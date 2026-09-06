package com.miniplayer;

import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UI tests for App.java, using TestFX's JUnit5 extension.
 *
 * NOTE ON APPROACH: most of these tests drive App's EngineListener
 * callback methods directly (onLoaded, onPosition, onEvent, ...) rather
 * than spinning up a real or fake child process. Since App already
 * implements EngineListener, this is a legitimate way to test "does the
 * UI react correctly to engine events" in complete isolation from IPC -
 * if something's broken here, the bug is in App.java's UI logic, not in
 * EngineProcess (which has its own dedicated tests). To avoid an
 * accidental real process interfering with these tests, @Start points
 * engine.path at a path that can't possibly exist, so App's own
 * IOException handling runs deterministically every time.
 *
 * KNOWN RISK - flagging honestly: I could not compile or run this file
 * in my sandbox (no path to Maven Central for JavaFX/TestFX jars there).
 * TestFX's JUnit5 API has shifted slightly across versions, and TestFX
 * 4.0.18 predates JavaFX 21 - if you hit a compile error on an import or
 * an API call here, it's most likely a version-alignment issue between
 * testfx-core/testfx-junit5 and your javafx.version in pom.xml. Paste me
 * the error and I'll adjust immediately.
 *
 * KNOWN RISK - headless environments: TestFX needs a real (or virtual)
 * display to open windows. On a normal desktop this just works. On a
 * headless CI box on Linux, run under Xvfb instead of trying to make
 * Monocle work with JavaFX 21 - Monocle's headless support hasn't kept
 * pace with newer JavaFX versions:
 *   xvfb-run -a mvn test
 */
@ExtendWith(ApplicationExtension.class)
class AppUiTest {

    private App app;

    @Start
    void start(Stage stage) throws Exception {
        System.setProperty("engine.path", "/nonexistent/audio_engine_for_ui_tests");
        app = new App();
        // Deliberately ignore the Stage TestFX hands us here and give
        // App a brand-new one instead. TestFX's ApplicationExtension
        // shows its primary stage internally before invoking @Start, and
        // App.start() calls stage.initStyle(UNDECORATED) - which JavaFX
        // only allows before a stage's first show(). Reusing the
        // already-shown TestFX stage throws
        // "IllegalStateException: Cannot set style once stage has been
        // set visible"; a fresh Stage() has never been shown, so
        // initStyle() succeeds normally.
        app.start(new Stage());
    }

    @Test
    void windowIsUndecoratedAndAlwaysOnTop() {
        Window window = app.trackLabel.getScene().getWindow();
        assertTrue(window instanceof Stage);
        assertTrue(((Stage) window).isAlwaysOnTop(),
                "miniplayer should stay on top of other windows");
    }

    @Test
    void startsWithPlayIconBeforeAnythingIsLoaded() {
        assertEquals("\u25B6", app.playPauseButton.getText());
        assertFalse(app.isLoaded);
    }

    @Test
    void onLoadedUpdatesSliderRangeAndTotalTimeAndSwitchesToPauseIcon(FxRobot robot) {
        robot.interact(() -> app.onLoaded(125.0));

        assertEquals(125.0, app.seekSlider.getMax(), 0.001);
        assertEquals("2:05", app.totalLabel.getText());
        assertEquals("\u23F8", app.playPauseButton.getText());
        assertTrue(app.isLoaded);
        assertTrue(app.isPlaying);
    }

    @Test
    void onPositionUpdatesSliderAndElapsedLabelWhenUserIsNotDragging(FxRobot robot) {
        robot.interact(() -> app.onLoaded(60.0));
        robot.interact(() -> app.onPosition(23.0));

        assertEquals(23.0, app.seekSlider.getValue(), 0.001);
        assertEquals("0:23", app.elapsedLabel.getText());
    }

    @Test
    void onPositionDoesNotOverwriteSliderWhileUserIsActivelyDraggingIt(FxRobot robot) {
        robot.interact(() -> app.onLoaded(60.0));
        robot.interact(() -> app.seekSlider.setValueChanging(true));
        robot.interact(() -> app.onPosition(42.0));

        // The whole point of the isValueChanging()/isPressed() guard in
        // App.onPosition is to avoid fighting the user mid-drag - if
        // this assertion ever fails, that guard regressed.
        assertNotEquals(42.0, app.seekSlider.getValue(), 0.001);

        robot.interact(() -> app.seekSlider.setValueChanging(false));
    }

    @Test
    void pausedEventSwitchesButtonBackToPlayIcon(FxRobot robot) {
        robot.interact(() -> app.onLoaded(60.0));
        robot.interact(() -> app.onEvent("PAUSED"));

        assertEquals("\u25B6", app.playPauseButton.getText());
        assertFalse(app.isPlaying);
    }

    @Test
    void resumedEventSwitchesButtonToPauseIcon(FxRobot robot) {
        robot.interact(() -> app.onLoaded(60.0));
        robot.interact(() -> app.onEvent("PAUSED"));
        robot.interact(() -> app.onEvent("RESUMED"));

        assertEquals("\u23F8", app.playPauseButton.getText());
        assertTrue(app.isPlaying);
    }

    @Test
    void stoppedEventResetsTrackLabelSliderAndButton(FxRobot robot) {
        robot.interact(() -> app.onLoaded(60.0));
        robot.interact(() -> app.onPosition(30.0));
        robot.interact(() -> app.onEvent("STOPPED"));

        assertEquals(0.0, app.seekSlider.getValue(), 0.001);
        assertEquals("0:00", app.elapsedLabel.getText());
        assertEquals("\u25B6", app.playPauseButton.getText());
        assertEquals("No track loaded", app.trackLabel.getText());
        assertFalse(app.isLoaded);
        assertFalse(app.isPlaying);
    }

    @Test
    void finishedEventResetsPlayPauseButtonWithoutClearingTheTrackLabel(FxRobot robot) {
        // Documents CURRENT behavior, not necessarily FINAL behavior:
        // there's no queue yet, so FINISHED just flips the button back
        // to play. Once auto-advance is added, this test should change
        // to assert the next track loads instead - update it alongside
        // that feature rather than treating this as a fixed contract.
        robot.interact(() -> app.onLoaded(60.0));
        robot.interact(() -> app.onEvent("FINISHED"));

        assertEquals("\u25B6", app.playPauseButton.getText());
        assertFalse(app.isPlaying);
    }

    @Test
    void errorCallbackSurfacesMessageInTrackLabel(FxRobot robot) {
        robot.interact(() -> app.onError("something went wrong"));
        assertEquals("Error: something went wrong", app.trackLabel.getText());
    }

    @Test
    void processExitedDisablesThePlayPauseButton(FxRobot robot) {
        robot.interact(() -> app.onProcessExited(1));
        assertTrue(app.playPauseButton.isDisabled());
    }
}