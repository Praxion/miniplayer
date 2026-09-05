#!/usr/bin/env python3
"""
test_audio_engine.py - black-box regression tests for audio_engine.

This does NOT unit-test C++ internals. It spawns the real compiled binary,
talks to it exactly the way Java will (write a line to stdin, read lines
from stdout), and asserts on the protocol described at the top of
audio_engine.cpp. That's deliberate: the bugs that matter here (a race
between the reporter thread and the command thread, FINISHED firing
twice, stdout buffering silently hanging Java) only show up when the
process is actually running, not from reading the source.

Usage:
    python3 test_audio_engine.py                  # uses ./audio_engine
    python3 test_audio_engine.py /path/to/engine   # explicit binary path

Exit code is 0 if every test passed, 1 otherwise - safe to wire into CI
or a pre-commit hook so a change that breaks playback fails loudly
instead of quietly shipping.

No external dependencies. Test audio (WAV, so no MP3 codec dependency) is
generated on the fly with the stdlib `wave` module.
"""

import math
import os
import queue
import struct
import subprocess
import sys
import tempfile
import threading
import time
import wave

ENGINE_PATH = sys.argv[1] if len(sys.argv) > 1 else "./audio_engine"


# ---------------------------------------------------------------------
# Test fixtures: short synthetic WAV files so tests don't depend on any
# particular music file existing on disk.
# ---------------------------------------------------------------------

def make_tone(path, duration_seconds, freq=440, sample_rate=44100):
    """Writes a mono 16-bit PCM sine wave WAV file."""
    n_frames = int(duration_seconds * sample_rate)
    with wave.open(path, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        frames = bytearray()
        for i in range(n_frames):
            val = int(3000 * math.sin(2 * math.pi * freq * i / sample_rate))
            frames += struct.pack("<h", val)
        w.writeframes(bytes(frames))


# ---------------------------------------------------------------------
# Harness: wraps the child process, gives tests a simple send()/expect()
# API instead of raw pipe plumbing. Two background reader threads drain
# stdout and stderr continuously so neither pipe's OS buffer can fill up
# and deadlock the child - the same hazard that bit the ALSA-noise case
# during manual testing.
# ---------------------------------------------------------------------

class EngineProcess:
    def __init__(self, engine_path):
        self.proc = subprocess.Popen(
            [engine_path],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        self.stdout_q = queue.Queue()
        self.stderr_lines = []

        self._stdout_thread = threading.Thread(
            target=self._pump, args=(self.proc.stdout, self.stdout_q), daemon=True
        )
        self._stderr_thread = threading.Thread(
            target=self._pump, args=(self.proc.stderr, self.stderr_lines), daemon=True
        )
        self._stdout_thread.start()
        self._stderr_thread.start()

    @staticmethod
    def _pump(stream, sink):
        for line in stream:
            line = line.rstrip("\n")
            if isinstance(sink, queue.Queue):
                sink.put(line)
            else:
                sink.append(line)
        stream.close()

    def send(self, command):
        self.proc.stdin.write(command + "\n")
        self.proc.stdin.flush()

    def expect(self, predicate, timeout=3.0, description=None):
        """
        Reads lines from stdout until one satisfies `predicate`, or raises
        AssertionError on timeout. Returns the matching line. Any
        non-matching lines seen along the way are returned too, since
        tests sometimes need to inspect e.g. all POS: lines before a
        FINISHED.
        """
        deadline = time.monotonic() + timeout
        seen = []
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            try:
                line = self.stdout_q.get(timeout=max(0.01, remaining))
            except queue.Empty:
                break
            seen.append(line)
            if predicate(line):
                return line, seen
        raise AssertionError(
            f"Timed out waiting for: {description or predicate}\n"
            f"Lines seen instead: {seen}"
        )

    def drain(self, duration=0.5):
        """Collects whatever arrives on stdout over a fixed window."""
        deadline = time.monotonic() + duration
        collected = []
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            try:
                collected.append(self.stdout_q.get(timeout=max(0.01, remaining)))
            except queue.Empty:
                break
        return collected

    def close(self):
        try:
            if self.proc.poll() is None:
                self.send("QUIT")
                self.proc.wait(timeout=3.0)
        except Exception:
            self.proc.kill()
            self.proc.wait(timeout=2.0)


def start_engine():
    eng = EngineProcess(ENGINE_PATH)
    eng.expect(lambda l: l == "READY", timeout=3.0, description="startup READY")
    return eng


def pos_value(line):
    """Parses 'POS:1.234000' -> 1.234. Returns None if not a POS line."""
    if not line.startswith("POS:"):
        return None
    try:
        return float(line.split(":", 1)[1])
    except ValueError:
        return None


# ---------------------------------------------------------------------
# Tests. Each is a plain function taking no arguments; failures raise
# AssertionError with a message explaining what went wrong.
# ---------------------------------------------------------------------

def test_startup_ready():
    eng = start_engine()
    eng.close()


def test_play_reports_loaded_and_position(tone_short):
    eng = start_engine()
    eng.send(f"PLAY:{tone_short}")
    line, _ = eng.expect(lambda l: l.startswith("LOADED:"), description="LOADED:")
    duration = float(line.split(":", 1)[1])
    assert 0.9 < duration < 1.1, f"expected ~1s tone, got LOADED:{duration}"

    line, _ = eng.expect(lambda l: pos_value(l) is not None, description="a POS: line")
    eng.close()


def test_position_increases_while_playing(tone_medium):
    eng = start_engine()
    eng.send(f"PLAY:{tone_medium}")
    eng.expect(lambda l: l.startswith("LOADED:"))

    positions = []
    lines = eng.drain(duration=1.5)
    positions = [pos_value(l) for l in lines if pos_value(l) is not None]
    assert len(positions) >= 2, f"expected multiple POS updates, got {positions}"
    assert positions == sorted(positions), (
        f"position should be non-decreasing while playing, got {positions}"
    )
    assert positions[-1] > positions[0], "position did not advance at all"
    eng.close()


def test_pause_halts_position_progress(tone_medium):
    eng = start_engine()
    eng.send(f"PLAY:{tone_medium}")
    eng.expect(lambda l: l.startswith("LOADED:"))
    eng.drain(duration=0.4)  # let it play briefly

    eng.send("PAUSE")
    eng.expect(lambda l: l == "EVENT:PAUSED", description="EVENT:PAUSED")

    lines_after_pause = eng.drain(duration=0.8)
    positions = [pos_value(l) for l in lines_after_pause if pos_value(l) is not None]
    if positions:
        spread = max(positions) - min(positions)
        assert spread < 0.05, (
            f"position moved by {spread:.3f}s while paused, expected ~0: {positions}"
        )
    eng.close()


def test_resume_continues_from_pause(tone_medium):
    eng = start_engine()
    eng.send(f"PLAY:{tone_medium}")
    eng.expect(lambda l: l.startswith("LOADED:"))
    eng.drain(duration=0.3)

    eng.send("PAUSE")
    eng.expect(lambda l: l == "EVENT:PAUSED")
    paused_lines = eng.drain(duration=0.3)
    paused_positions = [pos_value(l) for l in paused_lines if pos_value(l) is not None]
    last_paused_pos = paused_positions[-1] if paused_positions else 0.0

    eng.send("RESUME")
    eng.expect(lambda l: l == "EVENT:RESUMED", description="EVENT:RESUMED")

    resumed_lines = eng.drain(duration=0.8)
    resumed_positions = [pos_value(l) for l in resumed_lines if pos_value(l) is not None]
    assert resumed_positions, "no POS: updates after RESUME"
    assert resumed_positions[-1] > last_paused_pos, (
        "position did not advance after RESUME"
    )
    eng.close()


def test_seek_jumps_position(tone_medium):
    eng = start_engine()
    eng.send(f"PLAY:{tone_medium}")
    eng.expect(lambda l: l.startswith("LOADED:"))
    eng.drain(duration=0.2)

    eng.send("SEEK:4.0")
    line, _ = eng.expect(
        lambda l: pos_value(l) is not None and pos_value(l) > 3.5,
        timeout=2.0,
        description="a POS: line reflecting the seek target (~4.0s)",
    )
    eng.close()


def test_stop_clears_loaded_sound(tone_medium):
    eng = start_engine()
    eng.send(f"PLAY:{tone_medium}")
    eng.expect(lambda l: l.startswith("LOADED:"))
    eng.send("STOP")
    eng.expect(lambda l: l == "EVENT:STOPPED", description="EVENT:STOPPED")

    # After STOP, engine has nothing loaded - PAUSE should error, not crash.
    eng.send("PAUSE")
    eng.expect(
        lambda l: l.startswith("ERROR:") and "no sound loaded" in l,
        description="ERROR: ... no sound loaded",
    )
    eng.close()


def test_finished_event_fires_exactly_once(tone_short):
    eng = start_engine()
    eng.send(f"PLAY:{tone_short}")
    eng.expect(lambda l: l.startswith("LOADED:"))

    # tone_short is ~1s; wait long enough to run past the end plus a
    # few extra report ticks, then count FINISHED occurrences.
    lines = eng.drain(duration=2.0)
    finished_count = sum(1 for l in lines if l == "EVENT:FINISHED")
    assert finished_count == 1, (
        f"expected exactly one EVENT:FINISHED, got {finished_count}: {lines}"
    )
    eng.close()


def test_missing_file_errors_but_engine_survives(tone_short):
    eng = start_engine()
    eng.send("PLAY:/definitely/does/not/exist_xyz.mp3")
    eng.expect(lambda l: l.startswith("ERROR:"), description="ERROR: for missing file")

    # Engine must still be alive and accept a subsequent valid PLAY -
    # a failed load should never leave it wedged.
    eng.send(f"PLAY:{tone_short}")
    eng.expect(lambda l: l.startswith("LOADED:"), description="LOADED: after recovering")
    eng.close()


def test_invalid_seek_value_reports_error():
    eng = start_engine()
    eng.send("SEEK:not_a_number")
    eng.expect(
        lambda l: l.startswith("ERROR:") and "Invalid SEEK" in l,
        description="ERROR: Invalid SEEK value",
    )
    eng.close()


def test_invalid_volume_value_reports_error():
    eng = start_engine()
    eng.send("VOLUME:loud_please")
    eng.expect(
        lambda l: l.startswith("ERROR:") and "Invalid VOLUME" in l,
        description="ERROR: Invalid VOLUME value",
    )
    eng.close()


def test_unknown_command_reports_error_without_crashing():
    eng = start_engine()
    eng.send("MAKE_SANDWICH")
    eng.expect(
        lambda l: l.startswith("ERROR:") and "Unknown command" in l,
        description="ERROR: Unknown command",
    )
    # Engine should still be responsive afterward.
    eng.send("STOP")
    eng.close()
    assert eng.proc.poll() == 0, "engine did not exit cleanly after QUIT"


def test_out_of_range_volume_does_not_crash(tone_short):
    eng = start_engine()
    eng.send(f"PLAY:{tone_short}")
    eng.expect(lambda l: l.startswith("LOADED:"))
    eng.send("VOLUME:5.0")   # above 1.0, should clamp silently, not error
    eng.send("VOLUME:-2.0")  # below 0.0, should clamp silently, not error
    time.sleep(0.2)
    assert eng.proc.poll() is None, "engine crashed on out-of-range VOLUME"
    eng.close()


def test_stdout_never_contains_raw_stderr_noise(tone_short):
    """
    Guards the stdout/stderr separation the protocol depends on: every
    line Java reads from stdout must start with a recognized token.
    Regresses if someone later routes a debug print or an audio-backend
    log line to std::cout instead of std::cerr.
    """
    eng = start_engine()
    eng.send(f"PLAY:{tone_short}")
    lines = eng.drain(duration=1.5)
    allowed_prefixes = ("READY", "LOADED:", "POS:", "EVENT:", "ERROR:")
    for line in lines:
        assert line.startswith(allowed_prefixes), (
            f"unexpected line on stdout (protocol violation): {line!r}"
        )
    eng.close()


def test_quit_exits_promptly():
    eng = start_engine()
    eng.send("QUIT")
    start = time.monotonic()
    code = eng.proc.wait(timeout=3.0)
    elapsed = time.monotonic() - start
    assert code == 0, f"expected exit code 0, got {code}"
    assert elapsed < 1.0, f"QUIT took {elapsed:.2f}s to exit, expected near-instant"


def test_rapid_command_fire_does_not_crash(tone_short, tone_medium):
    """Stress test: hammer the engine with commands back-to-back with no
    delay, the way a flaky UI or a double-click might."""
    eng = start_engine()
    for _ in range(20):
        eng.send(f"PLAY:{tone_short}")
        eng.send("PAUSE")
        eng.send("RESUME")
        eng.send("SEEK:0.1")
        eng.send("STOP")
    eng.send(f"PLAY:{tone_medium}")
    eng.expect(lambda l: l.startswith("LOADED:"), timeout=3.0)
    assert eng.proc.poll() is None, "engine crashed under rapid command fire"
    eng.close()


# ---------------------------------------------------------------------
# Runner
# ---------------------------------------------------------------------

def main():
    if not os.path.exists(ENGINE_PATH):
        print(f"Engine binary not found at '{ENGINE_PATH}'. "
              f"Build it first, or pass the path as an argument.")
        sys.exit(1)

    tmpdir = tempfile.mkdtemp(prefix="audio_engine_tests_")
    tone_short = os.path.join(tmpdir, "short.wav")   # ~1s, for FINISHED tests
    tone_medium = os.path.join(tmpdir, "medium.wav")  # ~6s, for pause/seek/etc
    make_tone(tone_short, duration_seconds=1.0)
    make_tone(tone_medium, duration_seconds=6.0)

    # (test function, args) - args are fixture file paths where needed.
    tests = [
        (test_startup_ready, ()),
        (test_play_reports_loaded_and_position, (tone_short,)),
        (test_position_increases_while_playing, (tone_medium,)),
        (test_pause_halts_position_progress, (tone_medium,)),
        (test_resume_continues_from_pause, (tone_medium,)),
        (test_seek_jumps_position, (tone_medium,)),
        (test_stop_clears_loaded_sound, (tone_medium,)),
        (test_finished_event_fires_exactly_once, (tone_short,)),
        (test_missing_file_errors_but_engine_survives, (tone_short,)),
        (test_invalid_seek_value_reports_error, ()),
        (test_invalid_volume_value_reports_error, ()),
        (test_unknown_command_reports_error_without_crashing, ()),
        (test_out_of_range_volume_does_not_crash, (tone_short,)),
        (test_stdout_never_contains_raw_stderr_noise, (tone_short,)),
        (test_quit_exits_promptly, ()),
        (test_rapid_command_fire_does_not_crash, (tone_short, tone_medium)),
    ]

    passed, failed = 0, []
    for fn, args in tests:
        name = fn.__name__
        try:
            fn(*args)
            print(f"  PASS  {name}")
            passed += 1
        except AssertionError as e:
            print(f"  FAIL  {name}\n        {e}")
            failed.append(name)
        except Exception as e:
            print(f"  ERROR {name}\n        {type(e).__name__}: {e}")
            failed.append(name)

    print()
    print(f"{passed}/{len(tests)} passed")
    if failed:
        print("Failed: " + ", ".join(failed))
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
