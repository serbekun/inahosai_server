package com.serbekun.bunkasai.resources;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the ETag used for every cacheable response in this server.
 *
 * <p>Extracted from {@link ResourceCache} so that pre-rendered pages and cached static
 * files tag their bodies identically — two ETag schemes on one origin would be a source
 * of confusing cache behaviour rather than a real choice.
 */
public final class Etags {

    private Etags() {}

    /**
     * Computes a strong-looking, cheap ETag: the first 8 bytes of the SHA-256 of the
     * content, hex encoded and quoted.
     *
     * @param data the bytes to tag
     * @return the quoted ETag value
     */
    public static String of(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            return "\"" + HexFormat.of().formatHex(digest, 0, 8) + "\"";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
