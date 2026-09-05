package com.miniplayer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Full end-to-end tests against your REAL compiled audio_engine binary -
 * the same thing ManualDrive exercised manually, now as real assertions.
 *
 * SKIPPED BY DEFAULT: a plain `mvn test` will not run this class, so it
 * never blocks a normal build on having the binary present. Run it
 * explicitly with:
 *
 *   mvn test -Dtest=EngineProcessRealBinaryIT -Daudio.engine.path=/full/path/to/audio_engine
 *
 * (This class is intentionally named with an "IT" suffix rather than
 * "Test" - Surefire's default include pattern only picks up *Test.java,
 * so it's automatically excluded from `mvn test` even without the
 * @EnabledIfSystemProperty guard. Both mechanisms doing the same job is
 * deliberate belt-and-suspenders, not redundancy you need to remove.)
 */
@ExtendWith(MockitoExtension.class)
@EnabledIfSystemProperty(named = "audio.engine.path", matches = ".+")
class EngineProcessRealBinaryIT {

    @Mock
    private EngineListener listener;

    private EngineProcess engine;
    private File testTone;

    @BeforeEach
    void setUp() throws Exception {
        testTone = generateTestTone(2.0);
        String enginePath = System.getProperty("audio.engine.path");
        engine = new EngineProcess(enginePath, listener);
        engine.start();
        verify(listener, timeout(2000)).onReady();
    }

    @AfterEach
    void tearDown() {
        if (engine.isAlive()) {
            engine.shutdown();
        }
        if (testTone != null) {
            testTone.delete();
        }
    }

    @Test
    void playsARealFileAndReportsDuration() {
        engine.play(testTone.getAbsolutePath());
        verify(listener, timeout(3000)).onLoaded(doubleThat(d -> d > 1.8 && d < 2.2));
    }

    @Test
    void pauseResumeSeekAgainstRealEngine() {
        engine.play(testTone.getAbsolutePath());
        verify(listener, timeout(3000)).onLoaded(anyDouble());

        engine.pause();
        verify(listener, timeout(2000)).onEvent("PAUSED");

        engine.resume();
        verify(listener, timeout(2000)).onEvent("RESUMED");

        engine.seek(1.5);
        verify(listener, timeout(2000)).onPosition(doubleThat(d -> d > 1.3));
    }

    @Test
    void finishedFiresExactlyOnceAgainstRealEngine() throws Exception {
        engine.play(testTone.getAbsolutePath()); // ~2s tone
        verify(listener, timeout(3000)).onLoaded(anyDouble());
        verify(listener, timeout(4000)).onEvent("FINISHED");

        Thread.sleep(1000); // give a few more reporter ticks a chance to fire
        verify(listener, times(1)).onEvent("FINISHED");
    }

    @Test
    void missingFileReportsErrorButEngineSurvives() {
        engine.play("/definitely/not/real/xyz.mp3");
        verify(listener, timeout(2000)).onError(contains("Failed to load file"));

        // The failed load must not have wedged the engine - it should
        // still accept a subsequent valid PLAY.
        engine.play(testTone.getAbsolutePath());
        verify(listener, timeout(3000)).onLoaded(anyDouble());
    }

    /**
     * Generates a short mono 16-bit PCM WAV tone entirely in Java using
     * the standard library's javax.sound.sampled - no ffmpeg, no fixture
     * file checked into the repo, no external tool dependency.
     */
    private static File generateTestTone(double seconds) throws IOException {
        int sampleRate = 44100;
        int frameCount = (int) (sampleRate * seconds);
        byte[] data = new byte[frameCount * 2];

        for (int i = 0; i < frameCount; i++) {
            short sample = (short) (3000 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
            data[2 * i] = (byte) (sample & 0xFF);
            data[2 * i + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        File file = File.createTempFile("junit_test_tone", ".wav");
        file.deleteOnExit();

        try (AudioInputStream audioStream = new AudioInputStream(
                new ByteArrayInputStream(data), format, frameCount)) {
            AudioSystem.write(audioStream, AudioFileFormat.Type.WAVE, file);
        }
        return file;
    }
}
