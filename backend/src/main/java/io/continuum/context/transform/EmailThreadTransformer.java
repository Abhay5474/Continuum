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
    /** "Name: value" at the start of a line, as every header field is written. */
    private static final Pattern HEADER_LINE = Pattern.compile("[A-Za-z][A-Za-z0-9-]*:[ \t]");

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
        // And a message begins with them (RFC 5322 §2.1). A question followed
        // by a pasted email ("Summarise this: From: …") used to be claimed, parsed
        // as one message with the question as its headers, and come out as
        // "0 participants, From: unknown", with the question lost.
        if (!HEADER_LINE.matcher(head.stripLeading()).lookingAt()) {
            return false;
        }
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
            fromLine = sender(mime);
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
        List<Segment> segments = splitThread(body);

        List<CanonicalConversation.Message> messages = new ArrayList<>();
        Set<String> seenParagraphs = new LinkedHashSet<>();
        Map<String, Integer> senderCounts = new LinkedHashMap<>();
        List<SourceRef> provenance = new ArrayList<>();
        int quotedRemoved = 0;
        int signaturesRemoved = 0;

        for (int i = 0; i < segments.size(); i++) {
            String raw = segments.get(i).text();
            int before = raw.length();

            Stripped s = stripNoise(raw);
            if (s.removedSignature()) {
                signaturesRemoved++;
            }
            String deduped = dropSeenParagraphs(s.text(), seenParagraphs);
            if (deduped.length() < s.text().length() || i > 0) {
                // A repeated paragraph dropped, or an earlier message lifted out
                // of its quote markup: either way quoted history was removed.
                quotedRemoved++;
            }

            // The first segment is the newest message in a top-posted thread,
            // sent by the From header. An older one is attributed to the name
            // its quote header gives ("On …, Priya Nair wrote:"), as written,
            // and left unattributed when the header names nobody.
            String from = i == 0 ? fromLine : segments.get(i).sender();
            if (from != null) {
                senderCounts.merge(from, 1, Integer::sum);
            } else {
                ambiguities.add(new Ambiguity(Ambiguity.Kind.STRUCTURE,
                        "message " + (i + 1),
                        "This message was recovered from quoted history, so its sender could not "
                                + "be established from the headers."));
            }

            SourceRef ref = new SourceRef(source, "message " + (i + 1),
                    i == 0 ? "top-level message"
                            : from == null ? "recovered from quoted history"
                            : "recovered from quoted history; sender from its quote header");
            provenance.add(ref);
            messages.add(new CanonicalConversation.Message(i + 1, from,
                    i == 0 ? sent : null, deduped.strip(),
                    i == 0 ? attachments : List.of(),
                    Math.max(0, before - deduped.length()), ref));
        }

        // One entry per person: the same address seen as a sender, in a quote
        // header and in To is one participant, not three.
        Map<String, CanonicalConversation.Participant> people = new LinkedHashMap<>();
        senderCounts.forEach((who, count) -> {
            String[] na = nameAndAddress(who);
            people.merge(na[1] != null ? na[1].toLowerCase(Locale.ROOT) : who.toLowerCase(Locale.ROOT),
                    new CanonicalConversation.Participant(na[0], na[1], count),
                    (a, b) -> new CanonicalConversation.Participant(a.name() != null ? a.name() : b.name(),
                            a.address(), a.sent() + b.sent()));
        });
        try {
            for (Address a : orEmpty(mime.getAllRecipients())) {
                if (a instanceof InternetAddress ia) {
                    String key = ia.getAddress() == null ? String.valueOf(ia.getPersonal())
                            : ia.getAddress().toLowerCase(Locale.ROOT);
                    people.putIfAbsent(key, new CanonicalConversation.Participant(
                            ia.getPersonal(), ia.getAddress(), 0));
                }
            }
        } catch (Exception unparsable) {
            // Recipients are useful context, not essential; a malformed header
            // must not lose the conversation. Their names, as written, still help.
            for (String header : List.of("To", "Cc")) {
                for (String who : asWritten(mime, header)) {
                    people.putIfAbsent(who.toLowerCase(Locale.ROOT),
                            new CanonicalConversation.Participant(who, null, 0));
                }
            }
        }
        List<CanonicalConversation.Participant> participants = new ArrayList<>(people.values());

        return new CanonicalConversation(source, subject, participants, messages,
                quotedRemoved, signaturesRemoved, ambiguities, provenance);
    }

    // --- splitting and stripping ---------------------------------------------

    /** One message recovered from a thread, and who its quote header says wrote it. */
    record Segment(String text, String sender) {
    }

    /**
     * Splits a top-posted thread into its messages, newest first.
     *
     * <p>A reply carries the message it answers below a quote header, every
     * line prefixed with {@code >}; that message carries the one before it one
     * level deeper. So the thread is read one level at a time: the text above a
     * header is this message, and the quoted block below it, with one level of
     * {@code >} removed, is read the same way. Dropping the quoted lines
     * instead, as this used to, deleted every earlier message: a three-email
     * thread came out as its last email and an empty one.
     *
     * <p>Where no marker is present the whole body is one message, which is the
     * correct answer for a first email.
     */
    static List<Segment> splitThread(String body) {
        List<Segment> out = new ArrayList<>();
        collect(body, null, out, 0);
        return out.isEmpty() ? List.of(new Segment(body.strip(), null)) : out;
    }

    private static final int MAX_DEPTH = 25;

    private static void collect(String text, String sender, List<Segment> out, int depth) {
        List<int[]> cuts = new ArrayList<>();
        for (Pattern p : QUOTE_MARKERS) {
            java.util.regex.Matcher m = p.matcher(text);
            while (m.find()) {
                cuts.add(new int[]{m.start(), m.end()});
            }
        }
        cuts.sort((a, b) -> Integer.compare(a[0], b[0]));

        int last = 0;
        String who = sender;
        boolean afterOutlookHeader = false;
        for (int[] cut : cuts) {
            if (cut[0] < last) {
                continue;
            }
            addSegment(text.substring(last, cut[0]), who, afterOutlookHeader, out, depth);
            String marker = text.substring(cut[0], cut[1]);
            String named = senderOf(marker);
            // "-----Original Message-----" names nobody; the From:/Sent: block
            // right after it does, so a name already found is kept.
            if (named != null || !ORIGINAL_MESSAGE.matcher(marker).find()) {
                who = named;
            }
            afterOutlookHeader = OUTLOOK_HEADER.matcher(marker).find();
            last = cut[1];
        }
        addSegment(text.substring(last), who, afterOutlookHeader, out, depth);
    }

    private static void addSegment(String segment, String who, boolean afterOutlookHeader,
                                   List<Segment> out, int depth) {
        if (afterOutlookHeader) {
            segment = dropHeaderBlock(segment);
        }
        StringBuilder own = new StringBuilder();
        StringBuilder quoted = new StringBuilder();
        for (String line : segment.split("\n", -1)) {
            String t = line.stripLeading();
            if (t.startsWith(">")) {
                String inner = t.substring(1);
                quoted.append(inner.startsWith(" ") ? inner.substring(1) : inner).append('\n');
            } else {
                own.append(line).append('\n');
            }
        }
        boolean hasOwn = !own.toString().isBlank();
        if (hasOwn) {
            out.add(new Segment(own.toString().strip(), who));
        }
        if (!quoted.toString().isBlank() && depth < MAX_DEPTH) {
            // A quoted block straight under a header belongs to whoever the
            // header named; one under text of its own has no header to go by.
            collect(quoted.toString(), hasOwn ? null : who, out, depth + 1);
        }
    }

    private static final Pattern ORIGINAL_MESSAGE =
            Pattern.compile("-{2,}\\s*(Original|Forwarded) message", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTLOOK_HEADER =
            Pattern.compile("^\\s*From:", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern WROTE =
            Pattern.compile("^\\s*(?:On|Le)\\s+(.+?)\\s*(?:wrote|a écrit)\\s*:?\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FROM_FIELD =
            Pattern.compile("^\\s*From:\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    /**
     * Who a quote header says wrote the quoted message, as written: "Priya Nair
     * &lt;priya@acme.example&gt;" from "On Sun, 2 Aug 2026 at 12:10, Priya Nair
     * &lt;priya@acme.example&gt; wrote:". The date in front has commas of its
     * own, so the name is what follows the last one. Null when it names nobody.
     */
    static String senderOf(String marker) {
        java.util.regex.Matcher m = WROTE.matcher(marker);
        String who = null;
        if (m.find()) {
            String inner = m.group(1).strip();
            int angle = inner.indexOf('<');
            int comma = inner.lastIndexOf(',', angle < 0 ? inner.length() : angle);
            who = (comma < 0 ? inner : inner.substring(comma + 1)).strip();
        } else {
            java.util.regex.Matcher f = FROM_FIELD.matcher(marker);
            if (f.find()) {
                who = f.group(1).strip();
            }
        }
        if (who == null || who.isEmpty() || who.length() > 120 || !who.matches("(?s).*\\p{L}.*")) {
            return null;
        }
        return who;
    }

    private static final Pattern NAME_ADDRESS = Pattern.compile("^\\s*\"?(.*?)\"?\\s*<([^<>\\s]+@[^<>\\s]+)>\\s*$");

    /** "Priya Nair &lt;priya@acme.example&gt;" as {name, address}; either may be null. */
    static String[] nameAndAddress(String who) {
        java.util.regex.Matcher m = NAME_ADDRESS.matcher(who);
        if (m.matches()) {
            String name = m.group(1).strip();
            return new String[]{name.isEmpty() ? null : name, m.group(2)};
        }
        String t = who.strip();
        return t.matches("[^\\s@<>]+@[^\\s@<>]+") ? new String[]{null, t} : new String[]{t, null};
    }

    /** The rest of an Outlook header block (the Sent date, To, Cc, Subject) is not content. */
    private static String dropHeaderBlock(String segment) {
        String[] lines = segment.split("\n", -1);
        int i = 0;
        // The remainder of the "Sent:" line the marker stopped inside.
        if (lines.length > 0 && !lines[0].isBlank()) {
            i = 1;
        }
        while (i < lines.length && HEADER_LINE.matcher(lines[i].stripLeading()).lookingAt()) {
            i++;
        }
        return String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length));
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

    /**
     * The sender. When the address does not parse — the prompt firewall runs
     * first and turns {@code priya@acme.example} into {@code [REDACTED_EMAIL]},
     * which is no address at all — the name as written is kept. Strict parsing
     * used to throw there, and every email pasted into a chat with redaction on
     * came out as "The message headers could not be read".
     */
    private static String sender(MimeMessage mime) {
        try {
            return addresses(mime.getFrom());
        } catch (Exception unparsable) {
            List<String> names = asWritten(mime, "From");
            return names.isEmpty() ? null : String.join(", ", names);
        }
    }

    /** A header's mailboxes as written: the display name, else the text inside the angle brackets. */
    private static List<String> asWritten(MimeMessage mime, String header) {
        List<String> out = new ArrayList<>();
        try {
            String raw = mime.getHeader(header, ",");
            if (raw == null) {
                return out;
            }
            for (String part : raw.split(",")) {
                String p = part.strip();
                int lt = p.indexOf('<');
                String name = (lt >= 0 ? p.substring(0, lt) : p).strip().replaceAll("^\"|\"$", "");
                if (name.isEmpty() && lt >= 0) {
                    name = p.substring(lt).replaceAll("[<>]", "").strip();
                }
                if (!name.isEmpty()) {
                    out.add(name);
                }
            }
        } catch (Exception ignored) {
            // Nothing readable: no names, which the caller already handles.
        }
        return out;
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
