#!/usr/bin/env python3
"""Synthesizes the app's own sounds into app/src/main/res/raw/*.ogg (needs ffmpeg with libvorbis).

Usage: python3 tools/make_sounds.py
"""
import math
import os
import struct
import subprocess
import tempfile
import wave

RATE = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


def silence(seconds):
    return [0.0] * int(RATE * seconds)


def mix(track, at, sound):
    """Adds [sound] into [track] from [at] seconds, growing it as needed"""
    start = int(at * RATE)
    if len(track) < start + len(sound):
        track.extend([0.0] * (start + len(sound) - len(track)))
    for i, v in enumerate(sound):
        track[start + i] += v
    return track


def bell(freq, seconds, decay=3.0):
    """A struck bell: inharmonic partials, the high ones die first"""
    partials = [(1.0, 1.0), (2.0, 0.5), (2.76, 0.35), (5.4, 0.15), (8.93, 0.06)]
    out = []
    for i in range(int(RATE * seconds)):
        t = i / RATE
        attack = min(1.0, t / 0.004)
        v = sum(a * math.exp(-decay * k ** 0.5 * t) * math.sin(2 * math.pi * freq * k * t) for k, a in partials)
        out.append(attack * v)
    return out


def tone(freq, seconds, harmonics=(1.0,), attack=0.01, release=0.05, vibrato=0.0):
    """A held note with soft edges; [harmonics] are the amplitudes of 1x, 2x, 3x..."""
    out = []
    n = int(RATE * seconds)
    phase = 0.0
    for i in range(n):
        t = i / RATE
        f = freq * (1 + vibrato * math.sin(2 * math.pi * 5 * t))
        phase += 2 * math.pi * f / RATE
        env = min(1.0, t / attack, (seconds - t) / release)
        out.append(env * sum(a * math.sin(phase * (k + 1)) for k, a in enumerate(harmonics)))
    return out


def sweep(f0, f1, seconds, harmonics=(1.0, 0.3, 0.15)):
    out = []
    phase = 0.0
    for i in range(int(RATE * seconds)):
        t = i / RATE
        # Up and back down within the sweep, smoothly
        f = f0 + (f1 - f0) * (1 - math.cos(2 * math.pi * t / seconds)) / 2
        phase += 2 * math.pi * f / RATE
        env = min(1.0, t / 0.02, (seconds - t) / 0.02)
        out.append(env * sum(a * math.sin(phase * (k + 1)) for k, a in enumerate(harmonics)))
    return out


def chime():
    """Finish: two bell strikes, up a fourth"""
    t = mix(silence(0), 0.0, bell(659.25, 1.6))
    return mix(t, 0.28, bell(880.0, 2.0))


def arpeggio():
    """Finish: a warm major arpeggio, C E G C"""
    t = silence(0)
    for i, f in enumerate([523.25, 659.25, 783.99, 1046.5]):
        mix(t, i * 0.16, tone(f, 1.6 - i * 0.16, harmonics=(1.0, 0.25, 0.08), attack=0.01, release=1.0))
    return t


def ready():
    """Finish: three short soft beeps going up"""
    t = silence(0)
    for i, f in enumerate([784.0, 987.77, 1174.66]):
        mix(t, i * 0.18, tone(f, 0.14, harmonics=(1.0, 0.2), attack=0.005, release=0.06))
    return t


def alarm():
    """Error: fast two-tone beeping, 2 s"""
    t = silence(0)
    for i in range(8):
        f = 988.0 if i % 2 == 0 else 740.0
        mix(t, i * 0.25, tone(f, 0.2, harmonics=(1.0, 0.0, 0.33, 0.0, 0.2), attack=0.004, release=0.01))
    return t


def siren():
    """Error: two rising and falling sweeps"""
    t = silence(0)
    for i in range(2):
        mix(t, i * 1.1, sweep(600.0, 1300.0, 1.1))
    return t


def buzzer():
    """Error: three low descending buzzes"""
    t = silence(0)
    for i, f in enumerate([440.0, 370.0, 311.13]):
        mix(t, i * 0.45, tone(f, 0.38, harmonics=(1.0, 0.5, 0.33, 0.25, 0.2, 0.16), attack=0.01, release=0.03))
    return t


SOUNDS = {
    "finish_chime": chime,
    "finish_arpeggio": arpeggio,
    "finish_ready": ready,
    "error_alarm": alarm,
    "error_siren": siren,
    "error_buzzer": buzzer,
}


def write(name, samples):
    peak = max(abs(v) for v in samples) or 1.0
    tail = silence(0.05)
    data = b"".join(struct.pack("<h", int(v / peak * 0.89 * 32767)) for v in samples + tail)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        path = f.name
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data)
    out = os.path.join(OUT, name + ".ogg")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", path, "-c:a", "libvorbis", "-q:a", "4", out], check=True)
    os.remove(path)
    print(out, os.path.getsize(out))


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, make in SOUNDS.items():
        write(name, make())
