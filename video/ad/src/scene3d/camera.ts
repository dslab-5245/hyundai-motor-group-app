// Camera presets (car-local metres: the car faces -z, the driver sits in the centre (x = 0, a three-seater), the ground
// is at y = 0, the roof at 1.15, the nose at z = -2.4, the tail at z = 1.9) and a keyframed camera path with continuous
// velocity, so one move can glide from outside the car to the driver's seat.
export type V3 = [number, number, number];

export interface CamState {
  pos: V3;
  look: V3;
  fov: number;
}

export const DX = 0; // driver's lateral position

export const CAM: Record<string, CamState> = {
  // interior (eye height about 0.98; wheel hub at [0, 0.65, -0.94]; display at [0.47, 0.78, -1.2]; pedals at z = -1.5)
  wide: {pos: [0.04, 0.99, -0.34], look: [0.12, 0.74, -4.0], fov: 62},
  wheel: {pos: [0.1, 0.96, -0.38], look: [0.04, 0.66, -1.0], fov: 46},
  pedal: {pos: [0.24, 0.44, -0.98], look: [-0.03, 0.24, -1.52], fov: 44},
  stalk: {pos: [-0.2, 0.93, -0.5], look: [-0.06, 0.7, -1.15], fov: 38},
  display: {pos: [0.2, 0.95, -0.5], look: [0.47, 0.77, -1.2], fov: 34},
  road: {pos: [0, 1.0, -0.55], look: [0, 0.86, -8.0], fov: 58},
  passenger: {pos: [0.5, 0.99, -0.02], look: [0.05, 0.7, -1.1], fov: 50},
  // exterior (third person)
  follow: {pos: [1.6, 1.5, 6.6], look: [0, 0.6, -1.4], fov: 38},
  followLow: {pos: [0.9, 0.5, 6.2], look: [0, 0.6, -1.6], fov: 40},
  side: {pos: [5.7, 0.9, -0.2], look: [0, 0.52, -0.2], fov: 48},
  sideDriver: {pos: [-5.7, 0.9, -0.2], look: [0, 0.52, -0.2], fov: 48},
  sideClose: {pos: [4.6, 0.75, -1.4], look: [0, 0.5, -0.5], fov: 40},
  frontQ: {pos: [-3.9, 1.05, -6.2], look: [0.1, 0.48, -0.6], fov: 34},
  frontQR: {pos: [3.9, 1.05, -6.2], look: [-0.1, 0.48, -0.6], fov: 34},
  rear: {pos: [-0.4, 1.0, 6.1], look: [0, 0.62, 0.4], fov: 36},
  rearLow: {pos: [0.9, 0.34, 5.0], look: [0, 0.58, 0.6], fov: 42},
  lowFront: {pos: [2.9, 0.32, -4.4], look: [-0.3, 0.48, -1.0], fov: 42},
  wheelClose: {pos: [2.6, 0.42, -2.9], look: [1.0, 0.36, -1.5], fov: 28},
  top: {pos: [0.5, 8.6, 2.4], look: [0, 0.3, -0.3], fov: 40},
  nose: {pos: [0.2, 0.85, -5.4], look: [0, 0.58, -2.0], fov: 36},
  doorGlass: {pos: [-2.0, 1.0, -0.35], look: [0.25, 0.72, -0.95], fov: 36}, // just outside the left window
  sideDriverHigh: {pos: [-5.2, 2.8, 0.8], look: [0, 0.6, -0.2], fov: 36},
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
export const camInside = (pos: V3) => Math.abs(pos[0]) < 0.95 && pos[1] > 0.12 && pos[1] < 1.14 && pos[2] > -1.8 && pos[2] < 1.4;

/** 0 outside .. 1 deep inside: fades the glass as the camera flies through it. */
export const insideness = (pos: V3) => {
  const d = Math.hypot(pos[0] * 1.6, (pos[1] - 0.8) * 2.2, (pos[2] + 0.4) * 0.9);
  return Math.min(1, Math.max(0, (2.7 - d) / 1.2)); // ~0 at the side window, 1 well inside
};
