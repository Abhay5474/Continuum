package io.continuum.provider.model;

import java.util.List;

/**
 * One turn in a conversation.
 *
 * <p>The three-argument form is the text case and is used everywhere; the
 * canonical constructor adds the two things a real client sends that text alone
 * cannot carry — images, and the id tying a tool result back to the call that
 * asked for it. Without the latter a multi-turn tool conversation is malformed
 * and every provider rejects it.
 */
public record Message(Role role,
                      String content,
                      List<ToolCall> toolCalls,
                      List<ImagePart> images,
                      String toolCallId) {

    public Message(Role role, String content, List<ToolCall> toolCalls) {
        this(role, content, toolCalls, null, null);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null);
    }

    /** The same turn with different text — how redaction and compression rewrite. */
    public Message withContent(String newContent) {
        return new Message(role, newContent, toolCalls, images, toolCallId);
    }

    public boolean hasImages() {
        return images != null && !images.isEmpty();
    }
}
