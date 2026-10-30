"""Objective checks on the rendered previews (python -I scripts/analyze_previews.py [previews_dir]).

Prints, per WAV: RMS / peak (dBFS), crest factor, L/R correlation (stereo image), brightness proxy
and the energy split across sub / low / mid / high bands, so a tuning pass can be judged by numbers,
not only by ear. Requires numpy.
"""
import glob, math, os, sys, wave
import numpy as np

def band_db(spec_power, freqs, lo, hi):
    m = (freqs >= lo) & (freqs < hi)
    return 10 * math.log10(float(spec_power[m].sum()) + 1e-12)

def analyze(path):
    with wave.open(path) as w:
        sr = w.getframerate(); n = w.getnframes(); raw = w.readframes(n); width = w.getsampwidth()
    a = np.frombuffer(raw, dtype='<i2' if width == 2 else '<i4').astype(np.float64) / (32768.0 if width == 2 else 2147483648.0)
    L, R = a[0::2], a[1::2]
    mono = (L + R) * 0.5
    rms = math.sqrt(float(np.mean(mono ** 2)))
    peak = float(max(np.max(np.abs(L)), np.max(np.abs(R))))
    corr = float(np.corrcoef(L, R)[0, 1])
    hf = float(np.sqrt(np.mean(np.diff(mono) ** 2))) / (rms + 1e-9)
    # average power spectrum over 1 s windows (Hann), mono
    win = sr
    hann = np.hanning(win)
    acc = None; count = 0
    for start in range(0, len(mono) - win, win):
        seg = mono[start:start + win] * hann
        p = np.abs(np.fft.rfft(seg)) ** 2
        acc = p if acc is None else acc + p
        count += 1
    spec = acc / max(count, 1)
    freqs = np.fft.rfftfreq(win, 1.0 / sr)
    total = 10 * math.log10(float(spec.sum()) + 1e-12)
    bands = {name: band_db(spec, freqs, lo, hi) - total for name, (lo, hi) in
             {"sub<80": (20, 80), "low": (80, 250), "mid": (250, 2000), "hi-mid": (2000, 6000), "air": (6000, 20000)}.items()}
    return dict(file=os.path.basename(path), rms_db=20 * math.log10(rms + 1e-9), peak_db=20 * math.log10(peak + 1e-9),
                crest=20 * math.log10(peak / (rms + 1e-9)), corr=corr, hf=hf, bands=bands)

def main():
    d = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "previews")
    rows = [analyze(f) for f in sorted(glob.glob(os.path.join(d, "*.wav")))]
    hdr = f"{'file':20s} {'rms':>6s} {'peak':>6s} {'crest':>6s} {'corr':>5s} {'hf':>6s} | " + " ".join(f"{b:>7s}" for b in rows[0]['bands']) if rows else "no wav files"
    print(hdr)
    for r in rows:
        print(f"{r['file']:20s} {r['rms_db']:6.1f} {r['peak_db']:6.1f} {r['crest']:6.1f} {r['corr']:5.2f} {r['hf']:6.3f} | " +
              " ".join(f"{v:7.1f}" for v in r['bands'].values()))
    print("\nbands = energy share in dB relative to total (0 dB = everything); corr = L/R correlation (1 = mono)")

if __name__ == "__main__":
    main()
