"""CPU share of the app on the Pleos emulator / car, per thread, from /proc (no profiler needed).

  python -I scripts/cpu-share.py --adb <adb.exe> [--pkg io.drivesymphony.app] [--seconds 30]

Reads utime+stime of every thread of the process twice, `seconds` apart, and prints each thread's share of ONE
core over the window plus the process total and VmRSS. CLK_TCK is assumed 100 (Android kernels). This is the
measure the contest's "CPU < 5 % while driving" criterion refers to, so the smoke script stores it as cpu-share.txt.
"""
import argparse
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8")


def sh(adb, cmd):
    return subprocess.run([adb, "shell", cmd], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def snapshot(adb, pid):
    out = sh(adb, f"for t in /proc/{pid}/task/*; do echo \"$(cat $t/stat)\"; done")
    res = {}
    for line in out.splitlines():
        if ") " not in line:
            continue
        head, tail = line.rsplit(") ", 1)
        tid = head.split(" ", 1)[0]
        comm = head.split("(", 1)[1]
        f = tail.split()
        res[tid] = (comm, int(f[11]) + int(f[12]))   # utime + stime in clock ticks
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--adb", default="adb")
    ap.add_argument("--pkg", default="io.drivesymphony.app")
    ap.add_argument("--seconds", type=int, default=30)
    a = ap.parse_args()
    pid = sh(a.adb, f"pidof {a.pkg}").strip()
    if not pid:
        print("process not running:", a.pkg, file=sys.stderr)
        return 2
    s0 = snapshot(a.adb, pid)
    t0 = time.time()
    time.sleep(a.seconds)
    s1 = snapshot(a.adb, pid)
    dt = time.time() - t0
    rows = []
    for tid, (comm, ticks1) in s1.items():
        ticks0 = s0.get(tid, (comm, 0))[1]
        rows.append((comm, tid, (ticks1 - ticks0) / 100.0 / dt * 100.0))
    rows.sort(key=lambda r: -r[2])
    total = sum(r[2] for r in rows)
    rss = [l for l in sh(a.adb, f"cat /proc/{pid}/status").splitlines() if l.startswith("VmRSS")]
    print(f"pid {pid}  window {dt:.1f} s  (% of one core)")
    for comm, tid, pct in rows:
        if pct >= 0.05:
            print(f"  {pct:5.1f}%  {comm} ({tid})")
    print(f"  {total:5.1f}%  TOTAL process")
    print(" ", rss[0].strip() if rss else "VmRSS n/a")
    return 0


if __name__ == "__main__":
    sys.exit(main())
