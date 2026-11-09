// Camera presets (car-local metres: the car faces -z, the driver sits at x = DX on the left, the ground is at y = -0.28)
// and a keyframed camera path with continuous velocity, so one move can glide from outside the car to the driver's seat.
export type V3 = [number, number, number];

export interface CamState {
  pos: V3;
  look: V3;
  fov: number;
}

export const DX = -0.36; // driver's lateral position

export const CAM: Record<string, CamState> = {
  // interior
  wide: {pos: [DX + 0.22, 1.19, 0.5], look: [DX + 0.05, 0.9, -4.0], fov: 50},
  wheel: {pos: [DX + 0.12, 1.02, 0.22], look: [DX, 0.8, -0.45], fov: 38},
  pedal: {pos: [DX + 0.14, 0.8, 0.22], look: [DX - 0.03, 0.22, -0.62], fov: 40},
  stalk: {pos: [DX - 0.05, 1.0, 0.12], look: [DX - 0.2, 0.84, -0.6], fov: 34},
  display: {pos: [DX + 0.25, 1.02, 0.1], look: [0.18, 0.84, -0.62], fov: 32},
  road: {pos: [DX + 0.1, 1.15, 0.1], look: [DX + 0.1, 1.0, -8.0], fov: 56},
  passenger: {pos: [0.55, 1.12, 0.35], look: [DX - 0.1, 0.86, -0.6], fov: 44},
  // exterior (third person)
  follow: {pos: [1.6, 1.35, 6.4], look: [0, 0.72, -1.6], fov: 38},
  followLow: {pos: [0.9, 0.5, 6.2], look: [0, 0.72, -2.0], fov: 40},
  side: {pos: [8.4, 1.0, -0.3], look: [0, 0.62, -0.3], fov: 36},
  sideDriver: {pos: [-8.4, 1.0, -0.3], look: [0, 0.62, -0.3], fov: 36},
  sideClose: {pos: [5.2, 0.8, -1.2], look: [0, 0.6, -0.9], fov: 36},
  frontQ: {pos: [-3.9, 1.0, -5.8], look: [0.1, 0.55, -0.6], fov: 34},
  frontQR: {pos: [3.9, 1.0, -5.8], look: [-0.1, 0.55, -0.6], fov: 34},
  rear: {pos: [-0.4, 0.9, 5.9], look: [0, 0.75, 0.2], fov: 36},
  rearLow: {pos: [0.9, 0.32, 4.8], look: [0, 0.68, 0.4], fov: 42},
  lowFront: {pos: [2.9, 0.3, -3.9], look: [-0.3, 0.55, -0.8], fov: 42},
  wheelClose: {pos: [2.3, 0.28, -2.7], look: [0.86, 0.1, -1.65], fov: 28},
  top: {pos: [0.5, 8.2, 2.6], look: [0, 0.3, -0.5], fov: 40},
  nose: {pos: [0.2, 0.8, -4.8], look: [0, 0.72, -2.0], fov: 36},
  doorGlass: {pos: [-1.9, 1.15, 0.1], look: [DX + 0.3, 0.9, -0.5], fov: 36}, // just outside the driver's window
  sideDriverHigh: {pos: [-5.2, 2.8, 0.8], look: [0, 0.8, -0.3], fov: 36},
};

export const mixCam = (a: CamState, b: CamState, k: number): CamState => ({
  pos: [0, 1, 2].map((i) => a.pos[i] + (b.pos[i] - a.pos[i]) * k) as V3,
  look: [0, 1, 2].map((i) => a.look[i] + (b.look[i] - a.look[i]) * k) as V3,
  fov: a.fov + (b.fov - a.fov) * k,
});

/** One keyframe of a camera move. `cut` jumps here (no blend from the previous key); `hold` makes the move stop here. */
export interface CamKey {
  at: number; // seconds
  cam: CamState;
  cut?: boolean;
  hold?: boolean;
  orbit?: boolean; // reach this key by orbiting around the car (polar blend of the position) instead of a straight line
}

const hermite = (pa: number, ma: number, pb: number, mb: number, u: number) => {
  const u2 = u * u, u3 = u2 * u;
  return (2 * u3 - 3 * u2 + 1) * pa + (u3 - 2 * u2 + u) * ma + (-2 * u3 + 3 * u2) * pb + (u3 - u2) * mb;
};

const camNum = (c: CamState, k: number) => (k < 3 ? c.pos[k] : k < 6 ? c.look[k - 3] : c.fov);

/** The camera at time t along the keys: cubic Hermite with Catmull-Rom tangents (zero at cuts, holds and the ends). */
export const camPath = (keys: CamKey[], t: number): CamState => {
  if (keys.length === 0) return CAM.wide;
  if (t <= keys[0].at) return keys[0].cam;
  const last = keys[keys.length - 1];
  if (t >= last.at) return last.cam;
  let i = 0;
  while (i < keys.length - 2 && keys[i + 1].at <= t) i++;
  const a = keys[i], b = keys[i + 1];
  if (b.cut) return a.cam;
  const span = Math.max(1e-3, b.at - a.at);
  const u = Math.min(1, Math.max(0, (t - a.at) / span));
  const prev = i > 0 && !a.cut && !a.hold ? keys[i - 1] : null;
  const next = i + 2 < keys.length && !keys[i + 2].cut && !b.hold ? keys[i + 2] : null;
  const out: number[] = [];
  for (let k = 0; k < 7; k++) {
    const pa = camNum(a.cam, k), pb = camNum(b.cam, k);
    const ma = prev ? ((pb - camNum(prev.cam, k)) / (b.at - prev.at)) * span : 0;
    const mb = next ? ((camNum(next.cam, k) - pa) / (next.at - a.at)) * span : 0;
    out.push(hermite(pa, ma, pb, mb, u));
  }
  let pos: V3 = [out[0], out[1], out[2]];
  if (b.orbit) {
    // polar blend around the car's centre (x, z) with the same easing as the straight blend
    const ra = Math.hypot(a.cam.pos[0], a.cam.pos[2]), rb = Math.hypot(b.cam.pos[0], b.cam.pos[2]);
    const ta = Math.atan2(a.cam.pos[0], a.cam.pos[2]);
    let tb = Math.atan2(b.cam.pos[0], b.cam.pos[2]);
    while (tb - ta > Math.PI) tb -= Math.PI * 2;
    while (tb - ta < -Math.PI) tb += Math.PI * 2;
    const s = u * u * (3 - 2 * u);
    const r = ra + (rb - ra) * s, th = ta + (tb - ta) * s;
    pos = [Math.sin(th) * r, out[1], Math.cos(th) * r];
  }
  return {pos, look: [out[3], out[4], out[5]], fov: out[6]};
};

/** True when the camera sits inside the cabin (then the glass is hidden and the exterior shell is seen from within). */
export const camInside = (pos: V3) => Math.abs(pos[0]) < 1.0 && pos[1] > 0.3 && pos[1] < 1.5 && pos[2] > -1.0 && pos[2] < 1.4;

/** 0 outside .. 1 deep inside: fades the glass as the camera flies through it. */
export const insideness = (pos: V3) => {
  const d = Math.hypot(pos[0] * 1.6, (pos[1] - 1.05) * 2.2, (pos[2] - 0.15) * 0.9);
  return Math.min(1, Math.max(0, (1.9 - d) / 0.9));
};
