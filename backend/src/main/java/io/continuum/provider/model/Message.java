package io.continuum.provider.model;

import java.util.List;

/**
 * One turn in the canonical conversation model. The workflow engine and stored
 * events only ever speak this shape; provider adapters translate to/from it.
 */
public record Message(Role role, String content, List<ToolCall> toolCalls) {

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null);
    }
}
