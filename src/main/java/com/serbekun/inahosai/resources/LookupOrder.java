package com.serbekun.inahosai.resources;

/**
 * Where a resource is looked up, and in what order.
 *
 * <p>The mode is chosen in code per runtime rather than in the config, because it is a
 * property of the deployment, not of the school:
 *
 * <ul>
 *   <li>{@link #DISK_FIRST} — a file on disk shadows the packaged copy. Used by tests and
 *       by anything that wants live overrides on top of the bundled templates.</li>
 *   <li>{@link #CLASSPATH_FIRST} — the packaged copy wins when both exist.</li>
 *   <li>{@link #DISK_ONLY} — only the on-disk directory is read. This is the production
 *       mode: after {@code ResourceUnpacker} has written the templates once, the running
 *       server never falls back to the jar, so private files can never be shadowed by a
 *       packaged file of the same name and nothing is read from the binary at request
 *       time.</li>
 * </ul>
 */
public enum LookupOrder {

    /** Disk first, then the classpath. */
    DISK_FIRST,

    /** Classpath first, then disk. */
    CLASSPATH_FIRST,

    /** Disk only; the classpath is never consulted. */
    DISK_ONLY
}
