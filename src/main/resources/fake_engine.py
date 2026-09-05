# fake_engine.py - stands in for audio_engine in fast tests
import sys
print("READY", flush=True)
for line in sys.stdin:
    line = line.strip()
    if line.startswith("PLAY:"):
        print("LOADED:2.5", flush=True)
    elif line == "QUIT":
        break