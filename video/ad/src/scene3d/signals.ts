// Per-frame driving signals exported by the engine (video/ad/public/run/signals_story.json, gradle :engine:dumpSignals)
// turned into the DriveState the 3D scene needs: pedal travel with a little mechanical lag, integrated distance,
// a derived throttle, wiper/rain ramps and the blinker phase.
import type {DriveState} from './Cockpit';

export interface SignalFrame {
  t: number;
  steer: number; // degrees
  brake: number; // 0/1
  signal: 'OFF' | 'LEFT' | 'RIGHT';
  wiper: number;
  cruise: number;
  gear: string;
  speed: number; // km/h
  flow: number;
  pulse: number;
  event: string | null;
  graced: boolean;
}

export interface Signals {
  scenario: string;
  fps: number;
  duration: number;
  frames: SignalFrame[];
}

export interface Prepared {
  sig: Signals;
  brake: Float32Array; // smoothed pedal travel 0..1
  throttle: Float32Array;
  dist: Float32Array; // metres since frame 0
  wiper: Float32Array; // 0..1 ramp
}

const cache = new WeakMap<Signals, Prepared>();

/** One pass over the frames: pedal lag, distance integral, throttle guess, wiper ramp. Cached per signals object. */
export const prepare = (sig: Signals): Prepared => {
  const hit = cache.get(sig);
  if (hit) return hit;
  const n = sig.frames.length;
  const dt = 1 / sig.fps;
  const brake = new Float32Array(n), throttle = new Float32Array(n), dist = new Float32Array(n), wiper = new Float32Array(n);
  let b = 0, w = 0, d = 0, th = 0.2;
  for (let i = 0; i < n; i++) {
    const f = sig.frames[i];
    const target = f.brake ? 1 : 0;
    // press in ~120 ms, release in ~260 ms
    b += (target - b) * Math.min(1, dt / (target > b ? 0.045 : 0.09));
    brake[i] = b;
    const v = f.speed / 3.6;
    const v0 = i > 0 ? sig.frames[i - 1].speed / 3.6 : v;
    const accel = (v - v0) / dt;
    const want = f.brake ? 0 : f.cruise ? 0.28 : v < 0.5 ? 0 : 0.18 + Math.max(0, Math.min(0.5, accel * 0.25));
    th += (want - th) * Math.min(1, dt / 0.25);
    throttle[i] = th;
    d += v * dt;
    dist[i] = d;
    w += ((f.wiper ? 1 : 0) - w) * Math.min(1, dt / 0.6);
    wiper[i] = w;
  }
  const p = {sig, brake, throttle, dist, wiper};
  cache.set(sig, p);
  return p;
};

const lerp = (a: number, b: number, k: number) => a + (b - a) * k;

/** The DriveState at scenario time st (seconds), interpolated between exported frames; clamped at both ends. */
export const stateAt = (sig: Signals, st: number): DriveState => {
  const p = prepare(sig);
  const n = sig.frames.length;
  const x = Math.max(0, Math.min(n - 1.0001, st * sig.fps));
  const i = Math.floor(x), k = x - i, j = Math.min(n - 1, i + 1);
  const a = sig.frames[i], b = sig.frames[j];
  const steerDeg = lerp(a.steer, b.steer, k);
  const speedKmh = lerp(a.speed, b.speed, k);
  const signal = k < 0.5 ? a.signal : b.signal;
  const before = st < 0 ? st : 0; // before the scenario starts: keep rolling at the first speed
  return {
    steer: Math.max(-1, Math.min(1, steerDeg / 160)),
    brake: lerp(p.brake[i], p.brake[j], k),
    throttle: lerp(p.throttle[i], p.throttle[j], k),
    blinker: signal === 'LEFT' ? 'left' : signal === 'RIGHT' ? 'right' : 'off',
    blinkOn: Math.floor(st * 1.5 + 1000) % 2 === 0,
    flow: lerp(a.flow, b.flow, k),
    pulse: lerp(a.pulse, b.pulse, k),
    speed: speedKmh / 3.6,
    dist: lerp(p.dist[i], p.dist[j], k) + before * (a.speed / 3.6),
    t: st,
    wiper: lerp(p.wiper[i], p.wiper[j], k),
    rain: lerp(p.wiper[i], p.wiper[j], k),
  };
};
