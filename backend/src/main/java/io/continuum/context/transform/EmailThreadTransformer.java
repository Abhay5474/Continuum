package io.continuum.context.transform;

import io.continuum.context.Ambiguity;
import io.continuum.context.CanonicalContext;
import io.continuum.context.CanonicalConversation;
import io.continuum.context.ContextTransformer;
import io.continuum.context.ContextType;
import io.continuum.context.SourceRef;
import jakarta.mail.Address;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An email thread into a conversation.
 *
 * <p>The transformation is subtractive, and every subtraction has a deterministic
 * anchor rather than a judgement.
 *
 * <ul>
 *   <li><b>Quoted history</b> — {@code >} prefixes (RFC 3676), "On &lt;date&gt;
 *       &lt;person&gt; wrote:", and Outlook's {@code -----Original Message-----}
 *       block. Everything from the first such marker is history the thread
 *       already contains earlier.</li>
 *   <li><b>Signatures</b> — the {@code "-- "} delimiter is standardised by RFC
 *       3676 and is exact. Where it is absent, a short trailing block of contact
 *       lines is used, which is the heuristic Carvalho &amp; Cohen (CEAS 2004)
 *       established and Mailgun's Talon implements.</li>
 *   <li><b>Disclaimers</b> — matched on the fixed legal phrasing that makes them
 *       recognisable, and only in the trailing part of a message.</li>
 *   <li><b>Residual duplicates</b> — a paragraph already present verbatim in an
 *       earlier message is dropped, which catches quoting styles no marker
 *       identifies.</li>
 * </ul>
 *
 * <p><b>Nothing is summarised or reworded.</b> Every sentence in the output was
 * written by the person it is attributed to. Where this transformer is unsure
 * whether a trailing block was a signature, it keeps the text and records an
 * ambiguity — deleting something a person wrote is the one failure mode that
 * cannot be recovered downstream.
 */
@Component
public class EmailThreadTransformer implements ContextTransformer {

    private static final Logger log = LoggerFactory.getLogger(EmailThreadTransformer.class);

    public static final int MAX_BYTES = 20 * 1024 * 1024;

    /** Where quoted history begins. Everything after the first match goes. */
    private static final List<Pattern> QUOTE_MARKERS = List.of(
            Pattern.compile("^\\s*-{2,}\\s*Original Message\\s*-{2,}\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            Pattern.compile("^\\s*-{2,}\\s*Forwarded message\\s*-{2,}\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            Pattern.compile("^\\s*On .{4,120}\\bwrote:\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            Pattern.compile("^\\s*Le .{4,120}\\ba écrit\\s*:\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE),
            Pattern.compile("^\\s*From:\\s*.{3,120}$\\s*^\\s*Sent:\\s*",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE));

    /**
     * RFC 3676's signature delimiter.
     *
     * <p>The standard says {@code "-- "} with a trailing space, and plenty of
     * clients send it. Plenty of others drop the space, as does any editor that
     * strips trailing whitespace on save, so both are accepted. A longer run of
     * dashes is a horizontal rule and deliberately does not match.
     */
    private static final Pattern SIGNATURE_DELIMITER =
            Pattern.compile("^--[ \\t]*$", Pattern.MULTILINE);

    private static final List<String> DISCLAIMER_PHRASES = List.of(
            "this email and any attachments", "confidential and may be privileged",
            "if you are not the intended recipient", "please consider the environment",
            "disclaimer:", "this message contains confidential information",
            "any unauthorised", "any unauthorized");

    /** Lines that make a trailing block look like a signature rather than prose. */
    private static final Pattern CONTACT_LINE = Pattern.compile(
            ".*(\\+\\d[\\d\\s().-]{6,}|@[\\w.-]+\\.\\w{2,}|www\\.|https?://"
                    + "|\\b(mobile|tel|phone|fax|director|manager|engineer|officer)\\b).*",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "email";
    }

    @Override
    public String label() {
        return "Email thread → Conversation";
    }

    @Override
    public ContextType produces() {
        return ContextType.CONVERSATION;
    }

    @Override
    public boolean supports(byte[] input, String filename) {
        if (input == null || input.length < 32) {
            return false;
        }
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (n.endsWith(".eml") || n.endsWith(".mbox") || n.endsWith(".msg")) {
            return true;
        }
        // A MIME message is recognisable from its headers, which must appear
        // before the body and in the first few lines.
        String head = new String(input, 0, Math.min(input.length, 2048), StandardCharsets.UTF_8);
        int score = 0;
        for (String h : List.of("From:", "To:", "Subject:", "Date:", "Message-ID:",
                "MIME-Version:", "Received:")) {
            if (head.contains("\n" + h) || head.startsWith(h)) {
                score++;
            }
        }
        return score >= 3;
    }

    @Override
    public String rawTextBaseline(byte[] input, String filename) {
        // The whole message source, quotes and all: what a developer pasting an
        // email into a prompt would actually send.
        return new String(input, StandardCharsets.UTF_8);
    }

    @Override
    public CanonicalContext transform(byte[] input, String filename) {
        String source = filename == null || filename.isBlank() ? "email" : filename;
        List<Ambiguity> ambiguities = new ArrayList<>();

        if (input == null || input.length == 0) {
            return empty(source, "The message was empty.");
        }
        if (input.length > MAX_BYTES) {
            return empty(source, "The message is larger than " + (MAX_BYTES / 1024 / 1024)
                    + "MB and was not read.");
        }

        MimeMessage mime;
        try {
            Session session = Session.getInstance(new Properties());
            mime = new MimeMessage(session, new ByteArrayInputStream(input));
        } catch (Exception e) {
            log.debug("MIME parse failed: {}", e.toString());
            return empty(source, "This does not parse as a MIME message. It may be truncated or "
                    + "in a proprietary format.");
        }

        String subject;
        String fromLine;
        Instant sent;
        String body;
        List<String> attachments = new ArrayList<>();
        try {
            subject = mime.getSubject();
            fromLine = addresses(mime.getFrom());
            sent = mime.getSentDate() == null ? null : mime.getSentDate().toInstant();
            body = extractText(mime, attachments);
        } catch (Exception e) {
            return empty(source, "The message headers could not be read.");
        }

        if ((body == null || body.isBlank()) && subject == null && fromLine == null) {
            // MIME parsing is lenient enough to accept three arbitrary bytes and
            // call the result a message. No headers and no body is not one.
            return empty(source, "This does not parse as a MIME message — it has no headers and "
                    + "no body.");
        }
        if (body == null || body.isBlank()) {
            ambiguities.add(new Ambiguity(Ambiguity.Kind.STRUCTURE, source,
                    "No plain-text body was found. The message may be HTML-only, which this "
                            + "transformer does not render."));
            body = "";
        }

        // A thread arrives as one message containing every earlier one quoted
        // inside it. Splitting on the quote markers recovers the messages;
        // failing to split would leave the whole thread as one blob.
        List<String> segments = splitThread(body);

        List<CanonicalConversation.Message> messages = new ArrayList<>();
        Set<String> seenParagraphs = new LinkedHashSet<>();
        Map<String, Integer> senderCounts = new LinkedHashMap<>();
        List<SourceRef> provenance = new ArrayList<>();
        int quotedRemoved = 0;
        int signaturesRemoved = 0;

        for (int i = 0; i < segments.size(); i++) {
            String raw = segments.get(i);
            int before = raw.length();

            Stripped s = stripNoise(raw);
            if (s.removedSignature()) {
                signaturesRemoved++;
            }
            String deduped = dropSeenParagraphs(s.text(), seenParagraphs);
            if (deduped.length() < s.text().length()) {
                quotedRemoved++;
            }

            // The first segment is the newest message in a top-posted thread;
            // the sender of the older ones is not reliably recoverable from the
            // quote header, so it is left unattributed rather than guessed.
            String from = i == 0 ? fromLine : null;
            if (from != null) {
                senderCounts.merge(from, 1, Integer::sum);
            } else {
                ambiguities.add(new Ambiguity(Ambiguity.Kind.STRUCTURE,
                        "message " + (i + 1),
                        "This message was recovered from quoted history, so its sender could not "
                                + "be established from the headers."));
            }

            SourceRef ref = new SourceRef(source, "message " + (i + 1),
                    i == 0 ? "top-level message" : "recovered from quoted history");
            provenance.add(ref);
            messages.add(new CanonicalConversation.Message(i + 1, from,
                    i == 0 ? sent : null, deduped.strip(),
                    i == 0 ? attachments : List.of(),
                    Math.max(0, before - deduped.length()), ref));
        }

        List<CanonicalConversation.Participant> participants = new ArrayList<>();
        senderCounts.forEach((who, count) ->
                participants.add(new CanonicalConversation.Participant(who, null, count)));
        try {
            for (Address a : orEmpty(mime.getAllRecipients())) {
                if (a instanceof InternetAddress ia) {
                    participants.add(new CanonicalConversation.Participant(
                            ia.getPersonal(), ia.getAddress(), 0));
                }
            }
        } catch (Exception ignored) {
            // Recipients are useful context, not essential; a malformed header
            // must not lose the conversation.
        }

        return new CanonicalConversation(source, subject, participants, messages,
                quotedRemoved, signaturesRemoved, ambiguities, provenance);
    }

    // --- splitting and stripping ---------------------------------------------

    /**
     * Splits a top-posted thread into its messages at the quote markers.
     *
     * <p>The markers are the reply boundaries every mail client writes. Where
     * none is present the whole body is one message, which is the correct answer
     * for a first email.
     */
    static List<String> splitThread(String body) {
        List<Integer> cuts = new ArrayList<>();
        for (Pattern p : QUOTE_MARKERS) {
            java.util.regex.Matcher m = p.matcher(body);
            while (m.find()) {
                cuts.add(m.start());
            }
        }
        cuts.sort(Integer::compareTo);

        List<String> out = new ArrayList<>();
        int last = 0;
        for (int cut : cuts) {
            if (cut <= last) {
                continue;
            }
            String segment = body.substring(last, cut).strip();
            if (!segment.isBlank()) {
                out.add(segment);
            }
            last = cut;
        }
        String tail = body.substring(last).strip();
        if (!tail.isBlank()) {
            out.add(tail);
        }
        return out.isEmpty() ? List.of(body.strip()) : out;
    }

    /** @param removedSignature whether a signature block was identified and cut */
    record Stripped(String text, boolean removedSignature) {
    }

    /**
     * Removes quote prefixes, the signature and any disclaimer.
     *
     * <p>Order matters: quote prefixes first, because a signature inside a
     * quoted block should go with the quote rather than be mistaken for this
     * message's own.
     */
    static Stripped stripNoise(String segment) {
        // Lines beginning with ">" are quoted history in every client.
        StringBuilder unquoted = new StringBuilder();
        for (String line : segment.split("\n", -1)) {
            if (line.stripLeading().startsWith(">")) {
                continue;
            }
            unquoted.append(line).append('\n');
        }
        String text = unquoted.toString();

        // Drop everything from the marker onward — the quote header itself is
        // not content.
        for (Pattern p : QUOTE_MARKERS) {
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find()) {
                text = text.substring(0, m.start());
            }
        }

        boolean removedSignature = false;
        java.util.regex.Matcher sig = SIGNATURE_DELIMITER.matcher(text);
        if (sig.find()) {
            // The RFC delimiter is exact — no heuristic needed.
            text = text.substring(0, sig.start());
            removedSignature = true;
        } else {
            int cut = heuristicSignatureStart(text);
            if (cut > 0) {
                text = text.substring(0, cut);
                removedSignature = true;
            }
        }

        text = removeDisclaimer(text);
        return new Stripped(text.strip(), removedSignature);
    }

    /**
     * Where a signature starts, when there is no delimiter.
     *
     * <p>Only the last few lines are considered, and only when most of them look
     * like contact details. A message that ends in prose keeps its ending —
     * deleting a person's last sentence is much worse than keeping a phone
     * number.
     */
    static int heuristicSignatureStart(String text) {
        String[] lines = text.split("\n", -1);
        // Never the first line. A cut at zero deletes the entire message, which
        // is the one outcome this heuristic must not be able to produce.
        int start = Math.max(1, lines.length - 7);
        int contact = 0;
        int considered = 0;
        int offset = -1;

        int pos = 0;
        for (int i = 0; i < lines.length; i++) {
            if (i == start) {
                offset = pos;
            }
            pos += lines[i].length() + 1;
            if (i >= start && !lines[i].isBlank()) {
                considered++;
                if (CONTACT_LINE.matcher(lines[i]).matches()) {
                    contact++;
                }
            }
        }
        // Two independent contact lines in a short trailing block, and a clear
        // majority of that block. Either alone produces false positives on
        // messages that simply end with a URL.
        return offset > 0 && considered >= 2 && contact >= 2
                && contact / (double) considered >= 0.6 ? offset : -1;
    }

    static String removeDisclaimer(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int earliest = -1;
        for (String phrase : DISCLAIMER_PHRASES) {
            int at = lower.indexOf(phrase);
            // Only in the trailing third: the same words can legitimately appear
            // in a message that is actually about confidentiality.
            if (at > text.length() * 0.4 && (earliest < 0 || at < earliest)) {
                earliest = at;
            }
        }
        if (earliest < 0) {
            return text;
        }
        int lineStart = text.lastIndexOf('\n', earliest);
        return text.substring(0, lineStart < 0 ? earliest : lineStart);
    }

    /**
     * Drops paragraphs already seen in an earlier message.
     *
     * <p>Catches the quoting styles no marker identifies — a client that
     * re-sends the previous message with no prefix at all. Whole paragraphs
     * rather than lines, so a repeated greeting does not delete a real one.
     */
    static String dropSeenParagraphs(String text, Set<String> seen) {
        StringBuilder out = new StringBuilder();
        for (String para : text.split("\n\\s*\n")) {
            String key = para.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
            if (key.length() < 40) {
                // Short lines repeat legitimately: "Thanks", "Best regards".
                out.append(para).append("\n\n");
                continue;
            }
            if (seen.add(key)) {
                out.append(para).append("\n\n");
            }
        }
        return out.toString().strip();
    }

    // --- MIME ----------------------------------------------------------------

    private static String extractText(Part part, List<String> attachments) throws Exception {
        Object content = part.getContent();

        if (content instanceof String s) {
            return part.isMimeType("text/html") ? htmlToText(s) : s;
        }
        if (content instanceof Multipart mp) {
            String plain = null;
            String html = null;
            for (int i = 0; i < mp.getCount(); i++) {
                Part child = mp.getBodyPart(i);
                String disposition = child.getDisposition();
                if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
                    String name = child.getFileName();
                    attachments.add(name == null ? "unnamed attachment" : name);
                    continue;
                }
                if (child.isMimeType("text/plain") && plain == null) {
                    plain = String.valueOf(child.getContent());
                } else if (child.isMimeType("text/html") && html == null) {
                    html = String.valueOf(child.getContent());
                } else if (child.getContent() instanceof Multipart) {
                    String nested = extractText(child, attachments);
                    if (plain == null) {
                        plain = nested;
                    }
                }
            }
            // Plain text is preferred: the HTML alternative says the same thing
            // wrapped in markup that costs tokens and carries no meaning.
            return plain != null ? plain : (html == null ? "" : htmlToText(html));
        }
        return "";
    }

    /** Enough HTML handling to recover the words; not a renderer. */
    static String htmlToText(String html) {
        String text = html
                .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n\n")
                .replaceAll("(?i)</(div|tr|li|h[1-6])\\s*>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        return text.replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String addresses(Address[] addresses) {
        Address[] list = orEmpty(addresses);
        if (list.length == 0) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (Address a : list) {
            if (a instanceof InternetAddress ia) {
                out.add(ia.getPersonal() == null || ia.getPersonal().isBlank()
                        ? ia.getAddress() : ia.getPersonal() + " <" + ia.getAddress() + ">");
            } else {
                out.add(a.toString());
            }
        }
        return String.join(", ", out);
    }

    private static Address[] orEmpty(Address[] addresses) {
        return addresses == null ? new Address[0] : addresses;
    }

    private static CanonicalConversation empty(String source, String why) {
        return new CanonicalConversation(source, null, List.of(), List.of(), 0, 0,
                List.of(new Ambiguity(Ambiguity.Kind.STRUCTURE, source, why)), List.of());
    }
}
