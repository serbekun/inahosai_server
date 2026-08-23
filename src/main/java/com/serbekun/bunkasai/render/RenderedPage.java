package com.serbekun.bunkasai.render;

/**
 * One page, already rendered.
 *
 * <p>Rendering all five pages takes milliseconds, so it happens once at startup and the
 * bytes are held for the life of the process. Requests then do no templating at all —
 * they write a byte array and compare an ETag.
 *
 * <p>The body is treated as immutable. Nothing hands the array out to a caller that
 * mutates it, and the map holding these is replaced wholesale rather than edited.
 *
 * @param body        the rendered bytes, UTF-8
 * @param etag        the quoted ETag of {@code body}
 * @param contentType the full Content-Type header value, including charset
 */
public record RenderedPage(byte[] body, String etag, String contentType) {}
