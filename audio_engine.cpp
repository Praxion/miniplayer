// audio_engine.cpp
//
// Standalone audio engine, meant to be spawned as a child process by the
// JavaFX miniplayer via ProcessBuilder. No networking, no GUI — it just
// decodes and plays audio files and talks to its parent over stdin/stdout.
//
// Build (Linux/macOS):
//   g++ -std=c++17 -O2 audio_engine.cpp -o audio_engine -lpthread -ldl -lm -lpthread
// Build (Windows, MSVC Developer Prompt):
//   cl /EHsc /std:c++17 /O2 audio_engine.cpp /Fe:audio_engine.exe
//
// -----------------------------------------------------------------------
// Wire protocol. Every message on both directions is a single line of text
// terminated by '\n'. Commands come in on stdin; events/status go out on
// stdout. stderr is reserved for engine crash diagnostics only — Java
// should never need to parse it in normal operation.
//
// Commands (Java -> C++):
//   PLAY:<absolute path>     Load a file and start playing it from 0:00.
//   PAUSE                    Pause the currently loaded sound.
//   RESUME                   Resume a paused sound.
//   STOP                     Stop and unload the current sound entirely.
//   SEEK:<seconds>           Jump to an absolute position (float seconds).
//   VOLUME:<0.0-1.0>         Set linear playback volume.
//   QUIT                     Flush, tear down the engine, and exit(0).
//
// Events (C++ -> Java):
//   READY                        Emitted once at startup once the audio
//                                device is initialized and the engine is
//                                ready to accept commands.
//   LOADED:<durationSeconds>     A PLAY succeeded; reports total length.
//   POS:<currentSeconds>         Emitted a few times a second while a
//                                sound is loaded (playing or paused).
//   EVENT:PAUSED
//   EVENT:RESUMED
//   EVENT:STOPPED
//   EVENT:FINISHED               The sound played to its natural end.
//   ERROR:<message>              Bad command, missing file, decode
//                                failure, or device error. The engine
//                                keeps running after an ERROR; it does
//                                not exit unless told to via QUIT.
// -----------------------------------------------------------------------

#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>

namespace {

// How often we emit POS: updates while something is loaded.
constexpr auto kPositionReportInterval = std::chrono::milliseconds(250);

// Trim trailing \r (in case Java writes CRLF line endings) and whitespace.
std::string trim(const std::string& s) {
    size_t end = s.find_last_not_of(" \t\r\n");
    if (end == std::string::npos) return "";
    return s.substr(0, end + 1);
}

// Splits "PLAY:/some/path.mp3" into {"PLAY", "/some/path.mp3"}.
// If there's no ':', value is empty.
std::pair<std::string, std::string> splitCommand(const std::string& line) {
    auto pos = line.find(':');
    if (pos == std::string::npos) {
        return {line, ""};
    }
    return {line.substr(0, pos), line.substr(pos + 1)};
}

}  // namespace

// ---------------------------------------------------------------------
// AudioEngine wraps miniaudio's high-level ma_engine/ma_sound API.
//
// Thread-safety note: the reporter thread (position polling) and the main
// command thread both touch the same ma_sound*, so every access to
// current_ / engine state goes through mutex_. miniaudio's own playback
// happens on its internal device callback thread and is safe to call into
// concurrently from here — ma_sound_* functions are designed for that.
// ---------------------------------------------------------------------
class AudioEngine {
public:
    AudioEngine() = default;

    ~AudioEngine() {
        unloadLocked();
        if (initialized_) {
            ma_engine_uninit(&engine_);
        }
    }

    // Returns false if the audio device could not be initialized. In that
    // case the process should print ERROR + READY-failure and keep running
    // (it can still be useful for the parent to know it launched, even if
    // it can't produce sound) or exit, depending on how strict you want
    // startup to be. Here we treat it as fatal since a silent player is
    // not useful.
    bool init() {
        ma_result result = ma_engine_init(nullptr, &engine_);
        if (result != MA_SUCCESS) {
            return false;
        }
        initialized_ = true;
        return true;
    }

    // PLAY:<path>
    void play(const std::string& path) {
        std::lock_guard<std::mutex> lock(mutex_);
        unloadInternal();

        ma_sound* sound = new ma_sound();
        ma_result result = ma_sound_init_from_file(
            &engine_, path.c_str(),
            MA_SOUND_FLAG_DECODE,  // decode up front so seeking is cheap/exact
            nullptr, nullptr, sound);

        if (result != MA_SUCCESS) {
            delete sound;
            emitError("Failed to load file: " + path);
            return;
        }

        result = ma_sound_start(sound);
        if (result != MA_SUCCESS) {
            ma_sound_uninit(sound);
            delete sound;
            emitError("Failed to start playback: " + path);
            return;
        }

        current_ = sound;
        finishedReported_ = false;
        paused_ = false;

        float lengthSeconds = 0.0f;
        ma_sound_get_length_in_seconds(current_, &lengthSeconds);
        emit("LOADED:" + std::to_string(lengthSeconds));
    }

    void pause() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!current_) {
            emitError("PAUSE with no sound loaded");
            return;
        }
        ma_sound_stop(current_);  // ma_sound_stop pauses; position is preserved
        paused_ = true;
        emit("EVENT:PAUSED");
    }

    void resume() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!current_) {
            emitError("RESUME with no sound loaded");
            return;
        }
        ma_result result = ma_sound_start(current_);
        if (result != MA_SUCCESS) {
            emitError("Failed to resume playback");
            return;
        }
        paused_ = false;
        emit("EVENT:RESUMED");
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!current_) {
            // Not an error - STOP on an already-empty engine is a no-op.
            return;
        }
        unloadInternal();
        emit("EVENT:STOPPED");
    }

    // SEEK:<seconds>
    void seek(double seconds) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!current_) {
            emitError("SEEK with no sound loaded");
            return;
        }
        if (seconds < 0) seconds = 0;

        ma_uint32 sampleRate = 0;
        ma_sound_get_data_format(current_, nullptr, nullptr, &sampleRate,
                                  nullptr, 0);
        if (sampleRate == 0) sampleRate = ma_engine_get_sample_rate(&engine_);

        ma_uint64 frame =
            static_cast<ma_uint64>(seconds * static_cast<double>(sampleRate));
        ma_result result = ma_sound_seek_to_pcm_frame(current_, frame);
        if (result != MA_SUCCESS) {
            emitError("Seek failed");
        }
    }

    // VOLUME:<0.0-1.0>
    void setVolume(float volume) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (volume < 0.0f) volume = 0.0f;
        if (volume > 1.0f) volume = 1.0f;
        // Volume can be set on the engine's endpoint even with nothing
        // loaded yet, so it applies to whatever plays next.
        ma_engine_set_volume(&engine_, volume);
        if (current_) {
            ma_sound_set_volume(current_, volume);
        }
    }

    // Called periodically from the reporter thread. Emits POS: while a
    // sound is loaded, and detects natural end-of-track exactly once per
    // load, emitting EVENT:FINISHED.
    void tick() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!current_) return;

        ma_uint64 cursorFrames = 0;
        ma_sound_get_cursor_in_pcm_frames(current_, &cursorFrames);
        ma_uint32 sampleRate = 0;
        ma_sound_get_data_format(current_, nullptr, nullptr, &sampleRate,
                                  nullptr, 0);
        if (sampleRate == 0) sampleRate = ma_engine_get_sample_rate(&engine_);

        double positionSeconds =
            sampleRate > 0
                ? static_cast<double>(cursorFrames) / static_cast<double>(sampleRate)
                : 0.0;
        emit("POS:" + std::to_string(positionSeconds));

        if (!paused_ && !finishedReported_ && ma_sound_at_end(current_)) {
            finishedReported_ = true;
            emit("EVENT:FINISHED");
        }
    }

private:
    // Caller must hold mutex_.
    void unloadInternal() {
        if (current_) {
            ma_sound_uninit(current_);
            delete current_;
            current_ = nullptr;
        }
        paused_ = false;
        finishedReported_ = false;
    }

    void unloadLocked() {
        std::lock_guard<std::mutex> lock(mutex_);
        unloadInternal();
    }

    static void emit(const std::string& line) {
        std::cout << line << "\n";
        // std::cout is set to std::unitbuf in main(), so this flushes
        // automatically. Kept explicit here in case that ever changes.
        std::cout.flush();
    }

    static void emitError(const std::string& message) {
        emit("ERROR:" + message);
    }

    ma_engine engine_{};
    bool initialized_ = false;

    std::mutex mutex_;
    ma_sound* current_ = nullptr;
    bool paused_ = false;
    bool finishedReported_ = false;
};

// ---------------------------------------------------------------------
// Command loop (main thread) + reporter loop (background thread).
// ---------------------------------------------------------------------

int main() {
    // Auto-flush stdout after every '\n'-terminated write. This is not
    // optional: Java's reader thread blocks on readLine(), and if stdout
    // were fully buffered (the default when stdout is a pipe rather than
    // a terminal), status updates would sit in the C++ process's buffer
    // and never reach Java until the buffer filled or the process exited.
    std::cout << std::unitbuf;
    std::ios::sync_with_stdio(false);

    AudioEngine engine;
    if (!engine.init()) {
        std::cout << "ERROR:Failed to initialize audio device\n";
        std::cout.flush();
        return 1;
    }
    std::cout << "READY\n";
    std::cout.flush();

    std::atomic<bool> running{true};

    // Reporter thread: periodically asks the engine for its current
    // position and prints POS:/EVENT:FINISHED. Runs independently of the
    // command thread so playback status keeps flowing even if Java is
    // slow to send the next command.
    std::thread reporter([&engine, &running]() {
        while (running.load()) {
            engine.tick();
            std::this_thread::sleep_for(kPositionReportInterval);
        }
    });

    // Command thread (main): blocks on std::getline waiting for the next
    // line from Java. This is deliberately simple — one command per line,
    // processed synchronously. Playback itself is non-blocking (miniaudio
    // runs its own device callback thread), so a slow PLAY doesn't stall
    // command intake for long, and SEEK/VOLUME/PAUSE are effectively
    // instantaneous.
    std::string line;
    while (running.load() && std::getline(std::cin, line)) {
        line = trim(line);
        if (line.empty()) continue;

        auto [cmd, arg] = splitCommand(line);

        if (cmd == "PLAY") {
            if (arg.empty()) {
                std::cout << "ERROR:PLAY requires a path\n";
                std::cout.flush();
            } else {
                engine.play(arg);
            }
        } else if (cmd == "PAUSE") {
            engine.pause();
        } else if (cmd == "RESUME") {
            engine.resume();
        } else if (cmd == "STOP") {
            engine.stop();
        } else if (cmd == "SEEK") {
            try {
                engine.seek(std::stod(arg));
            } catch (const std::exception&) {
                std::cout << "ERROR:Invalid SEEK value: " << arg << "\n";
                std::cout.flush();
            }
        } else if (cmd == "VOLUME") {
            try {
                engine.setVolume(std::stof(arg));
            } catch (const std::exception&) {
                std::cout << "ERROR:Invalid VOLUME value: " << arg << "\n";
                std::cout.flush();
            }
        } else if (cmd == "QUIT") {
            running.store(false);
            break;
        } else {
            std::cout << "ERROR:Unknown command: " << cmd << "\n";
            std::cout.flush();
        }
    }

    // If stdin closed unexpectedly (e.g. Java process was killed), treat
    // it the same as QUIT rather than spinning forever.
    running.store(false);
    reporter.join();
    return 0;
}