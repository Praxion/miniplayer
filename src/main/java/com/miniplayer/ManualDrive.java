package com.miniplayer;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Not a real unit test - just a plain main() that drives EngineProcess
 * against the real compiled audio_engine binary and prints every
 * callback, to verify the Java <-> C++ wiring end to end before any
 * JavaFX code sits on top of it.
 *
 * Usage: java ManualDrive <path-to-audio_engine> <path-to-test.wav>
 */
public class ManualDrive implements EngineListener {

    private final CountDownLatch exited = new CountDownLatch(1);

    @Override
    public void onReady() {
        log("READY");
    }

    @Override
    public void onLoaded(double durationSeconds) {
        log("LOADED duration=" + durationSeconds);
    }

    @Override
    public void onPosition(double positionSeconds) {
        log("POS " + String.format("%.2f", positionSeconds));
    }

    @Override
    public void onEvent(String eventName) {
        log("EVENT " + eventName);
    }

    @Override
    public void onError(String message) {
        log("ERROR " + message);
    }

    @Override
    public void onProcessExited(int exitCode) {
        log("PROCESS EXITED code=" + exitCode);
        exited.countDown();
    }

    private static void log(String s) {
        System.out.println("[Java] " + s);
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 2) {
            System.err.println("Usage: ManualDrive <audio_engine path> <test audio file>");
            System.exit(1);
        }
        String enginePath = args[0];
        String audioFile = args[1];

        ManualDrive drive = new ManualDrive();
        EngineProcess engine = new EngineProcess(enginePath, drive);
        engine.start();

        Thread.sleep(300); // let READY arrive
        engine.play(audioFile);
        Thread.sleep(600);
        engine.pause();
        Thread.sleep(400);
        engine.resume();
        Thread.sleep(400);
        engine.seek(0.1);
        Thread.sleep(400);
        engine.setVolume(0.3f);
        Thread.sleep(400);
        engine.stop();
        Thread.sleep(200);
        engine.shutdown();

        drive.exited.await(3, TimeUnit.SECONDS);
    }
}
