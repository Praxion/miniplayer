package com.miniplayer;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.io.IOException;

/**
 * Minimal frameless, always-on-top miniplayer. Talks to the C++ audio
 * engine entirely through EngineProcess - this class only knows about
 * UI and never touches stdin/stdout directly.
 *
 * By default it looks for the engine binary at ./audio_engine (relative
 * to wherever the JVM's working directory is, which is NOT necessarily
 * where this jar/class lives). Override with:
 *   mvn javafx:run -Djavafx.args="" -Dengine.path=/full/path/to/audio_engine
 * or simply place the compiled audio_engine binary in the directory you
 * launch the app from.
 */
public class App extends Application implements EngineListener {

    private static final String DEFAULT_ENGINE_PATH = "./audio_engine";

    // Package-private (not private) so AppUiTest, which lives in this
    // same package, can assert on UI state directly after driving the
    // EngineListener callbacks below - no reflection or getters needed
    // purely for testability.
    EngineProcess engine;

    Label trackLabel;
    Slider seekSlider;
    Slider volumeSlider;
    Label elapsedLabel;
    Label totalLabel;
    Button playPauseButton;

    boolean isPlaying = false;
    boolean isLoaded = false;

    // For dragging the undecorated window by its top bar.
    private double dragOffsetX;
    private double dragOffsetY;

    @Override
    public void start(Stage stage) {
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setAlwaysOnTop(true);

        BorderPane root = buildUi(stage);
        Scene scene = new Scene(root, 380, 150);
        scene.setFill(null);
        scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());

        stage.setScene(scene);
        stage.setTitle("Mini Player");
        stage.show();

        stage.setOnCloseRequest(e -> shutdownAndExit());

        String enginePath = System.getProperty("engine.path", DEFAULT_ENGINE_PATH);
        engine = new EngineProcess(enginePath, this);
        try {
            engine.start();
        } catch (IOException e) {
            trackLabel.setText("Failed to start engine (see path?): " + e.getMessage());
        }
    }

    private void shutdownAndExit() {
        if (engine != null) {
            engine.shutdown();
        }
        Platform.exit();
        System.exit(0);
    }

    private BorderPane buildUi(Stage stage) {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");

        // --- Top bar: track name, close button, drag handle ---
        trackLabel = new Label("No track loaded");
        trackLabel.getStyleClass().add("track-label");
        HBox.setHgrow(trackLabel, Priority.ALWAYS);

        Button closeButton = new Button("\u2715");
        closeButton.getStyleClass().add("close-button");
        closeButton.setOnAction(e -> shutdownAndExit());

        HBox topBar = new HBox(8, trackLabel, closeButton);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.getStyleClass().add("top-bar");
        topBar.setPadding(new Insets(8, 8, 4, 12));

        // No OS title bar in undecorated mode, so the window needs its
        // own drag handling. Track the press point in scene coordinates,
        // then reposition the stage on every drag event.
        topBar.setOnMousePressed((MouseEvent e) -> {
            dragOffsetX = e.getSceneX();
            dragOffsetY = e.getSceneY();
        });
        topBar.setOnMouseDragged((MouseEvent e) -> {
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });

        root.setTop(topBar);

        // --- Center: seek slider with elapsed/total time ---
        seekSlider = new Slider(0, 1, 0);
        seekSlider.getStyleClass().add("seek-slider");
        HBox.setHgrow(seekSlider, Priority.ALWAYS);

        elapsedLabel = new Label("0:00");
        totalLabel = new Label("0:00");
        elapsedLabel.getStyleClass().add("time-label");
        totalLabel.getStyleClass().add("time-label");

        // Send SEEK only when the user releases the mouse, not on every
        // programmatic value change - otherwise incoming POS: updates
        // and the user's own drag would fight each other, and every
        // POS: tick would issue a redundant SEEK.
        seekSlider.setOnMouseReleased(e -> {
            if (isLoaded && engine != null) {
                engine.seek(seekSlider.getValue());
            }
        });

        HBox seekRow = new HBox(8, elapsedLabel, seekSlider, totalLabel);
        seekRow.setAlignment(Pos.CENTER);
        seekRow.setPadding(new Insets(4, 12, 4, 12));

        // --- Bottom: transport controls + volume ---
        playPauseButton = new Button("\u25B6");
        playPauseButton.getStyleClass().add("transport-button");
        playPauseButton.setOnAction(e -> onPlayPauseClicked(stage));

        Button openButton = new Button("Open\u2026");
        openButton.getStyleClass().add("open-button");
        openButton.setOnAction(e -> onOpenClicked(stage));

        Button stopButton = new Button("\u25A0");
        stopButton.getStyleClass().add("transport-button");
        stopButton.setOnAction(e -> {
            if (engine != null) engine.stop();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label volumeIcon = new Label("Vol");
        volumeIcon.getStyleClass().add("time-label");

        volumeSlider = new Slider(0, 1, 1.0);
        volumeSlider.setPrefWidth(80);
        volumeSlider.getStyleClass().add("volume-slider");
        volumeSlider.valueProperty().addListener((obs, oldV, newV) -> {
            if (engine != null) {
                engine.setVolume(newV.floatValue());
            }
        });

        HBox controls = new HBox(10, openButton, playPauseButton, stopButton,
                spacer, volumeIcon, volumeSlider);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(4, 12, 10, 12));

        VBox center = new VBox(seekRow, controls);
        root.setCenter(center);

        return root;
    }

    private void onOpenClicked(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose an audio file");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Audio files", "*.mp3", "*.wav", "*.flac", "*.ogg"));
        File file = chooser.showOpenDialog(stage);
        if (file != null && engine != null) {
            trackLabel.setText(file.getName());
            engine.play(file.getAbsolutePath());
        }
    }

    private void onPlayPauseClicked(Stage stage) {
        if (engine == null) return;
        if (!isLoaded) {
            onOpenClicked(stage);
            return;
        }
        if (isPlaying) {
            engine.pause();
        } else {
            engine.resume();
        }
    }

    // Package-private (not private) so it can be unit-tested directly as
    // a pure function - see AppFormatTimeTest - without needing to spin
    // up the JavaFX toolkit at all.
    static String formatTime(double seconds) {
        if (seconds < 0 || Double.isNaN(seconds)) {
            seconds = 0;
        }
        int total = (int) seconds;
        return String.format("%d:%02d", total / 60, total % 60);
    }

    // ------------------------------------------------------------------
    // EngineListener callbacks. These arrive on EngineProcess's stdout
    // reader thread - every one wraps its UI-touching work in
    // Platform.runLater. Skipping that is the single most common bug in
    // this kind of app: it often "mostly works" and then throws
    // IllegalStateException from a random thread under load.
    // ------------------------------------------------------------------

    @Override
    public void onReady() {
        Platform.runLater(() -> trackLabel.setText("Ready \u2014 click Open to choose a file"));
    }

    @Override
    public void onLoaded(double durationSeconds) {
        Platform.runLater(() -> {
            isLoaded = true;
            isPlaying = true;
            seekSlider.setMax(durationSeconds);
            totalLabel.setText(formatTime(durationSeconds));
            playPauseButton.setText("\u23F8");
        });
    }

    @Override
    public void onPosition(double positionSeconds) {
        Platform.runLater(() -> {
            if (!seekSlider.isPressed() && !seekSlider.isValueChanging()) {
                seekSlider.setValue(positionSeconds);
            }
            elapsedLabel.setText(formatTime(positionSeconds));
        });
    }

    @Override
    public void onEvent(String eventName) {
        Platform.runLater(() -> {
            switch (eventName) {
                case "PAUSED":
                    isPlaying = false;
                    playPauseButton.setText("\u25B6");
                    break;
                case "RESUMED":
                    isPlaying = true;
                    playPauseButton.setText("\u23F8");
                    break;
                case "STOPPED":
                    isPlaying = false;
                    isLoaded = false;
                    seekSlider.setValue(0);
                    elapsedLabel.setText("0:00");
                    playPauseButton.setText("\u25B6");
                    trackLabel.setText("No track loaded");
                    break;
                case "FINISHED":
                    isPlaying = false;
                    playPauseButton.setText("\u25B6");
                    // No queue yet - a future version would call
                    // engine.play(nextTrackPath) here instead of stopping.
                    break;
                default:
                    break;
            }
        });
    }

    @Override
    public void onError(String message) {
        Platform.runLater(() -> trackLabel.setText("Error: " + message));
    }

    @Override
    public void onProcessExited(int exitCode) {
        Platform.runLater(() -> {
            trackLabel.setText("Engine exited (code " + exitCode + ")");
            playPauseButton.setDisable(true);
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}