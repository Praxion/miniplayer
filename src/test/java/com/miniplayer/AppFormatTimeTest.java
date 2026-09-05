package com.miniplayer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * formatTime() is a pure function - no UI, no process, no threading - so
 * it gets the cheapest possible test: no JavaFX toolkit startup, no
 * Mockito, just plain JUnit5. This is deliberately the fastest test in
 * the whole suite and a good one to run constantly while iterating.
 */
class AppFormatTimeTest {

    @ParameterizedTest(name = "{0} seconds -> \"{1}\"")
    @CsvSource({
            "0,       0:00",
            "5,       0:05",
            "59,      0:59",
            "60,      1:00",
            "125,     2:05",
            "3599,    59:59",
            "3600,    60:00",
    })
    void formatsWholeSecondsAsMinutesColonSeconds(double seconds, String expected) {
        assertEquals(expected, App.formatTime(seconds));
    }

    @ParameterizedTest(name = "fractional {0} truncates to \"{1}\"")
    @CsvSource({
            "1.9,   0:01",
            "59.99, 0:59",
            "60.1,  1:00",
    })
    void truncatesFractionalSecondsRatherThanRounding(double seconds, String expected) {
        assertEquals(expected, App.formatTime(seconds));
    }

    @ParameterizedTest(name = "invalid input {0} clamps to 0:00")
    @CsvSource({
            "-1",
            "-0.5",
            "-3600",
    })
    void negativeValuesClampToZero(double seconds) {
        assertEquals("0:00", App.formatTime(seconds));
    }

    @org.junit.jupiter.api.Test
    void nanClampsToZeroInsteadOfThrowing() {
        // POS: parsing falls back to 0.0 on a malformed line (see
        // EngineProcess.parseDouble), but this guards the display layer
        // too in case NaN/Infinity ever reaches formatTime some other way.
        assertEquals("0:00", App.formatTime(Double.NaN));
    }
}
