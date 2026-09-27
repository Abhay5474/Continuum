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

    /**
     * One category of sensitive content and how to find it. {@code valueGroup}
     * is the regex group holding the secret itself; 0 means the whole match.
     * Context patterns ("my pin is 7849") redact only the value, so the reader
     * of the sanitised prompt still knows a PIN was there.
     */
    public enum PiiType {
        EMAIL("\\b[\\w.+-]+@[\\w-]+\\.[a-z]{2,}\\b", 0),
        // A number called a card or account number is one, checksum or not: a
        // made-up or mistyped card number is still one the user meant to share.
        CARD_NUMBER("(?i)\\b(?:card|credit|debit|account|acct|a/c)\\b[^\\d\\n]{0,30}?(\\d(?:[ -]?\\d){7,22})", 1),
        CREDIT_CARD("\\b(?:\\d[ -]*?){13,16}\\b", 0),
        // PINs, passwords, CVVs and one-time codes, named in the sentence.
        CREDENTIAL("(?i)\\b(?:pin|password|passcode|passwd|pwd|cvv|cvc|cvv2|otp|one[- ]time (?:code|password)|security code)"
                + "\\b(?:\\s*(?:number|no\\.?|code))?\\s*(?:is|was|=|:|-)?\\s*[\"']?([^\\s,;\"']{3,64})", 1),
        SSN("\\b\\d{3}-\\d{2}-\\d{4}\\b", 0),
        PHONE("\\b(?:\\+?\\d{1,3}[ -]?)?\\(?\\d{3}\\)?[ -]?\\d{3}[ -]?\\d{4}\\b", 0),
        IP_ADDRESS("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b", 0),
        API_KEY("\\b(?:sk-[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|cnt_live_[A-Za-z0-9]{16,}|AIza[0-9A-Za-z_-]{20,})\\b", 0),
        JWT("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b", 0);

        final Pattern pattern;
        final int valueGroup;

        PiiType(String regex, int valueGroup) {
            this.pattern = Pattern.compile(regex);
            this.valueGroup = valueGroup;
        }
    }

    /** What the outbound scan looks for: secrets a model might echo back, not ordinary prose. */
    private static final List<PiiType> OUTBOUND = List.of(
            PiiType.API_KEY, PiiType.JWT, PiiType.SSN, PiiType.CARD_NUMBER, PiiType.CREDIT_CARD, PiiType.CREDENTIAL);

    /**
     * Replaces each finding of {@code types} with a typed placeholder and counts
     * them. The value alone is replaced for context patterns. A text that is
     * already a placeholder is left alone, so a second pass changes nothing.
     */
    static String redact(String text, List<PiiType> types, boolean redact, List<Match> found) {
        String out = text;
        for (PiiType type : types) {
            Matcher m = type.pattern.matcher(out);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            int last = 0;
            while (m.find()) {
                int start = type.valueGroup > 0 ? m.start(type.valueGroup) : m.start();
                int end = type.valueGroup > 0 ? m.end(type.valueGroup) : m.end();
                String value = out.substring(start, end);
                if (value.startsWith("[REDACTED_")) {
                    continue;
                }
                if (type == PiiType.CREDIT_CARD && !luhnValid(value)) {
                    continue; // avoid flagging arbitrary long digit runs with no context
                }
                if (type == PiiType.CREDENTIAL && !statedAsValue(out.substring(m.start(), start), value)) {
                    continue; // "what is a pin code used for" names a PIN; it does not give one
                }
                count++;
                sb.append(out, last, start).append("[REDACTED_").append(type.name()).append(']');
                last = end;
            }
            if (count > 0) {
                found.add(new Match(type.name(), count));
                if (redact) {
                    sb.append(out.substring(last));
                    out = sb.toString();
                }
            }
        }
        return out;
    }

    /** Prompt-injection / jailbreak signatures with a severity weight in [0,1]. */
    private static final List<InjectionSig> INJECTION = List.of(
            new InjectionSig("override of earlier instructions",
                    "ignore (?:all |any )?(?:previous|prior|above|earlier) (?:instructions|prompts|rules)", 0.9),
            new InjectionSig("override of earlier instructions",
                    "disregard (?:all |the )?(?:previous|prior|above|system) (?:instructions|prompt|rules)", 0.9),
            new InjectionSig("request to forget instructions",
                    "forget (?:everything|all|your) (?:instructions|previous|rules)", 0.8),
            new InjectionSig("persona switch",
                    "you are (?:now|actually) (?:a |an )?(?:different|new|unrestricted|dan|jailbroken)", 0.85),
            new InjectionSig("jailbreak mode",
                    "(?:enable|enter|activate) (?:developer|dan|god|jailbreak|unrestricted) mode", 0.85),
            new InjectionSig("system prompt extraction",
                    "(?:reveal|print|show|repeat|leak) (?:your |the )?(?:system prompt|instructions|initial prompt)", 0.8),
            new InjectionSig("\"do anything now\" jailbreak", "do anything now", 0.7),
            new InjectionSig("request to ignore safety rules",
                    "ignore your (?:guidelines|programming|training|safety)", 0.85),
            new InjectionSig("unrestricted role-play", "pretend (?:you are|to be) (?:not |un)?(?:bound|restricted|an ai)", 0.6),
            new InjectionSig("injected chat-format markers", "<\\|im_(?:start|end)\\|>|\\[INST]|###\\s*system", 0.6));

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
        List<Match> redactions = new ArrayList<>();
        String sanitized = redact(text, List.of(PiiType.values()), redact, redactions);

        double injectionScore = 0;
        List<String> hits = new ArrayList<>();
        String lower = text.toLowerCase();
        for (InjectionSig sig : INJECTION) {
            if (sig.pattern.matcher(lower).find()) {
                injectionScore = Math.max(injectionScore, sig.weight);
                if (!hits.contains(sig.label())) {
                    hits.add(sig.label());
                }
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
        List<Match> redactions = new ArrayList<>();
        String sanitized = redact(text, OUTBOUND, redact, redactions);
        return new OutboundResult(sanitized, redactions);
    }

    private static final Pattern STATED = Pattern.compile("(?i)(?:\\bis|\\bwas|=|:)\\s*[\"']?$");

    /** A credential is given when its value has a digit, or follows "is", ":" or "=". */
    private static boolean statedAsValue(String lead, String value) {
        return value.chars().anyMatch(Character::isDigit) || STATED.matcher(lead).find();
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

    /** A signature; {@code label} is what a person is told was found, never the regex. */
    private record InjectionSig(String label, Pattern pattern, double weight) {
        InjectionSig(String label, String regex, double weight) {
            this(label, Pattern.compile(regex), weight);
        }
    }
}
