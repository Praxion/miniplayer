#!/usr/bin/env python3
"""
fake_engine.py - a lightweight stand-in for the real audio_engine binary,
used by EngineProcessFakeScriptTest / EngineProcessResilienceTest /
EngineProcessConcurrencyTest.

Speaks the SAME wire protocol as audio_engine.cpp for the commands it
implements (PLAY/PAUSE/RESUME/STOP/SEEK/QUIT), so these tests exercise
the real ProcessBuilder + reader-thread + writer code in EngineProcess -
just without needing real audio hardware, a compiled C++ binary, or a
real audio file.

It ALSO understands a few extra control commands that are NOT part of
the real protocol, used specifically to inject failure modes that are
hard to trigger on demand with a real engine:

    CRASH_NOW       exits immediately with code 7 (simulates a crash)
    HANG_ON_QUIT    starts ignoring QUIT (simulates a hung/misbehaving
                    child, to test EngineProcess.shutdown()'s force-kill
                    path)
    EMIT_GARBAGE    prints a line that isn't valid protocol output
                    (simulates a stray debug print or protocol bug)

Anything else it doesn't specifically recognize is echoed back as
"ECHO:<line>" so tests can assert on the exact text that reached the
process (e.g. confirming SEEK/VOLUME number formatting).
"""

import sys
import threading
import time

state_lock = threading.Lock()
state = {
    "loaded": False,
    "playing": False,
    "position": 0.0,
    "hang_on_quit": False,
}
running = True


def out(line):
    print(line, flush=True)


def reporter():
    # Mirrors the real engine's independent reporter thread: ticks on
    # its own regardless of whether new commands are arriving.
    while running:
        with state_lock:
            if state["loaded"] and state["playing"]:
                state["position"] += 0.25
                out(f"POS:{state['position']:.3f}")
        time.sleep(0.2)


def main():
    out("READY")
    t = threading.Thread(target=reporter, daemon=True)
    t.start()

    for raw in sys.stdin:
        line = raw.rstrip("\n")
        if not line:
            continue

        if line == "QUIT":
            with state_lock:
                hang = state["hang_on_quit"]
            if hang:
                continue
            break

        if line == "CRASH_NOW":
            sys.exit(7)

        if line == "HANG_ON_QUIT":
            with state_lock:
                state["hang_on_quit"] = True
            continue

        if line == "EMIT_GARBAGE":
            out("this is not a valid protocol line")
            continue

        if line.startswith("PLAY:"):
            with state_lock:
                state["loaded"] = True
                state["playing"] = True
                state["position"] = 0.0
            out("LOADED:3.000000")
            continue

        if line == "PAUSE":
            with state_lock:
                state["playing"] = False
            out("EVENT:PAUSED")
            continue

        if line == "RESUME":
            with state_lock:
                state["playing"] = True
            out("EVENT:RESUMED")
            continue

        if line == "STOP":
            with state_lock:
                state["loaded"] = False
                state["playing"] = False
                state["position"] = 0.0
            out("EVENT:STOPPED")
            continue

        if line.startswith("SEEK:"):
            try:
                value = float(line.split(":", 1)[1])
                with state_lock:
                    state["position"] = value
            except ValueError:
                pass
            out("ECHO:" + line)
            continue

        out("ECHO:" + line)

    sys.exit(0)


if __name__ == "__main__":
    main()