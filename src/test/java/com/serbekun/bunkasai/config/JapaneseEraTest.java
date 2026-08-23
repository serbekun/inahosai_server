package com.serbekun.bunkasai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class JapaneseEraTest {

    @Test
    void formatsAReiwaYear() {
        assertThat(JapaneseEra.format(LocalDate.of(2026, 10, 3))).isEqualTo("令和8年");
    }

    @Test
    void theDayBeforeReiwaIsStillHeisei() {
        assertThat(JapaneseEra.format(LocalDate.of(2019, 4, 30))).isEqualTo("平成31年");
    }

    @Test
    void reiwaBeginsOnTheFirstOfMay2019() {
        // Java renders the first year of an era as "1", not "元".
        assertThat(JapaneseEra.format(LocalDate.of(2019, 5, 1))).isEqualTo("令和1年");
    }

    @Test
    void theEraComesFromTheDateNotTheCalendarYear() {
        // Both dates are in 2019, but they fall in different eras. Deriving the era from
        // the year alone would give the same answer for both.
        assertThat(JapaneseEra.format(LocalDate.of(2019, 1, 1)))
                .isNotEqualTo(JapaneseEra.format(LocalDate.of(2019, 12, 31)));
    }

    @Test
    void anUnsetDateFormatsAsEmpty() {
        assertThat(JapaneseEra.formatOrEmpty(null)).isEmpty();
        assertThat(JapaneseEra.formatOrEmpty(LocalDate.of(2026, 10, 3))).isEqualTo("令和8年");
    }
}
