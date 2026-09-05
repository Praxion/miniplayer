package com.miniplayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Drives EngineProcess against fake_engine.py - a lightweight stand-in
 * for the real audio_engine binary that speaks the same protocol on
 * demand. This exercises the REAL ProcessBuilder/thread/pipe code in
 * EngineProcess, just without any real audio backend, so these tests
 * are fast, deterministic, and safe to run on every commit / in CI with
 * no audio hardware present.
 *
 * NOTE: assumes python3 is on PATH. On Windows this may be "python"
 * instead - if these tests fail to even reach onReady() there, that's
 * the first thing to check.
 */
@ExtendWith(MockitoExtension.class)
class EngineProcessFakeScriptTest {

    @Mock
    private EngineListener listener;

    private EngineProcess engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new EngineProcess(listener, "python3", FakeEngineScript.resolvePath());
        engine.start();
        verify(listener, timeout(2000)).onReady();
    }

    @AfterEach
    void tearDown() {
        if (engine.isAlive()) {
            engine.shutdown();
        }
    }

    @Test
    void playReportsLoadedDuration() {
        engine.play("whatever.wav");
        verify(listener, timeout(2000)).onLoaded(3.0);
    }

    @Test
    void positionStreamsWhilePlayingAndNeverMovesBackward() {
        engine.play("whatever.wav");
        verify(listener, timeout(2000)).onLoaded(3.0);

        ArgumentCaptor<Double> positions = ArgumentCaptor.forClass(Double.class);
        verify(listener, timeout(2000).atLeast(2)).onPosition(positions.capture());

        List<Double> values = positions.getAllValues();
        for (int i = 1; i < values.size(); i++) {
            assertTrue(values.get(i) >= values.get(i - 1),
                    "position moved backward while playing: " + values);
        }
    }

    @Test
    void pauseAndResumeFireExpectedEvents() {
        engine.play("whatever.wav");
        verify(listener, timeout(2000)).onLoaded(3.0);

        engine.pause();
        verify(listener, timeout(2000)).onEvent("PAUSED");

        engine.resume();
        verify(listener, timeout(2000)).onEvent("RESUMED");
    }

    @Test
    void stopFiresStoppedEvent() {
        engine.play("whatever.wav");
        verify(listener, timeout(2000)).onLoaded(3.0);

        engine.stop();
        verify(listener, timeout(2000)).onEvent("STOPPED");
    }

    @Test
    void seekCommandReachesProcessWithExactFormatting() {
        // fake_engine.py echoes anything it doesn't specifically handle
        // back as "ECHO:<line>", which EngineProcess.dispatch() treats
        // as an unrecognized protocol line - piggybacking on that here
        // to assert the exact text that left the JVM, e.g. no unexpected
        // "1.25E1"-style scientific notation on other inputs.
        engine.seek(12.5);
        verify(listener, timeout(2000)).onError(contains("ECHO:SEEK:12.5"));
    }

    @Test
    void volumeCommandNeverUsesLocaleDependentDecimalSeparator() {
        // Regression guard: if a future change formats volume with
        // something like String.format("%.2f", v) instead of
        // Float.toString(v), this would silently break on any machine
        // running under a comma-decimal locale (e.g. Locale.GERMANY),
        // since the C++ side always expects '.' as the decimal
        // separator. Float.toString/Double.toString are locale-
        // independent, so this should always pass - it exists to catch
        // a regression, not because it's currently at risk.
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            engine.setVolume(0.5f);
            verify(listener, timeout(2000)).onError(contains("ECHO:VOLUME:0.5"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
