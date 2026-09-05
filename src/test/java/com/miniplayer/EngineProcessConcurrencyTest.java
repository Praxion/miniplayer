package com.miniplayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Hammers EngineProcess with commands from multiple threads at once -
 * the kind of thing a flaky double-click, a queue auto-advance racing
 * with a manual click, or a slider drag racing with a POS: update could
 * plausibly trigger in the real app. Uses the fake engine so this runs
 * fast and doesn't depend on real audio hardware.
 */
@ExtendWith(MockitoExtension.class)
class EngineProcessConcurrencyTest {

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
    @Timeout(30)
    void concurrentCommandsFromManyThreadsDoNotCrashTheEngine() throws InterruptedException {
        int threadCount = 8;
        int iterationsPerThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger failures = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < iterationsPerThread; i++) {
                    try {
                        engine.play("dummy.wav");
                        engine.pause();
                        engine.resume();
                        engine.seek(i * 0.1);
                        engine.setVolume((i % 10) / 10f);
                        engine.stop();
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    }
                }
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS),
                "commands did not finish within the time budget - possible deadlock");

        for (Future<?> f : futures) {
            assertDoesNotThrow(() -> {
                f.get();
            });
        }
        assertEquals(0, failures.get(), "one or more threads threw while sending commands");
        assertTrue(engine.isAlive(), "engine process died under concurrent command load");
    }

    @Test
    @Timeout(15)
    void concurrentShutdownCallsFromMultipleThreadsAreSafe() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(pool.submit(() -> engine.shutdown()));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        for (Future<?> f : futures) {
            assertDoesNotThrow(() -> {
                f.get();
            });
        }
        assertFalse(engine.isAlive(), "process should be dead after concurrent shutdown() calls");
    }
}
