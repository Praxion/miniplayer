package com.miniplayer;

/**
 * Callbacks fired by {@link EngineProcess} as it reads lines from the
 * C++ audio engine's stdout.
 *
 * IMPORTANT: every one of these fires on the engine's background
 * stdout-reader thread, never on the JavaFX Application Thread. Any
 * implementation that touches JavaFX UI nodes must wrap its body in
 * {@code Platform.runLater(...)} - see App.java for the pattern.
 */
public interface EngineListener {

    /** The engine process started and printed its initial READY line. */
    void onReady();

    /** A PLAY succeeded; reports the track's total length. */
    void onLoaded(double durationSeconds);

    /** Periodic playback position update (fires ~4x/sec while loaded). */
    void onPosition(double positionSeconds);

    /**
     * One of the engine's EVENT: lines, with the "EVENT:" prefix
     * stripped - e.g. "PAUSED", "RESUMED", "STOPPED", "FINISHED".
     */
    void onEvent(String eventName);

    /**
     * One of the engine's ERROR: lines, with the "ERROR:" prefix
     * stripped. The engine keeps running after an error; this does not
     * mean the process died.
     */
    void onError(String message);

    /**
     * The child process actually exited (crashed, was killed, or shut
     * down cleanly after QUIT). After this fires, the EngineProcess
     * instance is no longer usable - construct a new one to relaunch.
     */
    void onProcessExited(int exitCode);
}
