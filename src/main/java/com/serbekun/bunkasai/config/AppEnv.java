package com.serbekun.bunkasai.config;

/**
 * Which environment the server thinks it is running in.
 *
 * <p>Anything other than an explicit {@code dev} counts as production, so an unset
 * variable means production. A fork that forgets to set it gets the safe behaviour rather
 * than the convenient one — development aids have to be opted into, not out of.
 */
public final class AppEnv {

    /** Environment variable selecting the mode. */
    public static final String ENV_VAR = "BUNKASAI_ENV";

    /** The only value that enables development-only routes. */
    public static final String DEV = "dev";

    private AppEnv() {}

    /**
     * Whether development-only routes should be registered.
     *
     * @return true only when {@code BUNKASAI_ENV} is exactly {@code dev}
     */
    public static boolean isDev() {
        return isDev(System.getenv(ENV_VAR));
    }

    /**
     * Whether the given value selects development mode.
     *
     * @param value the raw environment value, possibly null
     * @return true only when it is {@code dev}, ignoring case and surrounding space
     */
    public static boolean isDev(String value) {
        return value != null && DEV.equalsIgnoreCase(value.strip());
    }
}
