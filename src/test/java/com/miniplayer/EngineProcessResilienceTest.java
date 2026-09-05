package com.miniplayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Error-injection tests: uses fake_engine.py's special control commands
 * (CRASH_NOW, HANG_ON_QUIT, EMIT_GARBAGE) to simulate failure modes that
 * are hard to trigger on demand with a real audio backend - a crash
 * mid-session, a child that refuses to exit, and a protocol violation.
 */
@ExtendWith(MockitoExtension.class)
class EngineProcessResilienceTest {

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
    void garbageLineIsReportedAsErrorNotSilentlyDropped() {
        // Directly exercises the "unrecognized protocol line" branch of
        // EngineProcess.dispatch() - guards against a future bug where a
        // stray debug print or malformed engine output goes unnoticed
        // by the Java side instead of surfacing.
        engine.sendRawForTesting("EMIT_GARBAGE");
        verify(listener, timeout(2000)).onError(contains("Unrecognized engine output"));
    }

    @Test
    @Timeout(5)
    void processCrashIsReportedWithItsExitCode() {
        engine.sendRawForTesting("CRASH_NOW");
        verify(listener, timeout(3000)).onProcessExited(7);
    }

    @Test
    @Timeout(8)
    void shutdownForceKillsAProcessThatIgnoresQuit() {
        engine.sendRawForTesting("HANG_ON_QUIT");

        long start = System.currentTimeMillis();
        engine.shutdown();
        long elapsed = System.currentTimeMillis() - start;

        // EngineProcess.shutdown() waits up to 3s for a graceful exit
        // before calling destroyForcibly() - this asserts that ceiling
        // is actually respected rather than shutdown() hanging forever
        // on a misbehaving child. Upper bound is generous (5s) to allow
        // for scheduling jitter without making the test flaky.
        assertTrue(elapsed < 5000,
                "shutdown() took " + elapsed + "ms - expected it to force-kill around 3s");
        assertFalse(engine.isAlive(), "process should be dead after shutdown()");
    }

    @Test
    void sendingCommandsAfterProcessDiedNeverThrows() throws InterruptedException {
        engine.sendRawForTesting("CRASH_NOW");
        verify(listener, timeout(3000)).onProcessExited(7);
        Thread.sleep(200); // let the closed pipe fully settle after the crash

        // Whether this surfaces as onError("Failed to send...") or is a
        // no-op depends on OS-level pipe-buffering timing right after a
        // crash - the one guarantee this test enforces is that calling
        // a command method on a dead engine never throws an uncaught
        // exception back into calling (i.e. UI) code.
        assertDoesNotThrow(() -> engine.pause());
        assertDoesNotThrow(() -> engine.seek(5.0));
        assertDoesNotThrow(() -> engine.setVolume(0.5f));
    }
}
