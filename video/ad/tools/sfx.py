"""Synthesises the film's one sound effect: the turn-signal relay click (public/gen/tick.wav, 48 kHz mono, 60 ms).

    python -I sfx.py <public dir>

A short damped two-partial click with a touch of noise, peak −6 dBFS; the Film plays it at −24 dB on every blink phase
while a 3D scene shows the signal on. Deterministic (seeded), so renders are reproducible.
"""
import os, struct, sys, wave
import numpy as np


def tick(sr=48000):
    n = int(0.06 * sr)
    t = np.arange(n) / sr
    env = np.exp(-t / 0.011) * (1 - np.exp(-t / 0.0005))
    rng = np.random.default_rng(3)
    x = 0.6 * np.sin(2 * np.pi * 2400 * t) + 0.3 * np.sin(2 * np.pi * 1150 * t) + 0.25 * rng.normal(0, 1, n) * np.exp(-t / 0.0035)
    x = x * env
    x = x / (np.max(np.abs(x)) + 1e-9) * 0.5
    return x


def main():
    pub = os.path.abspath(sys.argv[1]); gen = os.path.join(pub, "gen"); os.makedirs(gen, exist_ok=True)
    x = tick()
    with wave.open(os.path.join(gen, "tick.wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(48000)
        w.writeframes(struct.pack(f"<{len(x)}h", *np.clip(x * 32767, -32768, 32767).astype(np.int16)))
    print(f"wrote {os.path.join(gen, 'tick.wav')} ({len(x) / 48000 * 1000:.0f} ms)")


if __name__ == "__main__":
    main()
