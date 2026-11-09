// A procedural, brand-neutral electric crossover seen from outside: an extruded side profile with rounded edges and
// wheel arches, a dark glass greenhouse with slim pillars, wheels that roll and steer, light bars front and rear,
// indicators that blink from the turn signal and a tail bar that flares on the brake. The body pitches on braking and
// rolls in turns; the wheels stay on the ground. No logos, no badges.
import React, {useMemo} from 'react';
import * as THREE from 'three';
import {RoundedBox} from '@react-three/drei';
import type {DriveState} from './Cockpit';
import type {V3} from './camera';

export const GROUND_Y = -0.28;
export const WHEEL_R = 0.36;
export const AXLE_F = -1.65;
export const AXLE_R = 1.25;
export const TRACK = 0.9;
export const BODY = '#111A2E';
const RUBBER = '#0B0C10';
const RIM = '#6E7788';

/** Extrudes a (z, y) outline along x, centred, with rounded edges. */
const profileGeometry = (build: (s: THREE.Shape) => void, width: number, bevelT: number, bevelS: number, curveSegments = 36) => {
  const s = new THREE.Shape();
  build(s);
  const depth = width - 2 * bevelT;
  const g = new THREE.ExtrudeGeometry(s, {depth, bevelEnabled: true, bevelThickness: bevelT, bevelSize: bevelS, bevelOffset: -bevelS, bevelSegments: 7, curveSegments});
  g.rotateY(-Math.PI / 2); // extrusion axis (+z) -> -x
  g.translate(depth / 2, 0, 0);
  g.computeVertexNormals();
  return g;
};

const ARCH = 0.415;
const archStart = Math.asin((0.06 - 0.08) / ARCH);

/** The cabin, cut out of the solid body shell (world space; clipIntersection clips only inside all six planes). Without
 * it every camera above the beltline sees the top of the extrusion as a slab with the dash buried in it. The interior's
 * door panels, floor and rear bulkhead close the cut. */
const CABIN_CLIP = [
  new THREE.Plane(new THREE.Vector3(1, 0, 0), -0.97),
  new THREE.Plane(new THREE.Vector3(-1, 0, 0), -0.97),
  new THREE.Plane(new THREE.Vector3(0, 1, 0), -1.3),
  new THREE.Plane(new THREE.Vector3(0, -1, 0), 0.03),
  new THREE.Plane(new THREE.Vector3(0, 0, 1), -1.5),
  new THREE.Plane(new THREE.Vector3(0, 0, -1), -0.97),
];

const BodyShell: React.FC = () => {
  const geo = useMemo(
    () =>
      profileGeometry((s) => {
        s.moveTo(-2.7, 0.06);
        s.lineTo(-2.8, 0.36);
        s.quadraticCurveTo(-2.82, 0.64, -2.62, 0.76);
        s.quadraticCurveTo(-2.0, 0.86, -1.0, 0.95);
        s.lineTo(-0.9, 0.99);
        s.lineTo(1.5, 1.03);
        s.quadraticCurveTo(2.02, 1.02, 2.1, 0.74);
        s.quadraticCurveTo(2.14, 0.4, 2.02, 0.06);
        s.lineTo(AXLE_R + ARCH, 0.06);
        s.absarc(AXLE_R, 0.08, ARCH, archStart, Math.PI - archStart, false);
        s.lineTo(AXLE_F + ARCH, 0.06);
        s.absarc(AXLE_F, 0.08, ARCH, archStart, Math.PI - archStart, false);
        s.lineTo(-2.7, 0.06);
      }, 2.0, 0.09, 0.07),
    [],
  );
  return (
    <mesh geometry={geo} castShadow receiveShadow>
      <meshPhysicalMaterial color={BODY} metalness={0.7} roughness={0.36} clearcoat={1} clearcoatRoughness={0.1} envMapIntensity={1.1} clippingPlanes={CABIN_CLIP} clipIntersection />
    </mesh>
  );
};

const Greenhouse: React.FC<{opacity: number}> = ({opacity}) => {
  const geo = useMemo(
    () =>
      profileGeometry((s) => {
        s.moveTo(-0.96, 1.0);
        s.quadraticCurveTo(-0.62, 1.33, -0.22, 1.47);
        s.quadraticCurveTo(0.4, 1.53, 0.98, 1.5);
        s.quadraticCurveTo(1.38, 1.42, 1.64, 1.03);
        s.lineTo(1.5, 1.01);
        s.lineTo(-0.9, 1.0);
        s.lineTo(-0.96, 1.0);
      }, 1.78, 0.07, 0.035),
    [],
  );
  if (opacity <= 0.01) return null;
  return (
    <mesh geometry={geo} castShadow>
      <meshPhysicalMaterial color="#060B16" metalness={0.1} roughness={0.14} clearcoat={1} clearcoatRoughness={0.08} transparent opacity={opacity} envMapIntensity={0.6} depthWrite={false} />
    </mesh>
  );
};

/** A box between two points (pillars, rails). */
export const Strut: React.FC<{a: V3; b: V3; w: number; d: number; color?: string; rough?: number}> = ({a, b, w, d, color = BODY, rough = 0.3}) => {
  const key = a.join(',') + b.join(',');
  const {pos, quat, len} = useMemo(() => {
    const va = new THREE.Vector3(...a), vb = new THREE.Vector3(...b);
    const dir = vb.clone().sub(va);
    const len = dir.length();
    dir.normalize();
    const quat = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 0, 1), dir);
    return {pos: va.clone().add(vb).multiplyScalar(0.5), quat, len};
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  return (
    <mesh position={pos} quaternion={quat} castShadow>
      <boxGeometry args={[w, d, len]} />
      <meshPhysicalMaterial color={color} metalness={0.7} roughness={rough} clearcoat={1} clearcoatRoughness={0.1} />
    </mesh>
  );
};

const Pillars: React.FC = () => (
  <>
    {([-0.885, 0.885] as const).map((x) => (
      <group key={x}>
        <Strut a={[x, 1.0, -0.95]} b={[x, 1.47, -0.22]} w={0.07} d={0.05} />
        <Strut a={[x, 1.47, -0.22]} b={[x, 1.5, 0.98]} w={0.07} d={0.05} />
        <Strut a={[x, 1.5, 0.98]} b={[x, 1.03, 1.62]} w={0.07} d={0.05} />
        <Strut a={[x, 1.0, 0.35]} b={[x, 1.49, 0.35]} w={0.06} d={0.05} color="#070A12" rough={0.4} />
      </group>
    ))}
    {/* windshield header and cowl bars */}
    <Strut a={[-0.885, 1.47, -0.22]} b={[0.885, 1.47, -0.22]} w={0.06} d={0.05} />
    <Strut a={[-0.885, 1.5, 0.98]} b={[0.885, 1.5, 0.98]} w={0.06} d={0.05} />
  </>
);

const WheelUnit: React.FC<{x: number; z: number; spin: number; yaw: number}> = ({x, z, spin, yaw}) => {
  const out = x > 0 ? 1 : -1;
  return (
    <group position={[x, GROUND_Y + WHEEL_R, z]} rotation={[0, yaw, 0]}>
      <group rotation={[spin, 0, 0]}>
        <mesh rotation={[0, 0, Math.PI / 2]} castShadow>
          <cylinderGeometry args={[WHEEL_R, WHEEL_R, 0.25, 56]} />
          <meshStandardMaterial color={RUBBER} roughness={0.92} />
        </mesh>
        <mesh rotation={[0, 0, Math.PI / 2]} position={[out * 0.004, 0, 0]}>
          <cylinderGeometry args={[0.245, 0.245, 0.258, 48]} />
          <meshStandardMaterial color="#161B26" metalness={0.85} roughness={0.42} />
        </mesh>
        {[0, 1, 2, 3, 4].map((i) => (
          <group key={i} rotation={[(i * Math.PI * 2) / 5, 0, 0]}>
            <mesh position={[out * 0.118, 0.125, 0]}>
              <boxGeometry args={[0.03, 0.23, 0.07]} />
              <meshStandardMaterial color={RIM} metalness={0.95} roughness={0.28} />
            </mesh>
          </group>
        ))}
        <mesh rotation={[0, 0, Math.PI / 2]} position={[out * 0.12, 0, 0]}>
          <cylinderGeometry args={[0.075, 0.075, 0.03, 32]} />
          <meshStandardMaterial color={RIM} metalness={0.95} roughness={0.25} />
        </mesh>
      </group>
    </group>
  );
};

const Headlamp: React.FC<{x: number}> = ({x}) => {
  const target = useMemo(() => {
    const o = new THREE.Object3D();
    o.position.set(x * 1.4, GROUND_Y, -20);
    return o;
  }, [x]);
  return (
    <group>
      <mesh position={[x, 0.7, -2.74]} rotation={[0, x > 0 ? -0.12 : 0.12, 0]}>
        <boxGeometry args={[0.5, 0.03, 0.03]} />
        <meshStandardMaterial color="#EEF4FF" emissive="#DCE8FF" emissiveIntensity={4.5} toneMapped={false} />
      </mesh>
      <primitive object={target} />
      <spotLight position={[x, 0.68, -2.7]} target={target} angle={0.52} penumbra={0.8} intensity={220} distance={70} decay={1.7} color="#D8E6FF" />
    </group>
  );
};

const Lamp: React.FC<{pos: V3; size: [number, number, number]; color: string; on: boolean; k?: number}> = ({pos, size, color, on, k = 3}) => (
  <mesh position={pos}>
    <boxGeometry args={size} />
    <meshStandardMaterial color={on ? color : '#1A1510'} emissive={on ? color : '#000000'} emissiveIntensity={on ? k : 0} toneMapped={false} />
  </mesh>
);

export const Car: React.FC<{state: DriveState; glassOpacity: number}> = ({state, glassOpacity}) => {
  const spin = -state.dist / WHEEL_R;
  const yaw = -state.steer * 0.5;
  const pitch = state.brake * 0.022 - state.throttle * 0.006;
  const roll = state.steer * 0.014;
  const bob = -state.brake * 0.012;
  const l = state.blinker === 'left' && state.blinkOn, r = state.blinker === 'right' && state.blinkOn;
  const tail = 1.1 + state.brake * 2.6;
  return (
    <group>
      <group rotation={[pitch, 0, roll]} position={[0, bob, 0]}>
        <BodyShell />
        <Greenhouse opacity={glassOpacity} />
        <Pillars />
        {/* door seams */}
        {([-1.0, 1.0] as const).map((x) =>
          [-0.95, 0.36, 1.62].map((z) => (
            <mesh key={`${x}${z}`} position={[x, 0.55, z]}>
              <boxGeometry args={[0.03, 0.86, 0.006]} />
              <meshStandardMaterial color="#05070C" roughness={0.9} />
            </mesh>
          )),
        )}
        {/* character line along the flank */}
        {([-1.0, 1.0] as const).map((x) => (
          <mesh key={x} position={[x, 0.66, -0.3]} rotation={[0, 0, x > 0 ? -0.35 : 0.35]}>
            <boxGeometry args={[0.03, 0.012, 4.3]} />
            <meshPhysicalMaterial color={BODY} metalness={0.72} roughness={0.3} clearcoat={1} clearcoatRoughness={0.08} />
          </mesh>
        ))}
        {/* mirrors */}
        {([-1.05, 1.05] as const).map((x) => (
          <group key={x} position={[x, 1.04, -0.8]}>
            <RoundedBox args={[0.2, 0.1, 0.14]} radius={0.03} smoothness={4} castShadow>
              <meshPhysicalMaterial color={BODY} metalness={0.7} roughness={0.3} clearcoat={1} />
            </RoundedBox>
            <mesh position={[x > 0 ? -0.1 : 0.1, -0.02, 0.02]}>
              <boxGeometry args={[0.1, 0.04, 0.05]} />
              <meshPhysicalMaterial color={BODY} metalness={0.7} roughness={0.3} />
            </mesh>
          </group>
        ))}
        {/* light bars */}
        <Headlamp x={-0.58} />
        <Headlamp x={0.58} />
        <mesh position={[0, 0.735, -2.705]}>
          <boxGeometry args={[1.64, 0.012, 0.01]} />
          <meshStandardMaterial color="#C9D8FF" emissive="#C9D8FF" emissiveIntensity={1.6} toneMapped={false} />
        </mesh>
        <mesh position={[0, 0.9, 2.085]}>
          <boxGeometry args={[1.7, 0.034, 0.02]} />
          <meshStandardMaterial color="#FF2B3A" emissive="#FF2B3A" emissiveIntensity={tail} toneMapped={false} />
        </mesh>
        <pointLight position={[0, 0.85, 2.5]} intensity={1.5 + state.brake * 12} distance={9} decay={2} color="#FF3040" />
        {/* indicators: front corners, rear corners, mirror repeaters */}
        <Lamp pos={[-0.92, 0.66, -2.6]} size={[0.14, 0.035, 0.08]} color="#FFB13B" on={l} />
        <Lamp pos={[0.92, 0.66, -2.6]} size={[0.14, 0.035, 0.08]} color="#FFB13B" on={r} />
        <Lamp pos={[-0.95, 0.9, 2.0]} size={[0.12, 0.034, 0.08]} color="#FFB13B" on={l} />
        <Lamp pos={[0.95, 0.9, 2.0]} size={[0.12, 0.034, 0.08]} color="#FFB13B" on={r} />
        <Lamp pos={[-1.07, 1.06, -0.86]} size={[0.16, 0.012, 0.012]} color="#FFB13B" on={l} k={2} />
        <Lamp pos={[1.07, 1.06, -0.86]} size={[0.16, 0.012, 0.012]} color="#FFB13B" on={r} k={2} />
        {l && <pointLight position={[-1.1, 0.7, -2.6]} intensity={1.4} distance={3} decay={2} color="#FFB13B" />}
        {r && <pointLight position={[1.1, 0.7, -2.6]} intensity={1.4} distance={3} decay={2} color="#FFB13B" />}
        {l && <pointLight position={[-1.1, 0.9, 2.1]} intensity={1.0} distance={3} decay={2} color="#FFB13B" />}
        {r && <pointLight position={[1.1, 0.9, 2.1]} intensity={1.0} distance={3} decay={2} color="#FFB13B" />}
      </group>
      {/* underbody (below the cabin floor) and wheel-well liners (dark, so the arches do not see through) */}
      <mesh position={[0, -0.02, -0.35]} receiveShadow>
        <boxGeometry args={[1.3, 0.14, 4.3]} />
        <meshStandardMaterial color="#04050A" roughness={0.95} />
      </mesh>
      {[AXLE_F, AXLE_R].map((z) =>
        ([-0.62, 0.62] as const).map((x) => (
          <mesh key={`${z}${x}`} position={[x, 0.3, z]}>
            <boxGeometry args={[0.5, 0.62, 1.1]} />
            <meshStandardMaterial color="#04050A" roughness={0.95} />
          </mesh>
        )),
      )}
      <WheelUnit x={-TRACK} z={AXLE_F} spin={spin} yaw={yaw} />
      <WheelUnit x={TRACK} z={AXLE_F} spin={spin} yaw={yaw} />
      <WheelUnit x={-TRACK} z={AXLE_R} spin={spin} yaw={0} />
      <WheelUnit x={TRACK} z={AXLE_R} spin={spin} yaw={0} />
    </group>
  );
};
