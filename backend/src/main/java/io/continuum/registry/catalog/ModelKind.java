package io.continuum.registry.catalog;

/**
 * What a listed model does. Only {@link #CHAT} models are tested and routed:
 * the providers list speech, safety and embedding models beside the chat ones,
 * and sending a chat request to one of those is a guaranteed failure.
 */
public enum ModelKind {
    CHAT,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    SAFETY,
    EMBEDDING,
    IMAGE,
    AUDIO,
    /** Another name for a model already listed: a pinned version or a moving "-latest" alias. */
    ALIAS,
    OTHER
}
