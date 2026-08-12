package io.continuum.provider.model;

/**
 * An image attached to a message.
 *
 * <p>{@code url} is either an https URL or a {@code data:} URI, exactly as the
 * caller sent it. Continuum does not fetch, re-encode or inline it — a gateway
 * that downloads a customer's image to re-upload it has quietly become a
 * processor of that image, with everything that implies.
 */
public record ImagePart(String url, String detail) {
}
