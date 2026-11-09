// The night world outside the car: a wet three-lane road (glossy clearcoat with a noise roughness map for puddles),
// dashed lane lines and lamp posts that stream past by the distance driven, curbs and a guard rail, a horizon glow,
// and rain streaks when the wipers are on. The car stays at the origin; the world moves.
import React, {useLayoutEffect, useMemo, useRef} from 'react';
import * as THREE from 'three';
import {GROUND_Y} from './Car';

const noiseTexture = () => {
  const n = 256;
  const c = document.createElement('canvas');
  c.width = n;
  c.height = n;
  const ctx = c.getContext('2d')!;
  const img = ctx.createImageData(n, n);
  // value noise, three octaves, seeded
  let seed = 1234567;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);
  const grids = [8, 16, 32].map((g) => Array.from({length: g * g}, () => rnd()));
  const sample = (grid: number[], g: number, u: number, v: number) => {
    const x = u * g, y = v * g;
    const x0 = Math.floor(x) % g, y0 = Math.floor(y) % g, x1 = (x0 + 1) % g, y1 = (y0 + 1) % g;
    const fx = x - Math.floor(x), fy = y - Math.floor(y);
    const sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
    const a = grid[y0 * g + x0], b = grid[y0 * g + x1], cc = grid[y1 * g + x0], d = grid[y1 * g + x1];
    return (a + (b - a) * sx) * (1 - sy) + (cc + (d - cc) * sx) * sy;
  };
  for (let y = 0; y < n; y++)
    for (let x = 0; x < n; x++) {
      const u = x / n, v = y / n;
      let val = 0.55 * sample(grids[0], 8, u, v) + 0.3 * sample(grids[1], 16, u, v) + 0.15 * sample(grids[2], 32, u, v);
      // puddles: low values become mirror-like, the rest stays damp asphalt
      val = val < 0.42 ? 0.08 : 0.35 + (val - 0.42) * 1.1;
      const b = Math.round(Math.min(1, Math.max(0, val)) * 255);
      const i = (y * n + x) * 4;
      img.data[i] = b;
      img.data[i + 1] = b;
      img.data[i + 2] = b;
      img.data[i + 3] = 255;
    }
  ctx.putImageData(img, 0, 0);
  const tex = new THREE.CanvasTexture(c);
  tex.wrapS = tex.wrapT = THREE.RepeatWrapping;
  tex.repeat.set(5, 80);
  return tex;
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
  const rough = useMemo(noiseTexture, []);
  const dashes = useMemo(() => Array.from({length: 40}, (_, i) => i), []);
  const poles = useMemo(() => Array.from({length: 12}, (_, i) => i), []);
  const posts = useMemo(() => Array.from({length: 90}, (_, i) => i), []);
  const phase = ((dist % 12) + 12) % 12;
  const polePhase = ((dist % 36) + 36) % 36;
  const postPhase = ((dist % 4) + 4) % 4;
  rough.offset.y = -dist / 5.5;
  return (
    <group>
      {/* wet asphalt */}
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, GROUND_Y, -140]} receiveShadow>
        <planeGeometry args={[30, 440]} />
        <meshPhysicalMaterial color="#090B11" roughness={0.9} roughnessMap={rough} metalness={0.05} clearcoat={0.9} clearcoatRoughness={0.3} envMapIntensity={0.7} />
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
            <meshStandardMaterial color="#2A3242" metalness={0.7} roughness={0.4} />
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
            <pointLight position={[x < 0 ? 2.5 : -2.5, 6.8, 0]} intensity={34} distance={26} decay={2} color="#FFDCA8" />
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
      <Rain t={t} amount={rain} />
    </group>
  );
};
