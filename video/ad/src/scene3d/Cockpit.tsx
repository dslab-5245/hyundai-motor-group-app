// The driver's side of the cabin in a "dark studio" style: sweeping dashboard, steering wheel, pedals in a footwell,
// signal stalk, slim cluster, ambient light strip, seats, interior pillar trims and the car display (the app's real
// screen as a texture). Everything is driven by DriveState (the scenario's signals), so the picture is frame-exact with
// the music. No people, no logos. LHD: the driver sits at x = DX. The exterior shell lives in Car.tsx.
import React, {useMemo} from 'react';
import {useLoader} from '@react-three/fiber';
import * as THREE from 'three';
import {RoundedBox} from '@react-three/drei';
import {DX} from './camera';
import {Strut} from './Car';

export {CAM, mixCam} from './camera';
export type {CamState} from './camera';

export interface DriveState {
  steer: number; // -1 (full left) .. 1 (full right)
  brake: number; // 0 .. 1 pedal travel
  throttle: number; // 0 .. 1
  blinker: 'off' | 'left' | 'right';
  blinkOn: boolean; // lamp phase
  flow: number; // 0 .. 1 (the app's flow score: cluster bar, ambient strip, cabin glow)
  pulse: number; // 0 .. 1 penalty pulse (camera shake, aberration)
  speed: number; // m/s
  dist: number; // metres driven (moves the road, spins the wheels)
  t: number; // seconds (rain, wipers, blink phase)
  wiper: number; // 0 .. 1 wipers running
  rain: number; // 0 .. 1 rain amount
}

export const LEATHER = '#15171D';
export const DASH = '#0F131C';
export const DASH_LO = '#0A0D14';
export const METAL = '#A9B4C6';
export const ICE = '#8FB3FF';

export const flowColor = (flow: number) => {
  const c = new THREE.Color('#1D3F8A').lerp(new THREE.Color('#9CC2FF'), Math.min(1, Math.max(0, flow)));
  return `#${c.getHexString()}`;
};

/** The dashboard as an extruded profile (z-y cross-section swept along x), so it reads as a real sweeping surface. */
export const DashBody: React.FC = () => {
  const geo = useMemo(() => {
    const s = new THREE.Shape();
    // profile in (z, y): two levels, a low rear shelf under the display and a higher cowl toward the windshield
    s.moveTo(-0.46, 0.78);
    s.lineTo(-0.6, 0.79);
    s.quadraticCurveTo(-0.7, 0.8, -0.76, 0.94);
    s.lineTo(-0.98, 0.95);
    s.lineTo(-1.02, 0.72);
    s.quadraticCurveTo(-0.95, 0.5, -0.74, 0.46);
    s.lineTo(-0.5, 0.44);
    s.quadraticCurveTo(-0.44, 0.6, -0.46, 0.78);
    const g = new THREE.ExtrudeGeometry(s, {depth: 1.86, bevelEnabled: true, bevelThickness: 0.015, bevelSize: 0.015, bevelSegments: 3, curveSegments: 24});
    g.rotateY(-Math.PI / 2); // extrusion axis (+z) -> -x
    g.translate(0.93, 0, 0);
    g.computeVertexNormals();
    return g;
  }, []);
  return (
    <mesh geometry={geo} castShadow receiveShadow>
      <meshStandardMaterial color="#0B0E15" roughness={0.85} metalness={0.05} envMapIntensity={0.25} />
    </mesh>
  );
};

export const Wheel: React.FC<{steer: number}> = ({steer}) => (
  <group position={[DX, 0.82, -0.36]} rotation={[-0.4, 0, 0]}>
    <group rotation={[0, 0, -steer * 1.15]}>
      <mesh castShadow>
        <torusGeometry args={[0.185, 0.02, 24, 120]} />
        <meshPhysicalMaterial color={LEATHER} roughness={0.6} clearcoat={0.3} clearcoatRoughness={0.5} />
      </mesh>
      {[Math.PI, 0, -Math.PI / 2].map((a, i) => (
        <mesh key={i} position={[Math.cos(a) * 0.1, Math.sin(a) * 0.1, 0]} rotation={[0, 0, a]} castShadow>
          <boxGeometry args={[0.15, i === 2 ? 0.045 : 0.03, 0.02]} />
          <meshStandardMaterial color={METAL} metalness={0.95} roughness={0.25} />
        </mesh>
      ))}
      <mesh rotation={[Math.PI / 2, 0, 0]} castShadow>
        <cylinderGeometry args={[0.06, 0.066, 0.05, 48]} />
        <meshPhysicalMaterial color="#1A1E28" roughness={0.3} metalness={0.35} clearcoat={0.7} clearcoatRoughness={0.2} />
      </mesh>
      <mesh position={[0, 0, 0.026]}>
        <torusGeometry args={[0.028, 0.0025, 12, 48]} />
        <meshStandardMaterial color={ICE} emissive={ICE} emissiveIntensity={0.6} roughness={0.3} />
      </mesh>
    </group>
    <mesh position={[0, -0.04, -0.17]} rotation={[0.95, 0, 0]}>
      <cylinderGeometry args={[0.032, 0.05, 0.36, 32]} />
      <meshStandardMaterial color={DASH_LO} roughness={0.5} />
    </mesh>
  </group>
);

export const Stalk: React.FC<{blinker: DriveState['blinker']}> = ({blinker}) => {
  const tilt = blinker === 'left' ? 0.24 : blinker === 'right' ? -0.24 : 0;
  return (
    <group position={[DX - 0.14, 0.8, -0.47]} rotation={[0, 0, tilt]}>
      <mesh position={[-0.075, 0, 0]} rotation={[0, 0, Math.PI / 2]}>
        <cylinderGeometry args={[0.011, 0.015, 0.17, 24]} />
        <meshPhysicalMaterial color="#1A1D26" roughness={0.4} clearcoat={0.5} />
      </mesh>
      <mesh position={[-0.155, 0, 0]}>
        <sphereGeometry args={[0.014, 16, 16]} />
        <meshStandardMaterial color={METAL} metalness={0.9} roughness={0.3} />
      </mesh>
    </group>
  );
};

export const Footwell: React.FC<{brake: number; throttle: number}> = ({brake, throttle}) => (
  <group position={[DX, 0, 0]}>
    <mesh position={[0, 0.0, -0.1]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
      <planeGeometry args={[1.0, 1.4]} />
      <meshStandardMaterial color="#0B0D13" roughness={0.95} />
    </mesh>
    <mesh position={[0, 0.22, -0.78]} rotation={[-0.95, 0, 0]} receiveShadow>
      <planeGeometry args={[1.0, 0.6]} />
      <meshStandardMaterial color="#0C0F16" roughness={0.9} />
    </mesh>
    <mesh position={[0.42, 0.25, -0.3]} rotation={[0, -Math.PI / 2, 0]} receiveShadow>
      <planeGeometry args={[1.2, 0.5]} />
      <meshStandardMaterial color="#0B0E15" roughness={0.9} />
    </mesh>
    {/* brake: wide rubber pad with ribs on a chrome arm from the firewall, swings further when pressed */}
    <group position={[-0.06, 0.36, -0.6]} rotation={[-0.85 + brake * 0.42, 0, 0]}>
      <group position={[0, -0.15, 0.02]}>
        <RoundedBox args={[0.105, 0.078, 0.014]} radius={0.006} smoothness={3} castShadow>
          <meshStandardMaterial color="#1E222B" roughness={0.75} />
        </RoundedBox>
        {[-0.024, 0, 0.024].map((y) => (
          <mesh key={y} position={[0, y, 0.0085]}>
            <boxGeometry args={[0.09, 0.006, 0.004]} />
            <meshStandardMaterial color={METAL} metalness={0.9} roughness={0.3} />
          </mesh>
        ))}
      </group>
      <mesh position={[0, -0.06, 0]}>
        <cylinderGeometry args={[0.007, 0.009, 0.18, 16]} />
        <meshStandardMaterial color={METAL} metalness={0.95} roughness={0.2} />
      </mesh>
    </group>
    {/* accelerator: tall narrow pad, hinged at the floor */}
    <group position={[0.1, 0.34, -0.6]} rotation={[-0.95 + throttle * 0.3, 0, 0]}>
      <group position={[0, -0.15, 0.02]}>
        <RoundedBox args={[0.058, 0.125, 0.014]} radius={0.006} smoothness={3} castShadow>
          <meshStandardMaterial color="#1E222B" roughness={0.75} />
        </RoundedBox>
        {[-0.04, -0.013, 0.013, 0.04].map((y) => (
          <mesh key={y} position={[0, y, 0.0085]}>
            <boxGeometry args={[0.046, 0.005, 0.004]} />
            <meshStandardMaterial color={METAL} metalness={0.9} roughness={0.3} />
          </mesh>
        ))}
      </group>
      <mesh position={[0, -0.06, 0]}>
        <cylinderGeometry args={[0.006, 0.008, 0.18, 16]} />
        <meshStandardMaterial color={METAL} metalness={0.95} roughness={0.2} />
      </mesh>
    </group>
    <pointLight position={[0.0, 0.5, -0.25]} intensity={0.5 + brake * 0.6} distance={1.0} decay={2} color="#AFC4F2" />
  </group>
);

export const Cluster: React.FC<{flow: number; blinker: DriveState['blinker']; blinkOn: boolean}> = ({flow, blinker, blinkOn}) => {
  const f = Math.max(0.03, Math.min(1, flow));
  return (
    <group position={[DX, 1.0, -0.8]} rotation={[-0.3, 0, 0]}>
      <RoundedBox args={[0.52, 0.11, 0.024]} radius={0.01} smoothness={4}>
        <meshPhysicalMaterial color="#04060A" roughness={0.1} clearcoat={1} clearcoatRoughness={0.08} />
      </RoundedBox>
      <mesh position={[0, -0.012, 0.013]}>
        <boxGeometry args={[0.36, 0.003, 0.001]} />
        <meshStandardMaterial color="#17223C" emissive="#17223C" emissiveIntensity={0.9} />
      </mesh>
      <mesh position={[-0.18 + 0.18 * f, -0.012, 0.014]}>
        <boxGeometry args={[0.36 * f, 0.005, 0.001]} />
        <meshStandardMaterial color={ICE} emissive={ICE} emissiveIntensity={1.8} toneMapped={false} />
      </mesh>
      {[-0.18, -0.09, 0, 0.09, 0.18].map((x) => (
        <mesh key={x} position={[x, 0.012, 0.013]}>
          <boxGeometry args={[0.001, 0.012, 0.001]} />
          <meshStandardMaterial color="#3A4A70" emissive="#3A4A70" emissiveIntensity={0.8} />
        </mesh>
      ))}
      {(['left', 'right'] as const).map((side) => {
        const on = blinker === side && blinkOn;
        return (
          <mesh key={side} position={[side === 'left' ? -0.225 : 0.225, 0.02, 0.013]} rotation={[0, 0, side === 'left' ? Math.PI / 2 : -Math.PI / 2]}>
            <coneGeometry args={[0.011, 0.022, 3]} />
            <meshStandardMaterial color={on ? '#5CFF9A' : '#0E1812'} emissive={on ? '#5CFF9A' : '#000000'} emissiveIntensity={on ? 2.5 : 0} toneMapped={false} />
          </mesh>
        );
      })}
    </group>
  );
};

/** Centre of the app's display: on the dash, right of the (central) steering wheel, turned to the driver. */
export const DISPLAY_POS: [number, number, number] = [0.47, 0.78, -1.2];
const DISPLAY_W = 0.42;
const DISPLAY_H = (DISPLAY_W * 821) / 1252;

/** The centre display with a ready texture (a still or a video frame of the app's pane). */
export const DisplayPanel: React.FC<{tex: THREE.Texture; glow: number}> = ({tex, glow}) => {
  const w = DISPLAY_W, h = DISPLAY_H;
  return (
    <group position={DISPLAY_POS} rotation={[-0.16, -0.34, 0]}>
      <RoundedBox args={[w + 0.028, h + 0.028, 0.018]} radius={0.01} smoothness={4} position={[0, 0, -0.011]}>
        <meshPhysicalMaterial color="#04060A" roughness={0.12} clearcoat={1} clearcoatRoughness={0.06} />
      </RoundedBox>
      <mesh>
        <planeGeometry args={[w, h]} />
        <meshBasicMaterial map={tex} color="#C9CFDA" toneMapped={false} />
      </mesh>
      <mesh position={[0, 0, 0.001]}>
        <planeGeometry args={[w, h]} />
        <meshPhysicalMaterial color="#000000" transparent opacity={0.05} roughness={0.14} clearcoat={0.5} clearcoatRoughness={0.14} />
      </mesh>
      <pointLight position={[0, -0.38, 0.22]} intensity={0.4 + glow * 0.35} distance={0.9} decay={2} color="#E6ECF8" />
    </group>
  );
};

export const DisplayImage: React.FC<{src: string; glow: number}> = ({src, glow}) => {
  const tex = useLoader(THREE.TextureLoader, src);
  useMemo(() => {
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.anisotropy = 8;
  }, [tex]);
  return <DisplayPanel tex={tex} glow={glow} />;
};

const Seat: React.FC<{x: number}> = ({x}) => (
  <group position={[x, 0, 0.55]}>
    <RoundedBox args={[0.5, 0.2, 0.52]} radius={0.05} smoothness={4} position={[0, 0.34, 0]} castShadow>
      <meshPhysicalMaterial color={LEATHER} roughness={0.7} clearcoat={0.2} />
    </RoundedBox>
    <group position={[0, 0.72, 0.25]} rotation={[0.2, 0, 0]}>
      <RoundedBox args={[0.5, 0.64, 0.16]} radius={0.05} smoothness={4} castShadow>
        <meshPhysicalMaterial color={LEATHER} roughness={0.7} clearcoat={0.2} />
      </RoundedBox>
      <RoundedBox args={[0.26, 0.14, 0.1]} radius={0.03} smoothness={4} position={[0, 0.42, -0.01]}>
        <meshPhysicalMaterial color={LEATHER} roughness={0.7} clearcoat={0.2} />
      </RoundedBox>
    </group>
  </group>
);

export const Wipers: React.FC<{wiper: number; t: number}> = ({wiper, t}) => {
  // tandem blades parked along the cowl; a 1.3 s sweep while the wipers run (eased, so they slow at the ends)
  const s = wiper > 0.01 ? 0.5 - 0.5 * Math.cos((t * Math.PI * 2) / 1.3) : 0;
  const sweep = 1.25 * s;
  return (
    <group position={[0, 0.98, -0.96]} rotation={[-1.0, 0, 0]}>
      {[DX - 0.1, 0.32].map((x, i) => (
        <group key={i} position={[x, 0, 0]} rotation={[0, 0, -1.5 + sweep]}>
          <mesh position={[0, i === 0 ? 0.3 : 0.26, 0.012]}>
            <boxGeometry args={[0.018, i === 0 ? 0.6 : 0.52, 0.01]} />
            <meshStandardMaterial color="#0A0B10" roughness={0.6} />
          </mesh>
          <mesh position={[0, i === 0 ? 0.3 : 0.26, 0.004]}>
            <boxGeometry args={[0.03, i === 0 ? 0.62 : 0.54, 0.004]} />
            <meshStandardMaterial color="#14171F" roughness={0.5} metalness={0.4} />
          </mesh>
        </group>
      ))}
    </group>
  );
};

/** Interior trims that frame the view from inside: doors, headliner, pillar trims and the ambient strip. */
export const CabinShell: React.FC<{flow: number}> = ({flow}) => {
  const strip = flowColor(flow);
  return (
    <group>
      <DashBody />
      <mesh position={[0, 0.765, -0.452]}>
        <boxGeometry args={[1.8, 0.0025, 0.003]} />
        <meshStandardMaterial color={strip} emissive={strip} emissiveIntensity={0.18 + flow * 0.22} metalness={0} roughness={1} />
      </mesh>
      {[-0.6, 0, 0.6].map((x) => (
        <pointLight key={x} position={[x, 0.76, -0.4]} intensity={0.06 + flow * 0.12} distance={0.8} decay={2} color={strip} />
      ))}
      {/* pillar trims and headliner (dark, matte) */}
      {([-0.9, 0.9] as const).map((x) => (
        <group key={x}>
          <Strut a={[x, 1.0, -0.95]} b={[x * 0.96, 1.46, -0.22]} w={0.1} d={0.11} color="#07090E" rough={0.9} />
          <Strut a={[x * 0.96, 1.46, -0.22]} b={[x * 0.96, 1.48, 1.0]} w={0.08} d={0.06} color="#07090E" rough={0.9} />
          <Strut a={[x * 0.97, 1.0, 0.35]} b={[x * 0.96, 1.47, 0.35]} w={0.09} d={0.09} color="#07090E" rough={0.9} />
        </group>
      ))}
      <mesh position={[0, 1.455, -0.22]} rotation={[0.3, 0, 0]}>
        <boxGeometry args={[1.76, 0.07, 0.1]} />
        <meshStandardMaterial color="#06080C" roughness={0.9} />
      </mesh>
      <mesh position={[0, 1.46, 0.45]} rotation={[Math.PI / 2, 0, 0]}>
        <planeGeometry args={[1.74, 1.36]} />
        <meshStandardMaterial color="#080A10" roughness={0.95} />
      </mesh>
      {/* door panels */}
      {([-0.93, 0.93] as const).map((x) => (
        <mesh key={x} position={[x, 0.5, 0.27]}>
          <boxGeometry args={[0.08, 1.0, 2.5]} />
          <meshPhysicalMaterial color="#0B0E15" roughness={0.65} clearcoat={0.2} />
        </mesh>
      ))}
      {/* floor and tunnel */}
      <mesh position={[0, 0.0, 0.3]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
        <planeGeometry args={[1.9, 2.4]} />
        <meshStandardMaterial color="#0A0C12" roughness={0.95} />
      </mesh>
      <RoundedBox args={[0.36, 0.3, 1.1]} radius={0.04} smoothness={3} position={[0.02, 0.15, 0.15]}>
        <meshStandardMaterial color="#0A0D14" roughness={0.88} />
      </RoundedBox>
      <Seat x={DX} />
      <Seat x={0.4} />
      {/* rear bench and bulkhead: close the cabin cut-out toward the tail */}
      <RoundedBox args={[1.78, 0.22, 0.5]} radius={0.05} smoothness={4} position={[0, 0.4, 1.25]}>
        <meshPhysicalMaterial color={LEATHER} roughness={0.7} clearcoat={0.2} />
      </RoundedBox>
      <RoundedBox args={[1.78, 0.74, 0.22]} radius={0.05} smoothness={4} position={[0, 0.67, 1.42]}>
        <meshPhysicalMaterial color={LEATHER} roughness={0.7} clearcoat={0.2} />
      </RoundedBox>
    </group>
  );
};

/** Everything inside the car; the display texture is passed in (still or video frame). */
export const Interior: React.FC<{state: DriveState; display: React.ReactNode}> = ({state, display}) => (
  <group>
    <CabinShell flow={state.flow} />
    <Cluster flow={state.flow} blinker={state.blinker} blinkOn={state.blinkOn} />
    <Wheel steer={state.steer} />
    <Stalk blinker={state.blinker} />
    <Footwell brake={state.brake} throttle={state.throttle} />
    {display}
    <Wipers wiper={state.wiper} t={state.t} />
  </group>
);
