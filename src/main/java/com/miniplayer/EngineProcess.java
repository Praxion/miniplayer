package com.miniplayer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Owns the C++ audio_engine child process: spawns it, writes commands to
 * its stdin, and reads status lines from its stdout on a dedicated
 * background thread, dispatching parsed events to an {@link EngineListener}.
 *
 * This class has no JavaFX dependency on purpose - it only knows about
 * java.io/java.lang.Process, so it can be constructed and driven from a
 * plain main() or a test, with no JavaFX runtime required. App.java is
 * responsible for marshaling callbacks onto the FX Application Thread.
 *
 * Protocol reference: see the header comment in audio_engine.cpp. Briefly:
 *   Commands out (one per line): PLAY:<path>, PAUSE, RESUME, STOP,
 *       SEEK:<seconds>, VOLUME:<0.0-1.0>, QUIT
 *   Events in (one per line): READY, LOADED:<seconds>, POS:<seconds>,
 *       EVENT:<name>, ERROR:<message>
 */
public class EngineProcess {

    private final String[] command;
    private final EngineListener listener;

    private Process process;
    private BufferedWriter stdin;
    private volatile boolean running = false;

    public EngineProcess(String enginePath, EngineListener listener) {
        this(listener, enginePath);
    }

    /**
     * General form: takes the full command line as separate arguments,
     * e.g. {@code new EngineProcess(listener, "python3", "fake_engine.py")}.
     * The single-path constructor above is just this with a one-element
     * command array - kept for backward compatibility with existing
     * call sites (App.java uses it directly with the compiled binary's
     * path). This overload exists so tests can point EngineProcess at a
     * fake stand-in process (see EngineProcessFakeScriptTest) without
     * needing the real audio_engine binary or a real audio device.
     */
    public EngineProcess(EngineListener listener, String... command) {
        this.command = command;
        this.listener = listener;
    }

    /**
     * Launches the child process and starts the reader/drain threads.
     * Returns once the process has been spawned - it does NOT block
     * until READY arrives; listen for {@link EngineListener#onReady()}
     * for that.
     */
    public void start() throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        // Deliberately NOT merging stderr into stdout: stderr carries
        // noisy audio-backend diagnostics (ALSA/CoreAudio/WASAPI device
        // probing) that would otherwise corrupt the line-based protocol
        // this class parses.
        builder.redirectErrorStream(false);

        process = builder.start();
        stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        running = true;

        Thread stdoutThread = new Thread(this::readStdout, "engine-stdout-reader");
        stdoutThread.setDaemon(true);
        stdoutThread.start();

        Thread stderrThread = new Thread(this::drainStderr, "engine-stderr-drain");
        stderrThread.setDaemon(true);
        stderrThread.start();
    }

    // --- Commands -------------------------------------------------------

    public void play(String absolutePath) {
        send("PLAY:" + absolutePath);
    }

    public void pause() {
        send("PAUSE");
    }

    public void resume() {
        send("RESUME");
    }

    public void stop() {
        send("STOP");
    }

    public void seek(double seconds) {
        send("SEEK:" + seconds);
    }

    public void setVolume(float volume) {
        send("VOLUME:" + volume);
    }

    /**
     * Sends QUIT and waits (briefly) for the process to exit cleanly.
     * Call this from your window's close handler - forgetting to call it
     * leaves an orphaned audio_engine process running in the background.
     */
    public void shutdown() {
        send("QUIT");
        running = false;
        if (process == null) return;
        try {
            boolean exited = process.waitFor(3, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /**
     * Test-only hook: sends a raw line with no protocol validation.
     * Package-private on purpose - it exists so tests can drive
     * fake-process control commands that aren't part of the real
     * audio_engine protocol (e.g. fake_engine.py's CRASH_NOW,
     * HANG_ON_QUIT, EMIT_GARBAGE), without exposing send() itself as
     * public API. Do not call this from App.java or any production code.
     */
    void sendRawForTesting(String rawLine) {
        send(rawLine);
    }

    // --- Internals --------------------------------------------------------

    private synchronized void send(String command) {
        if (stdin == null) {
            listener.onError("Cannot send '" + command + "': engine not started");
            return;
        }
        try {
            stdin.write(command);
            stdin.newLine();
            stdin.flush();  // required - without this, commands sit in
                             // Java's own buffer instead of reaching the
                             // child, and the engine appears to "ignore"
                             // input.
        } catch (IOException e) {
            listener.onError("Failed to send '" + command + "': " + e.getMessage());
        }
    }

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                dispatch(line);
            }
        } catch (IOException e) {
            // Pipe broke - almost always means the child process died.
            // Fall through; onProcessExited below reports the real cause.
        }

        int exitCode = -1;
        try {
            if (process != null) {
                exitCode = process.waitFor();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        running = false;
        listener.onProcessExited(exitCode);
    }

    private void drainStderr() {
        // We don't act on this content, but it MUST be continuously read
        // regardless - the OS pipe buffer for stderr is finite, and audio
        // backends (ALSA especially) can print enough device-probing
        // output at startup to fill it. If nothing drains that pipe, the
        // child process blocks on its next stderr write and the whole
        // engine appears to hang. Swap the discard for a logger call if
        // you want this output for debugging.
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {
                // discarded
            }
        } catch (IOException ignored) {
            // Process exited; nothing more to drain.
        }
    }

    private void dispatch(String line) {
        if (line.equals("READY")) {
            listener.onReady();
        } else if (line.startsWith("LOADED:")) {
            listener.onLoaded(parseDouble(line.substring("LOADED:".length())));
        } else if (line.startsWith("POS:")) {
            listener.onPosition(parseDouble(line.substring("POS:".length())));
        } else if (line.startsWith("EVENT:")) {
            listener.onEvent(line.substring("EVENT:".length()));
        } else if (line.startsWith("ERROR:")) {
            listener.onError(line.substring("ERROR:".length()));
        } else {
            // Protocol violation - some line we don't recognize showed up
            // on stdout. Surface it rather than silently dropping it, so
            // a future engine-side bug (e.g. a stray debug print routed
            // to stdout instead of stderr) doesn't fail silently here.
            listener.onError("Unrecognized engine output: " + line);
        }
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}