package io.continuum.context;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An email or chat thread, reduced to the conversation that actually happened.
 *
 * <p>A twelve-message email thread contains the first message up to twelve
 * times. Every reply quotes everything before it, every message carries a
 * signature, and many carry a legal disclaimer longer than the message. Sending
 * that raw does two distinct kinds of damage.
 *
 * <p>The obvious one is cost: most of the tokens are duplicates. The subtle one
 * is worse. Because the oldest message appears most often, and because position
 * and repetition shape what a model attends to (Liu et al., <i>Lost in the
 * Middle</i>, TACL 2024), the redundancy actively skews the answer toward the
 * beginning of a thread whose point is usually at the end.
 *
 * <p>So this is not compression. It is removing text that was never new
 * information, and putting what remains in order.
 */
public record CanonicalConversation(String sourceName, String subject,
                                    List<Participant> participants, List<Message> messages,
                                    int quotedBlocksRemoved, int signaturesRemoved,
                                    List<Ambiguity> ambiguities,
                                    List<SourceRef> provenance) implements CanonicalContext {

    public record Participant(String name, String address, int sent) {

        public String display() {
            if (name == null || name.isBlank()) {
                return address == null ? "unknown" : address;
            }
            return address == null || address.isBlank() ? name : name + " <" + address + ">";
        }
    }

    /**
     * One message with only its own words.
     *
     * @param body            what this sender actually wrote, with quoted
     *                        history, signature and disclaimer removed
     * @param removedChars    how much was stripped, so the saving is auditable
     *                        rather than asserted
     * @param attachments     names only. The bytes are not the conversation, and
     *                        a model told an invoice was attached can ask for it
     */
    public record Message(int ordinal, String from, Instant sent, String body,
                          List<String> attachments, int removedChars, SourceRef source) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ordinal", ordinal);
            m.put("from", from);
            m.put("sent", sent == null ? null : sent.toString());
            m.put("body", body);
            m.put("attachments", attachments);
            m.put("removedChars", removedChars);
            m.put("source", source == null ? null : source.toMap());
            return m;
        }
    }

    @Override
    public ContextType type() {
        return ContextType.CONVERSATION;
    }

    @Override
    public Map<String, Object> structure() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("messages", messages.size());
        m.put("participants", participants.size());
        m.put("quotedBlocksRemoved", quotedBlocksRemoved);
        m.put("signaturesRemoved", signaturesRemoved);
        m.put("attachments", messages.stream().mapToInt(x -> x.attachments().size()).sum());
        m.put("charactersRemoved", messages.stream().mapToInt(Message::removedChars).sum());
        return m;
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type().name());
        m.put("sourceName", sourceName);
        m.put("subject", subject);
        m.put("participants", participants.stream().map(p -> Map.of(
                "name", String.valueOf(p.name()), "address", String.valueOf(p.address()),
                "sent", p.sent())).toList());
        m.put("messages", messages.stream().map(Message::toMap).toList());
        m.put("structure", structure());
        m.put("ambiguities", ambiguities.stream().map(Ambiguity::toMap).toList());
        return m;
    }

    @Override
    public String render(RenderBudget budget) {
        StringBuilder b = new StringBuilder();
        int limit = budget.maxChars();

        b.append("CONVERSATION");
        if (subject != null && !subject.isBlank()) {
            b.append(": ").append(subject);
        }
        b.append('\n');
        b.append(messages.size()).append(messages.size() == 1 ? " message" : " messages")
                .append(" between ").append(participants.size())
                .append(participants.size() == 1 ? " participant" : " participants").append(".\n");

        if (!participants.isEmpty()) {
            b.append("Participants: ");
            b.append(participants.stream().map(Participant::display)
                    .reduce((x, y) -> x + ", " + y).orElse(""));
            b.append('\n');
        }
        if (quotedBlocksRemoved > 0 || signaturesRemoved > 0) {
            // Said out loud so a model does not conclude the thread is thinner
            // than it was, and so a developer can see what the layer did.
            b.append("Quoted history and signatures have been removed; each message below "
                    + "contains only what its sender wrote.\n");
        }
        b.append('\n');

        boolean truncated = false;
        for (Message m : messages) {
            if (b.length() > limit) {
                truncated = true;
                break;
            }
            b.append("--- Message ").append(m.ordinal()).append(" ---\n");
            b.append("From: ").append(m.from() == null ? "unknown" : m.from()).append('\n');
            if (m.sent() != null) {
                b.append("Sent: ").append(m.sent()).append('\n');
            }
            if (!m.attachments().isEmpty()) {
                b.append("Attachments: ").append(String.join(", ", m.attachments())).append('\n');
            }
            b.append('\n').append(m.body().isBlank()
                    ? "(this message added no new text — it was a quoted reply only)"
                    : m.body()).append("\n\n");
        }

        if (truncated) {
            b.append("[Rendering stopped at the configured budget. Later messages exist and are "
                    + "not shown — do not treat the last message here as the end of the thread.]\n");
        }

        if (!ambiguities.isEmpty()) {
            b.append("\nUNRESOLVED\n");
            for (Ambiguity a : ambiguities) {
                b.append("  - ").append(a.where()).append(": ").append(a.detail()).append('\n');
            }
        }
        return b.toString();
    }
}
