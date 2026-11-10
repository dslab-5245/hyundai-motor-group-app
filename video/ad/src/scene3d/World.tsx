// The night world outside the car: a damp three-lane road (fine grain, soft wetter patches), a skyline of lit windows,
// dashed lane lines and lamp posts that stream past by the distance driven, curbs and a guard rail, a horizon glow,
// and rain streaks when the wipers are on. The car stays at the origin; the world moves.
import React, {useLayoutEffect, useMemo, useRef} from 'react';
import * as THREE from 'three';
import {GROUND_Y} from './CarModel';

/** Seeded value noise in [0, 1], tileable, summed over the given grid sizes. */
const valueNoise = (n: number, octaves: [number, number][], seed0: number) => {
  let seed = seed0;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
  const grids = octaves.map(([g]) => Array.from({length: g * g}, () => rnd()));
  const out = new Float32Array(n * n);
  for (let y = 0; y < n; y++)
    for (let x = 0; x < n; x++) {
      let val = 0;
      octaves.forEach(([g, w], o) => {
        const grid = grids[o];
        const fx0 = (x / n) * g, fy0 = (y / n) * g;
        const x0 = Math.floor(fx0) % g, y0 = Math.floor(fy0) % g, x1 = (x0 + 1) % g, y1 = (y0 + 1) % g;
        const fx = fx0 - Math.floor(fx0), fy = fy0 - Math.floor(fy0);
        const sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
        val += w * ((grid[y0 * g + x0] + (grid[y0 * g + x1] - grid[y0 * g + x0]) * sx) * (1 - sy) + (grid[y1 * g + x0] + (grid[y1 * g + x1] - grid[y1 * g + x0]) * sx) * sy);
      });
      out[y * n + x] = val;
    }
  return out;
};

/** Roughness of damp asphalt: a fine grain everywhere, and broad, soft patches where the film of water is thicker. */
const roadTexture = () => {
  const n = 512;
  const c = document.createElement('canvas');
  c.width = n;
  c.height = n;
  const ctx = c.getContext('2d')!;
  const img = ctx.createImageData(n, n);
  const broad = valueNoise(n, [[4, 0.6], [8, 0.4]], 1234567);
  const grain = valueNoise(n, [[128, 0.6], [256, 0.4]], 7654321);
  for (let i = 0; i < n * n; i++) {
    const wet = Math.min(1, Math.max(0, (0.56 - broad[i]) / 0.3)); // 0 damp .. 1 wet, a wide soft transition
    const val = 0.64 - 0.2 * wet + (grain[i] - 0.5) * 0.2;
    const v = Math.round(Math.min(1, Math.max(0, val)) * 255);
    img.data[i * 4] = v;
    img.data[i * 4 + 1] = v;
    img.data[i * 4 + 2] = v;
    img.data[i * 4 + 3] = 255;
  }
  ctx.putImageData(img, 0, 0);
  const tex = new THREE.CanvasTexture(c);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.repeat.set(3, 44); // 10 m tiles on a 30 x 440 m road
  tex.anisotropy = 8;
  return tex;
};

/** Lit windows for the skyline: a grid of cells, some warm, some cool, most dark. */
const windowTexture = (seed0: number) => {
  const cols = 8, rows = 16, cell = 16;
  const c = document.createElement('canvas');
  c.width = cols * cell;
  c.height = rows * cell;
  const ctx = c.getContext('2d')!;
  ctx.fillStyle = '#000';
  ctx.fillRect(0, 0, c.width, c.height);
  let seed = seed0;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
  for (let y = 0; y < rows; y++)
    for (let x = 0; x < cols; x++) {
      const r = rnd();
      if (r > 0.34) continue;
      ctx.fillStyle = r < 0.2 ? '#FFD9A0' : r < 0.29 ? '#BFD4FF' : '#7A8FB8';
      ctx.globalAlpha = 0.35 + rnd() * 0.65;
      ctx.fillRect(x * cell + 4, y * cell + 5, cell - 8, cell - 10);
    }
  ctx.globalAlpha = 1;
  const tex = new THREE.CanvasTexture(c);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
};

const SKYLINE_SPAN = 520;

/** A city beyond the guard rails: dark blocks with lit windows that drift past, for depth and a horizon. */
const Skyline: React.FC<{dist: number}> = ({dist}) => {
  const mats = useMemo(
    () =>
      [11, 23, 37].map((seed, i) => {
        const tex = windowTexture(seed);
        tex.repeat.set(2 + i, 2 + i);
        return new THREE.MeshStandardMaterial({color: '#05070B', roughness: 0.9, emissive: '#FFFFFF', emissiveMap: tex, emissiveIntensity: 0.7});
      }),
    [],
  );
  const blocks = useMemo(() => {
    let seed = 9001;
    const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
    return Array.from({length: 84}, (_, i) => {
      const side = i % 2 === 0 ? -1 : 1;
      const w = 8 + rnd() * 12, h = 7 + rnd() * rnd() * 46, dd = 8 + rnd() * 12;
      return {x: side * (30 + rnd() * 44 + w / 2), z: (i / 84) * SKYLINE_SPAN + rnd() * 5, w, h, d: dd, m: Math.floor(rnd() * 3)};
    });
  }, []);
  return (
    <group>
      {blocks.map((b, i) => {
        const z = 40 - ((((b.z - dist) % SKYLINE_SPAN) + SKYLINE_SPAN) % SKYLINE_SPAN);
        return (
          <mesh key={i} position={[b.x, GROUND_Y + b.h / 2 - 1, z]} scale={[b.w, b.h, b.d]} material={mats[b.m]}>
            <boxGeometry args={[1, 1, 1]} />
          </mesh>
        );
      })}
    </group>
  );
};

const Rain: React.FC<{t: number; amount: number}> = ({t, amount}) => {
  const ref = useRef<THREE.InstancedMesh>(null);
  const N = 900;
  const seeds = useMemo(() => {
    let seed = 424242;
    const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
    return Array.from({length: N}, () => [rnd() * 20 - 10, rnd() * 9, rnd() * 26 - 16, 0.7 + rnd() * 0.5] as const);
  }, []);
  useLayoutEffect(() => {
    const m = ref.current;
    if (!m) return;
    const o = new THREE.Object3D();
    for (let i = 0; i < N; i++) {
      const [x, y0, z, s] = seeds[i];
      const y = GROUND_Y + ((y0 - t * 8.5 * s) % 9 + 9) % 9;
      o.position.set(x, y, z);
      o.rotation.set(0, 0, 0.06);
      o.scale.set(1, s, 1);
      o.updateMatrix();
      m.setMatrixAt(i, o.matrix);
    }
    m.instanceMatrix.needsUpdate = true;
  }, [t, seeds]);
  if (amount <= 0.01) return null;
  return (
    <instancedMesh ref={ref} args={[undefined, undefined, N]} frustumCulled={false}>
      <boxGeometry args={[0.006, 0.24, 0.006]} />
      <meshBasicMaterial color="#AFC2E0" transparent opacity={0.32 * amount} />
    </instancedMesh>
  );
};

export const World: React.FC<{dist: number; t: number; rain: number}> = ({dist, t, rain}) => {
  const rough = useMemo(roadTexture, []);
  const dashes = useMemo(() => Array.from({length: 40}, (_, i) => i), []);
  const poles = useMemo(() => Array.from({length: 12}, (_, i) => i), []);
  const posts = useMemo(() => Array.from({length: 90}, (_, i) => i), []);
  const phase = ((dist % 12) + 12) % 12;
  const polePhase = ((dist % 36) + 36) % 36;
  const postPhase = ((dist % 4) + 4) % 4;
  rough.offset.y = -dist / 10;
  return (
    <group>
      {/* wet asphalt */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, GROUND_Y, -140]} receiveShadow>
        <planeGeometry args={[30, 440]} />
        <meshStandardMaterial color="#07090D" roughness={1} roughnessMap={rough} metalness={0} envMapIntensity={0.32} />
      </mesh>
      {/* lane lines: dashed between lanes, solid at the edges */}
      {dashes.map((i) =>
        ([-1.85, 1.85] as const).map((x) => (
          <mesh key={`${i}${x}`} position={[x, GROUND_Y + 0.004, -4 - i * 12 + phase]} rotation={[-Math.PI / 2, 0, 0]}>
            <planeGeometry args={[0.12, 3]} />
            <meshStandardMaterial color="#AEB8CC" emissive="#AEB8CC" emissiveIntensity={0.22} roughness={0.6} />
          </mesh>
        )),
      )}
      {([-5.55, 5.55] as const).map((x) => (
        <mesh key={x} position={[x, GROUND_Y + 0.004, -140]} rotation={[-Math.PI / 2, 0, 0]}>
          <planeGeometry args={[0.12, 440]} />
          <meshStandardMaterial color="#AEB8CC" emissive="#AEB8CC" emissiveIntensity={0.18} roughness={0.6} />
        </mesh>
      ))}
      {/* curb and guard rail */}
      {([-6.3, 6.3] as const).map((x) => (
        <group key={x}>
          <mesh position={[x, GROUND_Y + 0.07, -140]}>
            <boxGeometry args={[0.36, 0.14, 440]} />
            <meshStandardMaterial color="#141922" roughness={0.85} />
          </mesh>
          <mesh position={[x + (x > 0 ? 0.7 : -0.7), GROUND_Y + 0.72, -140]}>
            <boxGeometry args={[0.05, 0.3, 440]} />
            <meshStandardMaterial color="#1B212C" metalness={0.5} roughness={0.55} envMapIntensity={0.4} />
          </mesh>
          {posts.map((i) => (
            <mesh key={i} position={[x + (x > 0 ? 0.7 : -0.7), GROUND_Y + 0.38, 6 - i * 4 + postPhase]}>
              <boxGeometry args={[0.08, 0.76, 0.08]} />
              <meshStandardMaterial color="#1E2531" roughness={0.6} />
            </mesh>
          ))}
        </group>
      ))}
      {/* street lamps */}
      {poles.map((i) =>
        ([-7.4, 7.4] as const).map((x) => (
          <group key={`${i}${x}`} position={[x, GROUND_Y, -22 - i * 36 + polePhase]}>
            <mesh position={[0, 3.6, 0]}>
              <cylinderGeometry args={[0.05, 0.08, 7.2, 10]} />
              <meshStandardMaterial color="#161B28" roughness={0.6} />
            </mesh>
            <mesh position={[x < 0 ? 1.3 : -1.3, 7.1, 0]} rotation={[0, 0, x < 0 ? -0.12 : 0.12]}>
              <boxGeometry args={[2.6, 0.06, 0.06]} />
              <meshStandardMaterial color="#161B28" roughness={0.6} />
            </mesh>
            <mesh position={[x < 0 ? 2.5 : -2.5, 7.0, 0]}>
              <boxGeometry args={[0.5, 0.08, 0.22]} />
              <meshStandardMaterial color="#FFE6C0" emissive="#FFDCA8" emissiveIntensity={3.0} toneMapped={false} />
            </mesh>
            {i < 4 ? <pointLight position={[x < 0 ? 2.5 : -2.5, 6.8, 0]} intensity={9} distance={30} decay={2} color="#FFDCA8" /> : null}
          </group>
        )),
      )}
      {/* horizon glow */}
      <mesh position={[0, 9, -300]}>
        <planeGeometry args={[360, 30]} />
        <meshBasicMaterial color="#17264F" transparent opacity={0.8} />
      </mesh>
      <mesh position={[0, 2, -302]}>
        <planeGeometry args={[360, 10]} />
        <meshBasicMaterial color="#2B3F78" transparent opacity={0.55} />
      </mesh>
      <Skyline dist={dist} />
      <Rain t={t} amount={rain} />
    </group>
  );
};
