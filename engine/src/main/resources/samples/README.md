# Bundled sample bank (`fluid_lofi.dsb`)

Sampled instruments for the pure-Kotlin engine, extracted from **FluidR3_GM.sf2** (Fluid Release 3 General MIDI
SoundFont, copyright Frank Wen 2000-2002, 2008, released under the MIT license; see `LICENSE-FluidR3.txt`).

- Source archive: `https://deb.debian.org/debian/pool/main/f/fluid-soundfont/fluid-soundfont_3.1.orig.tar.gz`
  (`fluid-soundfont-3.1/FluidR3_GM.sf2`, sha256 of the tarball
  `2621acaa1c78e4abdb24bdd163230cc577e61276936d6aa6e3180582142f0343`).
- Generator: `scripts/sf2_extract.py <FluidR3_GM.sf2>` (Python 3 + numpy; nothing else). It rewrites this directory's
  `fluid_lofi.dsb` and the `fluid_lofi.json` manifest (instruments, zones, sizes, source presets, envelope).
- Format: documented in `engine/src/main/kotlin/io/drivesymphony/engine/dsp/SampleBank.kt` (little-endian, version
  byte, 16-bit mono PCM per zone). Loaded at runtime with `SampleBank.loadDefault()`; played by `Sampler` /
  `DrumSampler` in `engine/src/main/kotlin/io/drivesymphony/engine/dsp/Sampler.kt`.

What was kept (total 4.8 MB of the 10 MB budget):

| instrument | GM source (bank:program) | keys | zones | notes |
|------------|--------------------------|------|-------|-------|
| `ep`       | 0:4 Rhodes EP            | 36-96 | 11 | 22.05 kHz native, stereo pairs averaged to mono, loops kept |
| `bass`     | 0:32 Acoustic Bass       | 28-60 | 6 | resampled 44.1 -> 32 kHz (no energy above 12 kHz) |
| `fbass`    | 0:33 Fingered Bass       | 28-60 | 6 | 22.05 kHz native |
| `vibes`    | 0:11 Vibraphone          | 55-96 | 9 | 44.1 kHz native |
| `strings`  | 0:48 Strings             | 48-84 | 6 | 32 kHz native, roots 4 semitones apart, trimmed to loop end |
| `pad`      | 0:89 Warm Pad            | 48-84 | 1 | one looped sample, 44.1 kHz |
| `drums`    | 128:0 Standard           | 36 38 37 42 46 51 70 | 7 | one-shots <= 1.5 s, 44.1 kHz |
| `brush`    | 128:40 Brush             | 36 38 40 42 46 51 37 | 7 | one-shots <= 1.5 s, 44.1 kHz |

FluidR3 repeats the same sample across nine velocity bands and only varies the envelope, so each key range is stored
once (velocity shapes gain in the sampler). Looped samples are cut at the loop end plus a 64-sample margin, run
through a 5 Hz DC blocker (40 Hz for the pad, whose source drifts to +10 % DC) and re-centred on the loop mean; drum
hits are cut at 1.5 s with a 50 ms fade and their SF2 volume envelope baked in. Every instrument's zone gains are shifted
together so its loudest zone peaks at -3 dBFS at full velocity; the balance between zones (e.g. hats vs kick inside a
kit) is the SoundFont's own.
