"""Drive the Pleos Connect emulator's Virtual Console over its REST API, without opening the browser UI.

The console (guest port 8088, package ai.pleos.connectwebconsole) only accepts writes while a browser session
heartbeat is alive (POST /browser/session every second), so this script keeps one running while it works.
Endpoints and field names come from GET /vehicle/catalog (see the comments next to each helper).

Usage (adb must be on PATH or passed with --adb; the script forwards host port 18088 -> guest 8088):
  python -I scripts/virtual-console.py state                      # GET /vehicle/state (gear, controls, body)
  python -I scripts/virtual-console.py set gear Drive
  python -I scripts/virtual-console.py set steering -30 | brake 60 | turn BlinkLeft | wiper Low | cruise longitudinal
  python -I scripts/virtual-console.py set seat rear_left true | belt On
  python -I scripts/virtual-console.py cue                        # the PT cue sheet (docs/pt-cue-sheet.md), ~85 s
"""
import argparse
import json
import math
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")
HOST_PORT = 18088
GUEST_PORT = 8088


class Console:
    def __init__(self, base, client="drive-symphony-script"):
        self.base = base
        self.client = client
        self._stop = threading.Event()
        self._thread = None
        self.t0 = time.time()

    def _req(self, method, path, params=None, timeout=5):
        url = self.base + path + ("?" + urllib.parse.urlencode(params) if params else "")
        req = urllib.request.Request(url, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")
        except Exception as e:  # connection refused etc.
            return 0, str(e)

    # --- browser-session heartbeat: the bridge reports "emulator_inactive" (503) without it
    def start(self):
        def loop():
            while not self._stop.is_set():
                self._req("POST", "/browser/session", {"client": self.client}, timeout=3)
                self._stop.wait(1.0)
        self._thread = threading.Thread(target=loop, daemon=True)
        self._thread.start()
        for _ in range(20):          # wait until the bridge is up
            st = self.state()
            if st.get("controls", {}).get("emulator_active"):
                return True
            time.sleep(0.5)
        return False

    def stop(self):
        self._stop.set()
        self._req("POST", "/browser/session", {"client": self.client, "closing": "1"}, timeout=3)

    def put(self, path, **params):
        code, body = self._req("PUT", path, params)
        t = time.time() - self.t0
        print(f"[{t:6.1f}s] PUT {path} {params} -> {code}{'' if code == 200 else ' ' + body[:80]}")
        return code

    def state(self):
        code, body = self._req("GET", "/vehicle/state")
        try:
            return json.loads(body) if code == 200 else {}
        except json.JSONDecodeError:
            return {}

    # --- signals (field names from /vehicle/catalog)
    def gear(self, g):            return self.put("/vehicle/gear", gear=g)                           # Park Reverse Neutral Drive
    def steering(self, deg):      return self.put("/vehicle/controls", steering=int(round(deg)))     # -470..500
    def brake(self, pct):         return self.put("/vehicle/controls", brake=int(pct))               # 0..100
    def turn(self, mode):         return self.put("/vehicle/turn-signal", mode=mode)                 # Off BlinkLeft BlinkRight
    def wiper(self, mode):        return self.put("/vehicle/body", front_wiper=mode)                 # Off Auto Low High
    def cruise(self, state):      return self.put("/vehicle/driving-mode", ad_mode_state=state)      # not_ready ready longitudinal ...
    def seat(self, area, occ):    return self.put("/vehicle/seat", kind="occupied", area=area, value="true" if occ else "false")
    def belt(self, state):        return self.put("/vehicle/telltales/simulator", color="Red", type="SeatBelt", state=state)  # Off On Blink


def steer_curve(c, seconds, fn, step=0.2):
    """Calls fn(t) -> degrees every `step` seconds for `seconds`."""
    n = max(1, int(seconds / step))
    for i in range(n):
        c.steering(fn(i * step))
        time.sleep(step)


def cue(c):
    """The live PT cue sheet (docs/pt-cue-sheet.md), compressed to ~85 s."""
    print("1. D, hands still")
    c.turn("Off"); c.wiper("Off"); c.cruise("not_ready"); c.brake(0); c.steering(0); c.gear("Drive")
    time.sleep(3)
    print("2. gentle steering +-30 deg (panning)")
    steer_curve(c, 8, lambda t: 30 * math.sin(t * 2 * math.pi / 4))
    c.steering(0)
    print("3. signaled turn: blinker left, then 160 deg for 2 s")
    c.turn("BlinkLeft"); time.sleep(1.5)
    steer_curve(c, 1.5, lambda t: 160 * t / 1.5); time.sleep(2.0)
    steer_curve(c, 1.5, lambda t: 160 * (1 - t / 1.5)); c.steering(0); c.turn("Off")
    print("4. 20 s steady -> layers open")
    steer_curve(c, 20, lambda t: 3 * math.sin(t))
    print("5. evasive swerve once (first harsh move after a clean streak -> grace)")
    c.steering(-220); time.sleep(0.3); c.brake(70); time.sleep(0.3); c.brake(0); c.steering(0)
    print("6. 12 s steady")
    steer_curve(c, 12, lambda t: 3 * math.sin(t))
    print("7. brake taps x3 within 3 s (habit -> music sinks)")
    for _ in range(3):
        c.brake(70); time.sleep(0.35); c.brake(0); time.sleep(0.6)
    print("8. 8 s steady -> recovery")
    steer_curve(c, 8, lambda t: 3 * math.sin(t))
    print("9. wiper on + cruise (rain texture, drone)")
    c.wiper("Low"); c.cruise("longitudinal")
    steer_curve(c, 10, lambda t: 3 * math.sin(t))
    print("10. rear seat occupied 5 s (feedback level drops to gentle), then empty")
    c.seat("rear_left", True); time.sleep(5); c.seat("rear_left", False)
    print("11. stop: brake, P -> journey card")
    c.cruise("not_ready"); c.wiper("Off"); c.brake(30); time.sleep(2); c.gear("Park"); c.brake(0)
    time.sleep(3)
    print("cue sheet done")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--adb", default="adb", help="adb executable (forwarding host 18088 -> guest 8088)")
    ap.add_argument("--no-forward", action="store_true")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("state")
    s = sub.add_parser("set"); s.add_argument("signal"); s.add_argument("value"); s.add_argument("extra", nargs="?")
    sub.add_parser("cue")
    a = ap.parse_args()

    if not a.no_forward:
        subprocess.run([a.adb, "forward", f"tcp:{HOST_PORT}", f"tcp:{GUEST_PORT}"], check=False, capture_output=True)
    c = Console(f"http://127.0.0.1:{HOST_PORT}")
    if a.cmd == "state":
        st = c.state()
        print(json.dumps({k: st.get(k) for k in ("gear", "controls")}, ensure_ascii=False, indent=1)[:1500])
        return 0
    if not c.start():
        print("bridge did not become active (is the Pleos emulator booted and port 8088 reachable?)", file=sys.stderr)
        return 2
    try:
        if a.cmd == "cue":
            cue(c)
        else:
            sig, v = a.signal, a.value
            if sig == "gear": c.gear(v)
            elif sig == "steering": c.steering(float(v))
            elif sig == "brake": c.brake(int(v))
            elif sig == "turn": c.turn(v)
            elif sig == "wiper": c.wiper(v)
            elif sig == "cruise": c.cruise(v)
            elif sig == "seat": c.seat(v, (a.extra or "true").lower() == "true")
            elif sig == "belt": c.belt(v)
            else: print("unknown signal", sig, file=sys.stderr); return 1
            time.sleep(0.5)
            st = c.state()
            print("gear:", json.dumps(st.get("gear", {}).get("actual")), "controls:", {k: st.get("controls", {}).get(k) for k in ("steering_deg", "brake_pct")})
    finally:
        c.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
