// audio_engine.cpp (libVLC version)
//
// Standalone audio engine replacing miniaudio with libVLC. 
// Spawns as a headless child process and talks to Java over stdin/stdout.

#include <vlc/vlc.h>

#include <atomic>
#include <chrono>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>

namespace {

constexpr auto kPositionReportInterval = std::chrono::milliseconds(500);

std::string trim(const std::string& s) {
    size_t end = s.find_last_not_of(" \t\r\n");
    if (end == std::string::npos) return "";
    return s.substr(0, end + 1);
}

std::pair<std::string, std::string> splitCommand(const std::string& line) {
    auto pos = line.find(':');
    if (pos == std::string::npos) {
        return {line, ""};
    }
    return {line.substr(0, pos), line.substr(pos + 1)};
}

}  // namespace

class AudioEngine {
public:
    AudioEngine() = default;

    ~AudioEngine() {
        if (mp_) {
            libvlc_media_player_stop_async(mp_);
            libvlc_media_player_release(mp_);
        }
        if (vlc_) {
            libvlc_release(vlc_);
        }
    }

    bool init() {
        // --quiet is mandatory. If libvlc prints decode warnings to stdout, 
        // the Java ProcessBuilder will crash trying to parse them as engine commands.
        const char* const vlc_args[] = {
            "--no-video",
            "--quiet",
            "--no-osd"
        };
        
        vlc_ = libvlc_new(sizeof(vlc_args) / sizeof(vlc_args[0]), vlc_args);
        if (!vlc_) return false;

        mp_ = libvlc_media_player_new(vlc_);
        if (!mp_) return false;

        return true;
    }

    void play(const std::string& path) {
        std::lock_guard<std::mutex> lock(mutex_);
        
        libvlc_media_player_stop_async(mp_);
        
        libvlc_media_t* media = libvlc_media_new_path(vlc_, path.c_str());
        if (!media) {
            emitError("Failed to load file: " + path);
            return;
        }

        // libvlc handles the media memory natively once attached to the player
        libvlc_media_player_set_media(mp_, media);
        libvlc_media_release(media);

        if (libvlc_media_player_play(mp_) != 0) {
            emitError("Failed to start playback: " + path);
            return;
        }

        loadedReported_ = false;
        finishedReported_ = false;
        paused_ = false;
        
        // Note: We do NOT emit "LOADED:" here. VLC loads asynchronously.
        // We defer it to tick() when libvlc_media_player_get_length > 0.
    }

    void pause() {
        std::lock_guard<std::mutex> lock(mutex_);
        libvlc_media_player_set_pause(mp_, 1);
        paused_ = true;
        emit("EVENT:PAUSED");
    }

    void resume() {
        std::lock_guard<std::mutex> lock(mutex_);
        libvlc_media_player_set_pause(mp_, 0);
        paused_ = false;
        emit("EVENT:RESUMED");
    }

    void stop() {
        std::lock_guard<std::mutex> lock(mutex_);
        libvlc_media_player_stop_async(mp_);
        loadedReported_ = false;
        emit("EVENT:STOPPED");
    }

    void seek(double seconds) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (seconds < 0) seconds = 0;
        libvlc_media_player_set_time(mp_, static_cast<libvlc_time_t>(seconds * 1000.0));
    }

    void setVolume(float volume) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (volume < 0.0f) volume = 0.0f;
        if (volume > 1.0f) volume = 1.0f;
        // libVLC volume ranges from 0 to 100
        libvlc_audio_set_volume(mp_, static_cast<int>(volume * 100));
    }

    void tick() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!mp_) return;

        libvlc_state_t state = libvlc_media_player_get_state(mp_);

        if (state == libvlc_Playing || state == libvlc_Paused) {
            // Defer LOADED command until length is successfully parsed
            if (!loadedReported_) {
                libvlc_time_t length = libvlc_media_player_get_length(mp_);
                if (length > 0) {
                    emit("LOADED:" + std::to_string(length / 1000.0));
                    loadedReported_ = true;
                }
            }

            // Continuous POS updates
            libvlc_time_t pos = libvlc_media_player_get_time(mp_);
            if (pos >= 0) {
                emit("POS:" + std::to_string(pos / 1000.0));
            }
        } 
        else if (state == libvlc_Ended && !finishedReported_) {
            finishedReported_ = true;
            emit("EVENT:FINISHED");
        }
    }

private:
    static void emit(const std::string& line) {
        std::cout << line << "\n";
        std::cout.flush();
    }

    static void emitError(const std::string& message) {
        emit("ERROR:" + message);
    }

    libvlc_instance_t* vlc_ = nullptr;
    libvlc_media_player_t* mp_ = nullptr;
    
    std::mutex mutex_;
    bool loadedReported_ = false;
    bool finishedReported_ = false;
    bool paused_ = false;
};


int main() {
    std::cout << std::unitbuf;
    std::ios::sync_with_stdio(false);

    AudioEngine engine;
    if (!engine.init()) {
        std::cout << "ERROR:Failed to initialize libvlc\n";
        std::cout.flush();
        return 1;
    }
    
    std::cout << "READY\n";
    std::cout.flush();

    std::atomic<bool> running{true};

    std::thread reporter([&engine, &running]() {
        while (running.load()) {
            engine.tick();
            std::this_thread::sleep_for(kPositionReportInterval);
        }
    });

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
            } catch (...) {
                std::cout << "ERROR:Invalid SEEK value\n";
                std::cout.flush();
            }
        } else if (cmd == "VOLUME") {
            try {
                engine.setVolume(std::stof(arg));
            } catch (...) {
                std::cout << "ERROR:Invalid VOLUME value\n";
                std::cout.flush();
            }
        } else if (cmd == "QUIT") {
            running.store(false);
            break;
        }
    }

    running.store(false);
    reporter.join();
    return 0;
}