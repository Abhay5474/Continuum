package io.continuum.firewall;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V8 — the Prompt Firewall: inbound PII redaction + prompt-injection detection,
 * and outbound secret-leak scanning.
 *
 * Grounded in OWASP <em>LLM Top-10</em> (LLM01: Prompt Injection is the #1 risk)
 * and the PII-detection tradition (Microsoft Presidio, Rebuff). Deterministic,
 * pattern-based, local — no data leaves the process to detect anything.
 *
 * <p>Design goals:
 * <ul>
 *   <li><b>Inbound redaction</b>: replace PII/secrets with typed placeholders
 *       ({@code [REDACTED_EMAIL]}, …) BEFORE the prompt leaves for the provider,
 *       so a customer's SSN or an API key never reaches Gemini/Groq.</li>
 *   <li><b>Injection detection</b>: score known jailbreak / instruction-override
 *       patterns; high scores can be blocked, lower ones flagged.</li>
 *   <li><b>Outbound scanning</b>: catch secrets the model echoed back.</li>
 * </ul>
 */
public final class PromptFirewall {

    /** One category of sensitive content and how to find it. */
    public enum PiiType {
        EMAIL("\\b[\\w.+-]+@[\\w-]+\\.[a-z]{2,}\\b"),
        CREDIT_CARD("\\b(?:\\d[ -]*?){13,16}\\b"),
        SSN("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
        PHONE("\\b(?:\\+?\\d{1,3}[ -]?)?\\(?\\d{3}\\)?[ -]?\\d{3}[ -]?\\d{4}\\b"),
        IP_ADDRESS("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),
        API_KEY("\\b(?:sk-[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|cnt_live_[A-Za-z0-9]{16,}|AIza[0-9A-Za-z_-]{20,})\\b"),
        JWT("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b");

        final Pattern pattern;

        PiiType(String regex) {
            this.pattern = Pattern.compile(regex);
        }
    }

    /** Prompt-injection / jailbreak signatures with a severity weight in [0,1]. */
    private static final List<InjectionSig> INJECTION = List.of(
            new InjectionSig("ignore (?:all |any )?(?:previous|prior|above|earlier) (?:instructions|prompts|rules)", 0.9),
            new InjectionSig("disregard (?:all |the )?(?:previous|prior|above|system) (?:instructions|prompt|rules)", 0.9),
            new InjectionSig("forget (?:everything|all|your) (?:instructions|previous|rules)", 0.8),
            new InjectionSig("you are (?:now|actually) (?:a |an )?(?:different|new|unrestricted|dan|jailbroken)", 0.85),
            new InjectionSig("(?:enable|enter|activate) (?:developer|dan|god|jailbreak|unrestricted) mode", 0.85),
            new InjectionSig("(?:reveal|print|show|repeat|leak) (?:your |the )?(?:system prompt|instructions|initial prompt)", 0.8),
            new InjectionSig("do anything now", 0.7),
            new InjectionSig("ignore your (?:guidelines|programming|training|safety)", 0.85),
            new InjectionSig("pretend (?:you are|to be) (?:not |un)?(?:bound|restricted|an ai)", 0.6),
            new InjectionSig("<\\|im_(?:start|end)\\|>|\\[INST]|###\\s*system", 0.6));

    private static final double BLOCK_THRESHOLD = 0.8;

    public record Match(String category, int count) {
    }

    /** Inbound scan result: the (possibly redacted) text + what was found/decided. */
    public record InboundResult(String sanitized, List<Match> redactions,
                                double injectionScore, boolean blocked, List<String> injectionHits) {
        public boolean changed() {
            return !redactions.isEmpty();
        }
    }

    public record OutboundResult(String sanitized, List<Match> redactions) {
        public boolean changed() {
            return !redactions.isEmpty();
        }
    }

    /** Redact PII/secrets and score injection risk on an inbound prompt. */
    public InboundResult scanInbound(String text, boolean redact, boolean blockOnInjection) {
        if (text == null || text.isBlank()) {
            return new InboundResult(text, List.of(), 0, false, List.of());
        }
        String sanitized = text;
        List<Match> redactions = new ArrayList<>();
        for (PiiType type : PiiType.values()) {
            Matcher m = type.pattern.matcher(sanitized);
            int count = 0;
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                if (type == PiiType.CREDIT_CARD && !luhnValid(m.group())) {
                    continue; // avoid flagging arbitrary long digit runs
                }
                count++;
                if (redact) {
                    m.appendReplacement(sb, "[REDACTED_" + type.name() + "]");
                }
            }
            if (redact) {
                m.appendTail(sb);
                sanitized = sb.toString();
            }
            if (count > 0) {
                redactions.add(new Match(type.name(), count));
            }
        }

        double injectionScore = 0;
        List<String> hits = new ArrayList<>();
        String lower = text.toLowerCase();
        for (InjectionSig sig : INJECTION) {
            if (sig.pattern.matcher(lower).find()) {
                injectionScore = Math.max(injectionScore, sig.weight);
                hits.add(sig.label());
            }
        }
        boolean blocked = blockOnInjection && injectionScore >= BLOCK_THRESHOLD;
        return new InboundResult(sanitized, redactions, injectionScore, blocked, hits);
    }

    /** Scan an outbound model response for leaked secrets/PII. */
    public OutboundResult scanOutbound(String text, boolean redact) {
        if (text == null || text.isBlank()) {
            return new OutboundResult(text, List.of());
        }
        String sanitized = text;
        List<Match> redactions = new ArrayList<>();
        // Only the high-confidence secret categories on the way out (avoid mangling normal prose).
        for (PiiType type : List.of(PiiType.API_KEY, PiiType.JWT, PiiType.SSN, PiiType.CREDIT_CARD)) {
            Matcher m = type.pattern.matcher(sanitized);
            int count = 0;
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                if (type == PiiType.CREDIT_CARD && !luhnValid(m.group())) {
                    continue;
                }
                count++;
                if (redact) {
                    m.appendReplacement(sb, "[REDACTED_" + type.name() + "]");
                }
            }
            if (redact) {
                m.appendTail(sb);
                sanitized = sb.toString();
            }
            if (count > 0) {
                redactions.add(new Match(type.name(), count));
            }
        }
        return new OutboundResult(sanitized, redactions);
    }

    /** Luhn checksum — filters out non-card digit strings so we don't over-redact. */
    private static boolean luhnValid(String candidate) {
        String digits = candidate.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean alt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (alt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    private record InjectionSig(String label, Pattern pattern, double weight) {
        InjectionSig(String regex, double weight) {
            this(regex, Pattern.compile(regex), weight);
        }
    }
}
