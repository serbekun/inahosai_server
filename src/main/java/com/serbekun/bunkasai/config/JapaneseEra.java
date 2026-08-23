package com.serbekun.bunkasai.config;

import java.time.LocalDate;
import java.time.chrono.JapaneseChronology;
import java.time.chrono.JapaneseDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats a date as a Japanese era year, e.g. {@code 令和8年}.
 *
 * <p>The era is computed from the festival's start date rather than from the calendar
 * year, because eras change mid-year — 令和 began on 1 May 2019, so deriving the era from
 * January would give the wrong answer for 2019.
 *
 * <p>Before this existed the markup said {@code 令和8年} while the meta description said
 * {@code 2026}, in ten and five places respectively. Both now come from one date.
 */
public final class JapaneseEra {

    /**
     * {@code GGGG} is the full era name and {@code y} the year within it.
     *
     * <p>Note that Java renders the first year of an era as {@code 令和1年}, not
     * {@code 令和元年} as a Japanese reader would normally write it. That is left as-is:
     * it only affects the first year of a new era, and special-casing it would mean
     * hand-maintaining a rule the JDK already owns.
     */
    private static final DateTimeFormatter ERA_FORMAT =
            DateTimeFormatter.ofPattern("GGGGy年", Locale.JAPANESE)
                    .withChronology(JapaneseChronology.INSTANCE);

    private JapaneseEra() {}

    /**
     * Formats a date as its Japanese era year.
     *
     * @param date the date to convert; must not be null
     * @return the era string, e.g. {@code 令和8年}
     */
    public static String format(LocalDate date) {
        return JapaneseDate.from(date).format(ERA_FORMAT);
    }

    /**
     * Formats a date as its Japanese era year, tolerating an unset date.
     *
     * @param date the date to convert, or null when the festival date is not configured
     * @return the era string, or an empty string when {@code date} is null
     */
    public static String formatOrEmpty(LocalDate date) {
        return date == null ? "" : format(date);
    }
}
