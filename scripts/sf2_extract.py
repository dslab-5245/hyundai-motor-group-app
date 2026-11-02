#!/usr/bin/env python3
"""
sf2_extract.py - self-contained SoundFont 2 reader that extracts a compact sample bank for the Drive Symphony engine.

Reads FluidR3_GM.sf2 (MIT, Frank Wen) and writes
    engine/src/main/resources/samples/fluid_lofi.dsb    (binary bank, format documented in SampleBank.kt)
    engine/src/main/resources/samples/fluid_lofi.json   (manifest: instruments, zones, sizes, provenance)

Usage
    python -I scripts/sf2_extract.py <FluidR3_GM.sf2> [--out engine/src/main/resources/samples] [--budget-mb 10]
    python -I scripts/sf2_extract.py <FluidR3_GM.sf2> --inspect 0:4 128:0     # dump preset zones, no output written

Only the standard library plus numpy are used. The SF2 parser honours: RIFF INFO / sdta.smpl / pdta (phdr, pbag,
pmod, pgen, inst, ibag, igen, shdr); generators keyRange 43, velRange 44, overridingRootKey 58, coarseTune 51,
fineTune 52, initialAttenuation 48, sampleModes 54, pan 17, exclusiveClass 57, the volume envelope 33-38, sample
offset generators 0-4/12/45/50, sampleID 53 and instrument 41; global zones; preset-level generator values are
added to instrument-level values (ranges are intersected), as the SF2 2.04 spec prescribes.
"""
import argparse
import collections
import json
import math
import os
import struct
import sys
import time

import numpy as np

# ----------------------------------------------------------------------------------------------------------------
# SF2 parsing
# ----------------------------------------------------------------------------------------------------------------

GEN_START_OFS, GEN_END_OFS, GEN_STARTLOOP_OFS, GEN_ENDLOOP_OFS, GEN_START_COARSE = 0, 1, 2, 3, 4
GEN_END_COARSE, GEN_PAN, GEN_DELAY_VOL, GEN_ATTACK_VOL, GEN_HOLD_VOL, GEN_DECAY_VOL = 12, 17, 33, 34, 35, 36
GEN_SUSTAIN_VOL, GEN_RELEASE_VOL, GEN_INSTRUMENT, GEN_KEYRANGE, GEN_VELRANGE = 37, 38, 41, 43, 44
GEN_STARTLOOP_COARSE, GEN_KEYNUM, GEN_VELOCITY, GEN_ATTEN, GEN_ENDLOOP_COARSE = 45, 46, 47, 48, 50
GEN_COARSE_TUNE, GEN_FINE_TUNE, GEN_SAMPLE_ID, GEN_SAMPLE_MODES, GEN_SCALE_TUNING = 51, 52, 53, 54, 56
GEN_EXCLUSIVE_CLASS, GEN_ROOT_KEY = 57, 58
RANGE_GENS = (GEN_KEYRANGE, GEN_VELRANGE)

# instrument-level defaults (SF2 2.04 section 8.1.3); every other generator defaults to 0
GEN_DEFAULTS = {
    GEN_DELAY_VOL: -12000, GEN_ATTACK_VOL: -12000, GEN_HOLD_VOL: -12000, GEN_DECAY_VOL: -12000,
    GEN_SUSTAIN_VOL: 0, GEN_RELEASE_VOL: -12000, GEN_KEYNUM: -1, GEN_VELOCITY: -1, GEN_ROOT_KEY: -1,
    GEN_SCALE_TUNING: 100,
}

SAMPLE_MONO, SAMPLE_RIGHT, SAMPLE_LEFT, SAMPLE_LINKED = 1, 2, 4, 8


def _cstr(b):
    return b.split(b"\0", 1)[0].decode("latin-1").strip()


class Sample:
    __slots__ = ("index", "name", "start", "end", "loop_start", "loop_end", "rate", "pitch", "correction", "link", "type")

    def __init__(self, index, rec):
        self.index = index
        (name, self.start, self.end, self.loop_start, self.loop_end, self.rate,
         self.pitch, self.correction, self.link, self.type) = struct.unpack("<20sIIIIIBbHH", rec)
        self.name = _cstr(name)


class Zone:
    """A preset or instrument zone: generator dict plus the terminating sample/instrument reference."""

    def __init__(self, gens, mods):
        self.gens = gens
        self.mods = mods
        self.key_lo, self.key_hi = gens.get(GEN_KEYRANGE, (0, 127))
        self.vel_lo, self.vel_hi = gens.get(GEN_VELRANGE, (0, 127))
        self.sample_id = gens.get(GEN_SAMPLE_ID)
        self.instrument = gens.get(GEN_INSTRUMENT)


class SoundFont:
    def __init__(self, path):
        self.path = path
        self.info = {}
        with open(path, "rb") as f:
            data = f.read()
        self.data = data
        if data[:4] != b"RIFF" or data[8:12] != b"sfbk":
            raise ValueError("not a SoundFont 2 file")
        pos, end = 12, 8 + struct.unpack_from("<I", data, 4)[0]
        self.smpl = None
        chunks = {}
        while pos + 8 <= end:
            cid, size = struct.unpack_from("<4sI", data, pos)
            body = pos + 8
            if cid == b"LIST":
                ltype = data[body:body + 4]
                sub = body + 4
                lend = body + size
                while sub + 8 <= lend:
                    sid, ssize = struct.unpack_from("<4sI", data, sub)
                    chunks[(ltype, sid)] = (sub + 8, ssize)
                    sub += 8 + ssize + (ssize & 1)
            pos = body + size + (size & 1)
        for (lt, sid), (off, size) in chunks.items():
            if lt == b"INFO":
                raw = data[off:off + size]
                if sid == b"ifil":
                    self.info["version"] = "%d.%d" % struct.unpack("<HH", raw)
                else:
                    self.info[sid.decode("latin-1")] = _cstr(raw)
        off, size = chunks[(b"sdta", b"smpl")]
        self.smpl = np.frombuffer(data, dtype="<i2", count=size // 2, offset=off)
        if (b"sdta", b"sm24") in chunks:
            self.info["has_sm24"] = True

        def records(key, fmt_size):
            off, size = chunks[(b"pdta", key)]
            return [data[off + i * fmt_size: off + (i + 1) * fmt_size] for i in range(size // fmt_size)]

        self.samples = [Sample(i, r) for i, r in enumerate(records(b"shdr", 46))]
        phdr = [struct.unpack("<20sHHHIII", r) for r in records(b"phdr", 38)]
        pbag = [struct.unpack("<HH", r) for r in records(b"pbag", 4)]
        pgen = [self._gen(r) for r in records(b"pgen", 4)]
        pmod = [struct.unpack("<HHhHH", r) for r in records(b"pmod", 10)]
        inst = [struct.unpack("<20sH", r) for r in records(b"inst", 22)]
        ibag = [struct.unpack("<HH", r) for r in records(b"ibag", 4)]
        igen = [self._gen(r) for r in records(b"igen", 4)]
        imod = [struct.unpack("<HHhHH", r) for r in records(b"imod", 10)]

        self.instruments = []
        for i in range(len(inst) - 1):   # last record is the EOI terminator
            name, bag_start = inst[i]
            bag_end = inst[i + 1][1]
            zones = self._zones(ibag, igen, imod, bag_start, bag_end, GEN_SAMPLE_ID)
            self.instruments.append((_cstr(name), zones))
        self.presets = {}
        for i in range(len(phdr) - 1):   # last record is the EOP terminator
            name, program, bank, bag_start, _, _, _ = phdr[i]
            bag_end = phdr[i + 1][3]
            zones = self._zones(pbag, pgen, pmod, bag_start, bag_end, GEN_INSTRUMENT)
            self.presets[(bank, program)] = (_cstr(name), zones)

    @staticmethod
    def _gen(rec):
        op, lo, hi = struct.unpack("<HBB", rec)
        if op in RANGE_GENS:
            return op, (lo, hi)
        return op, struct.unpack("<h", rec[2:4])[0]

    @staticmethod
    def _zones(bags, gens, mods, bag_start, bag_end, terminator):
        """Returns (global_zone_or_None, [zones]). A first bag without the terminator generator is the global zone."""
        out = []
        glob = None
        for b in range(bag_start, bag_end):
            g0, m0 = bags[b]
            g1, m1 = bags[b + 1]
            zg = {}
            for op, val in gens[g0:g1]:
                zg[op] = val
            z = Zone(zg, mods[m0:m1])
            if terminator not in zg:
                if b == bag_start:
                    glob = z
                continue   # a zone without a sample/instrument reference that is not first is ignored (spec)
            out.append(z)
        return glob, out

    def sample_pcm(self, s):
        return self.smpl[s.start:s.end]


class RenderZone:
    """One playable region after preset and instrument generators are combined."""
    __slots__ = ("key_lo", "key_hi", "vel_lo", "vel_hi", "root", "fine_cents", "atten_cb", "pan", "modes",
                 "env", "sample", "start", "end", "loop_start", "loop_end", "excl", "inst_name", "gens")

    def __init__(self, sf, inst_name, pz, ipz_gens, iz, ig_gens):
        # ig_gens: merged instrument-level gens (global + zone), ipz_gens: merged preset-level gens (global + zone)
        self.inst_name = inst_name
        g = collections.defaultdict(int, GEN_DEFAULTS)
        g.update(ig_gens)
        for op, v in ipz_gens.items():
            if op in RANGE_GENS or op in (GEN_INSTRUMENT, GEN_SAMPLE_ID):
                continue
            g[op] = g.get(op, 0) + v       # preset values are relative: added to the instrument values
        self.gens = g
        klo, khi = ig_gens.get(GEN_KEYRANGE, (0, 127))
        pklo, pkhi = ipz_gens.get(GEN_KEYRANGE, (0, 127))
        vlo, vhi = ig_gens.get(GEN_VELRANGE, (0, 127))
        pvlo, pvhi = ipz_gens.get(GEN_VELRANGE, (0, 127))
        self.key_lo, self.key_hi = max(klo, pklo), min(khi, pkhi)
        self.vel_lo, self.vel_hi = max(vlo, pvlo), min(vhi, pvhi)
        s = sf.samples[ig_gens[GEN_SAMPLE_ID]]
        self.sample = s
        root = g[GEN_ROOT_KEY]
        self.root = root if 0 <= root <= 127 else s.pitch
        if self.root > 127:   # 255 = "unpitched" per spec
            self.root = 60
        self.fine_cents = g[GEN_COARSE_TUNE] * 100 + g[GEN_FINE_TUNE] + s.correction
        self.atten_cb = g[GEN_ATTEN]
        self.pan = g[GEN_PAN]
        self.modes = g[GEN_SAMPLE_MODES] & 3
        self.excl = g[GEN_EXCLUSIVE_CLASS]
        self.start = s.start + g.get(GEN_START_OFS, 0) + 32768 * g.get(GEN_START_COARSE, 0)
        self.end = s.end + g.get(GEN_END_OFS, 0) + 32768 * g.get(GEN_END_COARSE, 0)
        self.loop_start = s.loop_start + g.get(GEN_STARTLOOP_OFS, 0) + 32768 * g.get(GEN_STARTLOOP_COARSE, 0)
        self.loop_end = s.loop_end + g.get(GEN_ENDLOOP_OFS, 0) + 32768 * g.get(GEN_ENDLOOP_COARSE, 0)
        tc = lambda v: 2.0 ** (v / 1200.0)   # timecents -> seconds
        sus_cb = max(0, min(1440, g[GEN_SUSTAIN_VOL]))
        self.env = {
            "delay": tc(g[GEN_DELAY_VOL]), "attack": tc(g[GEN_ATTACK_VOL]), "hold": tc(g[GEN_HOLD_VOL]),
            "decay": tc(g[GEN_DECAY_VOL]), "sustain": 0.0 if sus_cb >= 1000 else 10 ** (-sus_cb / 200.0),
            "release": tc(g[GEN_RELEASE_VOL]),
        }
        for k in ("delay", "attack", "hold", "decay", "release"):
            if self.env[k] < 0.001:
                self.env[k] = 0.0 if k in ("delay", "hold") else 0.001

    @property
    def looped(self):
        return self.modes in (1, 3) and self.loop_end > self.loop_start

    def describe(self):
        s = self.sample
        return ("key %3d-%3d vel %3d-%3d root %3d fine %+4d att %4d cB pan %+4d modes %d loop %d-%d/%d rate %d %s "
                "env a=%.3f h=%.3f d=%.2f s=%.3f r=%.2f  <%s>" % (
                    self.key_lo, self.key_hi, self.vel_lo, self.vel_hi, self.root, self.fine_cents, self.atten_cb, self.pan,
                    self.modes, self.loop_start - s.start, self.loop_end - s.start, self.end - self.start, s.rate,
                    {1: "mono", 2: "R", 4: "L", 8: "link"}.get(s.type & 0xF, str(s.type)),
                    self.env["attack"], self.env["hold"], self.env["decay"], self.env["sustain"], self.env["release"], s.name))


def render_zones(sf, bank, program):
    """All playable zones of a preset with preset+instrument generators merged."""
    if (bank, program) not in sf.presets:
        return None, []
    pname, (pglob, pzones) = sf.presets[(bank, program)]
    out = []
    for pz in pzones:
        pg = dict(pglob.gens) if pglob else {}
        pg.update(pz.gens)
        iname, (iglob, izones) = sf.instruments[pz.instrument]
        for iz in izones:
            ig = dict(iglob.gens) if iglob else {}
            ig.update(iz.gens)
            rz = RenderZone(sf, iname, pz, pg, iz, ig)
            if rz.key_lo > rz.key_hi or rz.vel_lo > rz.vel_hi:
                continue
            out.append(rz)
    return pname, out


# ----------------------------------------------------------------------------------------------------------------
# Signal helpers (numpy only)
# ----------------------------------------------------------------------------------------------------------------

def energy_above(pcm, rate, hz):
    """Fraction of the sample's energy above `hz` (Hann-windowed FFT)."""
    x = pcm.astype(np.float64)
    if len(x) < 64:
        return 0.0
    x = x * np.hanning(len(x))
    spec = np.abs(np.fft.rfft(x)) ** 2
    freqs = np.fft.rfftfreq(len(x), 1.0 / rate)
    total = spec.sum()
    return float(spec[freqs >= hz].sum() / total) if total > 0 else 0.0


def resample(x, ratio, taps=32, beta=8.0):
    """Windowed-sinc (Kaiser) resampling by `ratio` (out_len = round(len * ratio)); cutoff just below the lower Nyquist."""
    n = len(x)
    m = int(round(n * ratio))
    cutoff = 0.92 * min(1.0, ratio)          # relative to the input Nyquist
    pos = np.arange(m) / ratio
    base = np.floor(pos).astype(np.int64)
    frac = pos - base
    half = taps // 2
    pad = np.concatenate([np.zeros(half + 1), x.astype(np.float64), np.zeros(half + 2)])
    y = np.zeros(m)
    wsum = np.zeros(m)
    for t in range(-half + 1, half + 1):
        d = t - frac                      # distance from the output position to input sample base+t
        win = np.i0(beta * np.sqrt(np.clip(1 - (d / half) ** 2, 0, 1))) / np.i0(beta)
        w = np.sinc(cutoff * d) * cutoff * win
        y += pad[base + t + half + 1] * w
        wsum += w
    # normalise the tap sum so DC and low frequencies keep unity gain regardless of the fractional position
    y /= np.where(wsum == 0, 1, wsum)
    return y


def to_i16(x):
    return np.clip(np.round(x), -32768, 32767).astype("<i2")


def dc_block(x, rate, hz=5.0):
    """First-order DC blocker y[n] = x[n] - x[n-1] + a*y[n-1] (a = exp(-2*pi*hz/rate)), vectorised block-wise with
    exact state carry. -0.06 dB at 41 Hz (the lowest bass note), so it only removes offset and sub-audio drift."""
    a = math.exp(-2.0 * math.pi * hz / rate)
    d = np.diff(x, prepend=x[0])
    y = np.empty_like(d)
    block = 4096
    idx = np.arange(block)
    pw, ipw = a ** idx, a ** (-idx)
    state = 0.0
    for s in range(0, len(d), block):
        m = min(block, len(d) - s)
        y[s:s + m] = np.cumsum(d[s:s + m] * ipw[:m]) * pw[:m] + state * a * pw[:m]
        state = y[s + m - 1]
    return y


# ----------------------------------------------------------------------------------------------------------------
# Instrument extraction plan
# ----------------------------------------------------------------------------------------------------------------

MELODIC = [
    # name, candidates (bank, program), key range, budget share, root spacing, velocity layers
    dict(name="ep", presets=[(0, 4)], keys=(36, 96), share=0.44, spacing=3, layers=2),
    dict(name="bass", presets=[(0, 32)], keys=(28, 60), share=0.11, spacing=3, layers=2),
    dict(name="fbass", presets=[(0, 33)], keys=(28, 60), share=0.11, spacing=3, layers=2),
    dict(name="vibes", presets=[(0, 11)], keys=(55, 96), share=0.10, spacing=3, layers=2),
    # only when budget remains: string ensemble (long looped samples, roots 4 semitones apart) and the one-sample warm pad
    dict(name="strings", presets=[(0, 48)], keys=(48, 84), share=0.15, spacing=4, layers=1, optional=True),
    # the pad is one C6 sample played up to three octaves down, so everything under 40 Hz (rumble that would land
    # at 5 Hz) is removed at the source
    dict(name="pad", presets=[(0, 89)], keys=(48, 84), share=0.02, spacing=3, layers=1, optional=True, hp=40.0),
]
DRUMS = [
    dict(name="drums", presets=[(128, 0)], notes=[36, 38, 37, 42, 46, 51, 70], share=0.08),
    dict(name="brush", presets=[(128, 40), (128, 32)], notes=[36, 38, 40, 42, 46, 51, 37], share=0.08),
]
DRUM_MAX_SEC = 1.5
LOOP_MARGIN = 64          # samples kept after the loop end (interpolation taps + safety)
FADE_SAMPLES = 24         # tiny ramp at the very end of trimmed one-shots
HF_THRESHOLD = 1e-4       # energy fraction above 12 kHz under which an instrument is stored at 32 kHz


def velocity_layers(zones_for_key, max_layers):
    """Groups a key range's zones into velocity layers. FluidR3 mostly repeats the same sample over 9 velocity bands
    (only the envelope differs), so bands that reference the same sample(s) are merged into one layer; genuinely
    different samples are kept, at most `max_layers`, spread from the softest to the loudest."""
    bands = {}
    for z in zones_for_key:
        bands.setdefault((z.vel_lo, z.vel_hi), []).append(z)   # a band holds the L/R pair when the sample is stereo
    merged = []
    for (vlo, vhi), zs in sorted(bands.items()):
        ident = frozenset(z.sample.index for z in zs)
        if merged and merged[-1][3] == ident:
            merged[-1][1] = vhi
        else:
            merged.append([vlo, vhi, zs, ident])
    if len(merged) > max_layers:
        idx = sorted(set(np.linspace(0, len(merged) - 1, max_layers).round().astype(int)))
        merged = [merged[i] for i in idx]
    out = []
    for j, (vlo, vhi, zs, ident) in enumerate(merged):
        # envelope representative: the source band that contains velocity 96 (mezzo-forte), else the loudest band
        same = [z for z in zones_for_key if z.sample.index in ident]
        env_z = next((z for z in same if z.vel_lo <= 96 <= z.vel_hi), max(same, key=lambda z: z.vel_hi))
        out.append((0 if j == 0 else vlo, 127 if j == len(merged) - 1 else vhi, zs, env_z))
    return out


def pick_key_zones(zones, key_lo, key_hi, spacing, layers):
    """Thin a preset's zones: one entry per source key range, roots at least `spacing` semitones apart,
    <= `layers` velocity layers, and the kept zones stretched so they tile key_lo..key_hi contiguously."""
    by_key = {}
    for z in zones:
        if z.key_hi < key_lo or z.key_lo > key_hi:
            continue
        by_key.setdefault((z.key_lo, z.key_hi), []).append(z)
    ranges = sorted(by_key.keys(), key=lambda k: (by_key[k][0].root, k))
    kept = []
    for (klo, khi) in ranges:
        zs = by_key[(klo, khi)]
        root = zs[0].root
        if kept and root - kept[-1][0] < spacing:
            continue
        kept.append((root, velocity_layers(zs, layers)))
    result = []
    for i, (root, vlayers) in enumerate(kept):
        lo = key_lo if i == 0 else (kept[i - 1][0] + root) // 2 + 1
        hi = key_hi if i == len(kept) - 1 else (root + kept[i + 1][0]) // 2
        lo, hi = max(lo, key_lo), min(hi, key_hi)
        if lo > hi:
            continue
        result.append((lo, hi, vlayers))
    return result


def mono_pcm(sf, zs):
    """Mono PCM for a zone group: average of the stereo pair when both halves match, else the first zone."""
    z = zs[0]
    pcm = sf.smpl[z.start:z.end].astype(np.float64)
    pair = [o for o in zs[1:] if o.end - o.start == z.end - z.start and o.sample.index != z.sample.index]
    if pair:
        o = pair[0]
        pcm = (pcm + sf.smpl[o.start:o.end].astype(np.float64)) * 0.5
        return pcm, z, True
    return pcm, z, False


def trim_zone(pcm, z, one_shot_max=None, hp=5.0):
    """Trim: looped -> loop end + margin; one-shot -> up to one_shot_max seconds with a short fade. Returns (pcm, loop_start, loop_end).
    Looped samples also lose their DC offset (the FluidR3 "Alien Strings" pad drifts from 0 to +10 % DC over its first
    second): a 5 Hz DC blocker removes the drift, then the loop-region mean is subtracted so the loop stays seamless."""
    s0 = z.start
    if z.looped and not one_shot_max:
        ls, le = z.loop_start - s0, z.loop_end - s0
        if 0 <= ls < le <= len(pcm):
            end = min(len(pcm), le + LOOP_MARGIN)
            out = dc_block(pcm[:end], z.sample.rate, hp)
            out -= out[ls:le].mean()
            return out, ls, le
    # one shot
    out = pcm
    if one_shot_max:
        limit = int(one_shot_max * z.sample.rate)
        if len(out) > limit:
            out = out[:limit].copy()
            fade = min(int(0.05 * z.sample.rate), len(out))
            out[-fade:] *= np.linspace(1, 0, fade)
    if len(out) > FADE_SAMPLES:
        out = out.copy()
        out[-FADE_SAMPLES:] *= np.linspace(1, 0, FADE_SAMPLES)
    return out, -1, -1


def bake_envelope(pcm, env, rate):
    """Multiplies a one-shot by its SF2 volume envelope (hold then 96 dB/decay-seconds exponential decay)."""
    n = len(pcm)
    t = np.arange(n) / rate
    g = np.ones(n)
    if env["attack"] > 0.001:
        g *= np.clip(t / env["attack"], 0, 1)
    start = env["hold"]
    dec = env["decay"]
    if dec < 50:   # 50 s or more: effectively no decay
        k = (96.0 / 20.0) * math.log(10) / dec
        g *= np.where(t > start, np.exp(-k * np.maximum(t - start, 0)), 1.0)
        g = np.maximum(g, env["sustain"] * (t > start) + (t <= start) * g) if env["sustain"] > 0 else g
    return pcm * g


def instrument_env(zones):
    """Instrument-level envelope: median of the zone envelopes (they are usually identical within a preset)."""
    med = lambda k: float(np.median([z.env[k] for z in zones]))
    return dict(attack=med("attack"), hold=med("hold"), decay=med("decay"), sustain=med("sustain"), release=med("release"))


def build_melodic(sf, spec, budget_bytes, log):
    for bank, program in spec["presets"]:
        pname, zones = render_zones(sf, bank, program)
        if zones:
            break
    else:
        log("  %s: no preset found" % spec["name"])
        return None
    klo, khi = spec["keys"]
    kept = pick_key_zones(zones, klo, khi, spec["spacing"], spec["layers"])
    # measure the high-frequency content over all kept samples: downsample the whole instrument to 32 kHz when nothing lives above 12 kHz
    hf = []
    items = []
    for lo, hi, vlayers in kept:
        for vlo, vhi, zs, env_z in vlayers:
            pcm, z, averaged = mono_pcm(sf, zs)
            pcm, ls, le = trim_zone(pcm, z, hp=spec.get("hp", 5.0))
            hf.append(energy_above(pcm, z.sample.rate, 12000))
            items.append(dict(lo=lo, hi=hi, vlo=vlo, vhi=vhi, z=z, env_z=env_z, pcm=pcm, ls=ls, le=le, averaged=averaged))
    max_hf = max(hf) if hf else 0.0
    # "no energy above 12 kHz": less than -40 dB of the total; then 32 kHz loses nothing audible and saves 27 %
    downsample = max_hf < HF_THRESHOLD and all(it["z"].sample.rate > 32000 for it in items)
    out_zones = []
    for it in items:
        z = it["z"]
        pcm, ls, le = it["pcm"], it["ls"], it["le"]
        rate = z.sample.rate
        fine = z.fine_cents
        if downsample:
            ratio = 32000.0 / rate
            if le > 0:
                # make the loop length an integer after resampling; fold the residual pitch error into fineCents
                loop_len = le - ls
                new_len = max(1, int(round(loop_len * ratio)))
                ratio = new_len / loop_len
                new_ls = int(round(ls * ratio))
                new_le = new_ls + new_len
                pcm = resample(pcm, ratio)
                ls, le = new_ls, new_le
                # the PCM is now `rate * ratio` samples per source second but is declared as 32 kHz: a tone of f Hz
                # would play at f * 32000 / (rate * ratio), so raise the pitch by the inverse (well under a cent)
                fine += 1200.0 * math.log2(rate * ratio / 32000.0)
            else:
                pcm = resample(pcm, ratio)
            rate = 32000
        out_zones.append(dict(
            lowKey=it["lo"], highKey=it["hi"], lowVel=it["vlo"], highVel=it["vhi"], rootKey=z.root,
            fineCents=int(round(fine)), sampleRate=rate, loopStart=ls, loopEnd=le, gainDb=-z.atten_cb / 10.0,
            pcm=to_i16(pcm), source=z.sample.name, sourceRate=z.sample.rate, averagedStereo=it["averaged"],
            pan=z.pan, hfEnergy=energy_above(pcm, rate, 12000)))
    env = instrument_env([it["env_z"] for it in items])
    total = sum(len(z["pcm"]) * 2 for z in out_zones)
    log("  %-7s %-24s zones %2d  %6.2f MB  hf>12k max %.1e  %s  env a=%.3f h=%.3f d=%.2f s=%.3f r=%.2f" % (
        spec["name"], pname, len(out_zones), total / 1e6, max_hf, "32k" if downsample else "native",
        env["attack"], env["hold"], env["decay"], env["sustain"], env["release"]))
    return dict(name=spec["name"], preset=dict(bank=bank, program=program, name=pname), zones=out_zones, env=env,
                downsampled=downsample, bytes=total)


def build_drums(sf, spec, log):
    for bank, program in spec["presets"]:
        pname, zones = render_zones(sf, bank, program)
        if zones:
            break
    else:
        log("  %s: no preset found" % spec["name"])
        return None
    out_zones = []
    for note in spec["notes"]:
        cands = [z for z in zones if z.key_lo <= note <= z.key_hi]
        if not cands:
            log("  %s: note %d missing in %s" % (spec["name"], note, pname))
            continue
        # velocity layers: identical samples collapse into one layer; genuinely different samples keep up to 2
        layers = velocity_layers(cands, 2)
        for j, (vlo, vhi, zs, _) in enumerate(layers):
            # FluidR3 shortens soft hits through the envelope, so bake the loudest band's envelope (the full hit)
            loud = [z for z in cands if z.sample.index == zs[0].sample.index]
            z_env = max(loud, key=lambda z: z.vel_hi)
            pcm, z, averaged = mono_pcm(sf, zs)
            pcm, _, _ = trim_zone(pcm, z, one_shot_max=DRUM_MAX_SEC)
            pcm = bake_envelope(pcm, z_env.env, z.sample.rate)
            # drum zones play at their root; a zone spanning several keys keeps its pitch offset via fineCents
            fine = z.fine_cents + (note - z.root) * 100
            out_zones.append(dict(
                lowKey=note, highKey=note, lowVel=vlo, highVel=vhi,
                rootKey=note, fineCents=int(round(fine)), sampleRate=z.sample.rate, loopStart=-1, loopEnd=-1,
                gainDb=-z.atten_cb / 10.0, pcm=to_i16(pcm), source=z.sample.name, sourceRate=z.sample.rate,
                averagedStereo=averaged, pan=z.pan, excl=z.excl, hfEnergy=energy_above(pcm, z.sample.rate, 12000)))
    env = dict(attack=0.001, hold=0.0, decay=100.0, sustain=1.0, release=0.05)   # envelope is baked into the PCM
    total = sum(len(z["pcm"]) * 2 for z in out_zones)
    log("  %-7s %-24s zones %2d  %6.2f MB  notes %s" % (spec["name"], pname, len(out_zones), total / 1e6,
                                                        sorted(set(z["lowKey"] for z in out_zones))))
    return dict(name=spec["name"], preset=dict(bank=bank, program=program, name=pname), zones=out_zones, env=env,
                downsampled=False, bytes=total)


def normalise(inst, target_db=-3.0):
    """Shifts every zone's gainDb by the same amount so the loudest zone (at full velocity) peaks at target_db."""
    peak_db = max(20 * math.log10(max(1, int(np.abs(z["pcm"].astype(np.int32)).max())) / 32768.0) + z["gainDb"] for z in inst["zones"])
    shift = target_db - peak_db
    for z in inst["zones"]:
        z["gainDb"] = round(z["gainDb"] + shift, 2)
    inst["normaliseShiftDb"] = round(shift, 2)


def shrink_to_budget(inst, max_bytes, log):
    """Drops the softer velocity layer, then the zones in the middle of the keyboard, until the instrument fits."""
    before = inst["bytes"]
    while inst["bytes"] > max_bytes:
        zones = inst["zones"]
        layered = [z for z in zones if z["highVel"] < 127]
        if layered:
            victim = layered[0]
            partner = next(z for z in zones if z["lowKey"] == victim["lowKey"] and z is not victim)
            partner["lowVel"] = 0
            zones.remove(victim)
        elif len(zones) > 2:
            # remove the zone with the shortest gap to its neighbours (its neighbours stretch to cover it)
            zones.sort(key=lambda z: z["lowKey"])
            i = len(zones) // 2
            victim = zones.pop(i)
            mid = (victim["lowKey"] + victim["highKey"]) // 2
            zones[i - 1]["highKey"] = mid
            zones[i]["lowKey"] = mid + 1
        else:
            break
        inst["bytes"] = sum(len(z["pcm"]) * 2 for z in zones)
    if inst["bytes"] != before:
        log("  %-7s trimmed to %.2f MB, %d zones" % (inst["name"], inst["bytes"] / 1e6, len(inst["zones"])))


# ----------------------------------------------------------------------------------------------------------------
# Bank writer (see SampleBank.kt for the format)
# ----------------------------------------------------------------------------------------------------------------

def write_bank(path, instruments):
    with open(path, "wb") as f:
        f.write(b"DSBK" + struct.pack("<BBH", 1, len(instruments), 0))
        for inst in instruments:
            name = inst["name"].encode("utf-8")
            env = inst["env"]
            f.write(struct.pack("<B", len(name)) + name)
            f.write(struct.pack("<fffffH", env["attack"], env["hold"], env["decay"], env["sustain"], env["release"], len(inst["zones"])))
            for z in inst["zones"]:
                f.write(struct.pack("<BBBBBBhiiifi", z["lowKey"], z["highKey"], z["lowVel"], z["highVel"], z["rootKey"], 0,
                                    z["fineCents"], z["sampleRate"], z["loopStart"], z["loopEnd"], z["gainDb"], len(z["pcm"])))
                f.write(z["pcm"].astype("<i2").tobytes())


def manifest_entry(inst):
    return dict(
        name=inst["name"], source=inst["preset"], bytes=inst["bytes"], zoneCount=len(inst["zones"]),
        downsampledTo32k=inst["downsampled"], normaliseShiftDb=inst.get("normaliseShiftDb", 0.0),
        envelope={k: round(v, 4) for k, v in inst["env"].items()},
        zones=[dict(key=[z["lowKey"], z["highKey"]], vel=[z["lowVel"], z["highVel"]], root=z["rootKey"], fineCents=z["fineCents"],
                    rate=z["sampleRate"], frames=int(len(z["pcm"])), loop=[z["loopStart"], z["loopEnd"]] if z["loopEnd"] > 0 else None,
                    gainDb=z["gainDb"], sample=z["source"], sourceRate=z["sourceRate"], stereoAveraged=bool(z["averagedStereo"]),
                    sourcePan=z["pan"], hfEnergyAbove12k=float("%.2e" % z["hfEnergy"])) for z in inst["zones"]],
    )


# ----------------------------------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("sf2")
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                                                  "engine", "src", "main", "resources", "samples"))
    ap.add_argument("--budget-mb", type=float, default=10.0)
    ap.add_argument("--inspect", nargs="*", help="bank:program pairs to dump (no bank written)")
    args = ap.parse_args()

    t0 = time.time()
    sf = SoundFont(args.sf2)
    log = lambda s: print(s, flush=True)
    log("SoundFont %s: %s (%d samples, %d instruments, %d presets, %.1f MB PCM) parsed in %.1fs" % (
        os.path.basename(args.sf2), sf.info.get("INAM"), len(sf.samples), len(sf.instruments), len(sf.presets),
        sf.smpl.nbytes / 1e6, time.time() - t0))

    if args.inspect is not None:
        for spec in args.inspect:
            bank, program = (int(v) for v in spec.split(":"))
            pname, zones = render_zones(sf, bank, program)
            log("preset %d:%d %s  (%d zones)" % (bank, program, pname, len(zones)))
            for z in sorted(zones, key=lambda z: (z.key_lo, z.vel_lo)):
                log("   " + z.describe())
        return

    budget = int(args.budget_mb * 1e6)
    instruments = []
    log("extracting (budget %.1f MB):" % (budget / 1e6))
    for spec in MELODIC:
        if spec.get("optional"):
            continue
        inst = build_melodic(sf, spec, budget, log)
        if inst:
            shrink_to_budget(inst, int(budget * spec["share"]), log)
            instruments.append(inst)
    for spec in DRUMS:
        inst = build_drums(sf, spec, log)
        if inst:
            shrink_to_budget(inst, int(budget * spec["share"]), log)
            instruments.append(inst)
    used = sum(i["bytes"] for i in instruments)
    for spec in MELODIC:
        if not spec.get("optional"):
            continue
        inst = build_melodic(sf, spec, budget, log)
        if inst:
            remaining = budget - used - 4096 - 64 * sum(len(i["zones"]) for i in instruments)
            shrink_to_budget(inst, min(int(budget * spec["share"]), remaining), log)
            if inst["bytes"] <= remaining and len(inst["zones"]) >= 1:
                instruments.append(inst)
                used += inst["bytes"]
            else:
                log("  %s dropped: no budget left" % spec["name"])
    for inst in instruments:
        normalise(inst)

    os.makedirs(args.out, exist_ok=True)
    bank_path = os.path.join(args.out, "fluid_lofi.dsb")
    write_bank(bank_path, instruments)
    size = os.path.getsize(bank_path)
    manifest = dict(
        format="DSBK v1 (see engine/src/main/kotlin/io/drivesymphony/engine/dsp/SampleBank.kt)",
        source=dict(file=os.path.basename(args.sf2), name=sf.info.get("INAM"), version=sf.info.get("version"),
                    copyright=sf.info.get("ICOP"), engineer=sf.info.get("IENG"), comment=sf.info.get("ICMT")),
        license="MIT (Frank Wen, 2000-2002, 2008); see LICENSE-FluidR3.txt",
        generator="scripts/sf2_extract.py",
        budgetBytes=budget, totalBytes=size,
        instruments=[manifest_entry(i) for i in instruments],
    )
    with open(os.path.join(args.out, "fluid_lofi.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1)
    log("wrote %s (%.2f MB of %.1f MB budget) and manifest in %.1fs" % (bank_path, size / 1e6, budget / 1e6, time.time() - t0))
    if size > budget:
        log("ERROR: bank exceeds the budget")
        sys.exit(1)


if __name__ == "__main__":
    main()
