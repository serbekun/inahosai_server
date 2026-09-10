package com.serbekun.inahosai.domain.http.dto;

/**
 * Response of {@code GET /api/v0/live/type}.
 *
 * @param type the live platform, one of {@code "youtube"}, {@code "zoom"} or
 *             {@code "none"}
 */
public record V0LiveTypeGetRes(String type) {}
