package com.serbekun.bunkasai.domain.http.dto;

import java.util.List;

/**
 * Result of an {@code /admin/reload}.
 *
 * @param reloaded    how many pages were re-rendered
 * @param missingKeys config keys that are still unset
 */
public record ReloadRes(int reloaded, List<String> missingKeys) {}
