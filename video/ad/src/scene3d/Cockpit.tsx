// A procedural driver's-seat scene in a "dark studio" style: sweeping dashboard, steering wheel, pedals in a footwell,
// signal stalk, cluster, ambient light strip, the car display (the app's real screen as a texture) and an abstract
// night road beyond the windshield. Everything is driven by DriveState (the scenario's signals) and CamState (the
// director's camera), so the picture is frame-exact with the music. No people, no logos. LHD: the driver sits at x≈-0.36.
import React, {useLayoutEffect, useMemo} from 'react';
import {useLoader, useThree} from '@react-three/fiber';
import * as THREE from 'three';
import {Environment, RoundedBox} from '@react-three/drei';
import {Bloom, ChromaticAberration, EffectComposer, Noise, ToneMapping, Vignette} from '@react-three/postprocessing';
import {ToneMappingMode} from 'postprocessing';
import {staticFile} from 'remotion';

export interface DriveState {
  steer: number; // -1 (full left) .. 1 (full right)
  brake: number; // 0 .. 1 pedal travel
  throttle: number; // 0 .. 1
  blinker: 'off' | 'left' | 'right';
  blinkOn: boolean; // lamp phase
  flow: number; // 0 .. 1 (the app's flow score: cluster arc, ambient strip, cabin glow)
  speed: number; // m/s, moves the road
  t: number; // seconds, road phase
}

export interface CamState {
  pos: [number, number, number];
  look: [number, number, number];
  fov: number;
}

const DX = -0.36; // driver's lateral position

export const CAM: Record<string, CamState> = {
  wide: {pos: [DX + 0.22, 1.19, 0.5], look: [DX + 0.05, 0.9, -4.0], fov: 50},
  wheel: {pos: [DX + 0.12, 1.02, 0.22], look: [DX, 0.8, -0.45], fov: 38},
  pedal: {pos: [DX + 0.14, 0.8, 0.22], look: [DX - 0.03, 0.22, -0.62], fov: 40},
  stalk: {pos: [DX - 0.05, 1.0, 0.12], look: [DX - 0.2, 0.84, -0.6], fov: 34},
  display: {pos: [DX + 0.25, 1.02, 0.1], look: [0.18, 0.84, -0.62], fov: 32},
  road: {pos: [DX + 0.1, 1.15, 0.1], look: [DX + 0.1, 1.0, -8.0], fov: 56},
};

export const mixCam = (a: CamState, b: CamState, k: number): CamState => ({
  pos: [0, 1, 2].map((i) => a.pos[i] + (b.pos[i] - a.pos[i]) * k) as [number, number, number],
  look: [0, 1, 2].map((i) => a.look[i] + (b.look[i] - a.look[i]) * k) as [number, number, number],
  fov: a.fov + (b.fov - a.fov) * k,
});

const Camera: React.FC<{cam: CamState}> = ({cam}) => {
  const {camera, invalidate} = useThree();
  useLayoutEffect(() => {
    const c = camera as THREE.PerspectiveCamera;
    c.position.set(cam.pos[0], cam.pos[1], cam.pos[2]);
    c.lookAt(cam.look[0], cam.look[1], cam.look[2]);
    c.fov = cam.fov;
    c.near = 0.04;
    c.far = 500;
    c.updateProjectionMatrix();
    invalidate();
  }, [camera, invalidate, cam.pos[0], cam.pos[1], cam.pos[2], cam.look[0], cam.look[1], cam.look[2], cam.fov]);
  return null;
};

const LEATHER = '#15171D';
const DASH = '#0F131C';
const DASH_LO = '#0A0D14';
const METAL = '#A9B4C6';
const ICE = '#8FB3FF';

const flowColor = (flow: number) => {
  const c = new THREE.Color('#1D3F8A').lerp(new THREE.Color('#9CC2FF'), Math.min(1, Math.max(0, flow)));
  return `#${c.getHexString()}`;
};

/** The dashboard as an extruded profile (z-y cross-section swept along x), so it reads as a real sweeping surface. */
const DashBody: React.FC = () => {
  const geo = useMemo(() => {
    const s = new THREE.Shape();
    // profile in (z, y): rear-top -> windshield base -> front face -> knee -> rear-bottom
    // two levels: a low rear shelf under the display, a higher cowl toward the windshield
    s.moveTo(-0.46, 0.78);
    s.lineTo(-0.6, 0.79);
    s.quadraticCurveTo(-0.7, 0.8, -0.76, 0.94);
    s.lineTo(-0.98, 0.95);
    s.lineTo(-1.02, 0.72);
    s.quadraticCurveTo(-0.95, 0.5, -0.74, 0.46);
    s.lineTo(-0.5, 0.44);
    s.quadraticCurveTo(-0.44, 0.6, -0.46, 0.78);
    const g = new THREE.ExtrudeGeometry(s, {depth: 2.1, bevelEnabled: true, bevelThickness: 0.015, bevelSize: 0.015, bevelSegments: 3, curveSegments: 24});
    g.rotateY(-Math.PI / 2); // extrusion axis (+z) -> +x
    g.translate(1.05, 0, 0); // extrusion ran toward -x after the rotation: centre it
    g.computeVertexNormals();
    return g;
  }, []);
  return (
    <mesh geometry={geo} castShadow receiveShadow>
      <meshStandardMaterial color="#0B0E15" roughness={0.85} metalness={0.05} envMapIntensity={0.25} />
    </mesh>
  );
};

const Wheel: React.FC<{steer: number}> = ({steer}) => (
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

const Stalk: React.FC<{blinker: DriveState['blinker']}> = ({blinker}) => {
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

const Footwell: React.FC<{brake: number; throttle: number}> = ({brake, throttle}) => (
  <group position={[DX, 0, 0]}>
    {/* floor */}
    <mesh position={[0, 0.0, -0.1]} rotation={[-Math.PI / 2, 0, 0]} receiveShadow>
      <planeGeometry args={[1.0, 1.4]} />
      <meshStandardMaterial color="#0B0D13" roughness={0.95} />
    </mesh>
    {/* firewall slope */}
    <mesh position={[0, 0.22, -0.78]} rotation={[-0.95, 0, 0]} receiveShadow>
      <planeGeometry args={[1.0, 0.6]} />
      <meshStandardMaterial color="#0C0F16" roughness={0.9} />
    </mesh>
    {/* side wall (tunnel) */}
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
    {/* footwell downlight (cool), brightens with the pedal so the travel reads */}
    <pointLight position={[0.0, 0.5, -0.25]} intensity={0.5 + brake * 0.6} distance={1.0} decay={2} color="#AFC4F2" />
  </group>
);

const Cluster: React.FC<{flow: number; blinker: DriveState['blinker']; blinkOn: boolean}> = ({flow, blinker, blinkOn}) => {
  const f = Math.max(0.03, Math.min(1, flow));
  return (
    <group position={[DX, 1.0, -0.8]} rotation={[-0.3, 0, 0]}>
      <RoundedBox args={[0.52, 0.11, 0.024]} radius={0.01} smoothness={4}>
        <meshPhysicalMaterial color="#04060A" roughness={0.1} clearcoat={1} clearcoatRoughness={0.08} />
      </RoundedBox>
      {/* flow bar: dim track, lit part grows from the left */}
      <mesh position={[0, -0.012, 0.013]}>
        <boxGeometry args={[0.36, 0.003, 0.001]} />
        <meshStandardMaterial color="#17223C" emissive="#17223C" emissiveIntensity={0.9} />
      </mesh>
      <mesh position={[-0.18 + 0.18 * f, -0.012, 0.014]}>
        <boxGeometry args={[0.36 * f, 0.005, 0.001]} />
        <meshStandardMaterial color={ICE} emissive={ICE} emissiveIntensity={1.8} toneMapped={false} />
      </mesh>
      {/* tick marks */}
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

const Display: React.FC<{src: string; glow: number}> = ({src, glow}) => {
  const tex = useLoader(THREE.TextureLoader, src);
  useMemo(() => {
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.anisotropy = 8;
  }, [tex]);
  const w = 0.42;
  const h = (w * 821) / 1252;
  return (
    <group position={[0.2, 0.92, -0.6]} rotation={[-0.14, -0.16, 0]}>
      <RoundedBox args={[w + 0.028, h + 0.028, 0.018]} radius={0.01} smoothness={4} position={[0, 0, -0.011]}>
        <meshPhysicalMaterial color="#04060A" roughness={0.12} clearcoat={1} clearcoatRoughness={0.06} />
      </RoundedBox>
      <mesh>
        <planeGeometry args={[w, h]} />
        <meshBasicMaterial map={tex} color="#C9CFDA" />
      </mesh>
      {/* glass sheen */}
      <mesh position={[0, 0, 0.001]}>
        <planeGeometry args={[w, h]} />
        <meshPhysicalMaterial color="#000000" transparent opacity={0.08} roughness={0.05} clearcoat={1} clearcoatRoughness={0.03} />
      </mesh>
      <pointLight position={[0, 0, 0.14]} intensity={0.35 + glow * 0.35} distance={0.8} decay={2} color="#E6ECF8" />
    </group>
  );
};

const Cabin: React.FC<{flow: number}> = ({flow}) => {
  const strip = flowColor(flow);
  return (
    <group>
      <DashBody />
      {/* metallic trim line along the dash break */}
      {/* ambient light strip in the groove under the lip: colour and intensity follow flow */}
      <mesh position={[0, 0.765, -0.452]}>
        <boxGeometry args={[1.96, 0.0025, 0.003]} />
        <meshStandardMaterial color={strip} emissive={strip} emissiveIntensity={0.18 + flow * 0.22} metalness={0} roughness={1} />
      </mesh>
      {[-0.6, 0, 0.6].map((x) => (
        <pointLight key={x} position={[x, 0.76, -0.4]} intensity={0.06 + flow * 0.12} distance={0.8} decay={2} color={strip} />
      ))}
      {/* A-pillars and roof lip */}
      <mesh position={[-1.08, 1.1, -0.55]} rotation={[0.35, 0.25, 0.08]}>
        <boxGeometry args={[0.1, 1.3, 0.14]} />
        <meshStandardMaterial color="#07090E" roughness={0.9} />
      </mesh>
      <mesh position={[1.08, 1.1, -0.55]} rotation={[0.35, -0.25, -0.08]}>
        <boxGeometry args={[0.1, 1.3, 0.14]} />
        <meshStandardMaterial color="#07090E" roughness={0.9} />
      </mesh>
      <mesh position={[0, 1.62, -0.45]} rotation={[0.42, 0, 0]}>
        <boxGeometry args={[2.3, 0.1, 0.7]} />
        <meshStandardMaterial color="#06080C" roughness={0.9} />
      </mesh>
      {/* doors */}
      <mesh position={[-1.08, 0.7, 0.2]}>
        <boxGeometry args={[0.1, 0.9, 1.6]} />
        <meshPhysicalMaterial color="#0B0E15" roughness={0.65} clearcoat={0.2} />
      </mesh>
      <mesh position={[1.08, 0.7, 0.2]}>
        <boxGeometry args={[0.1, 0.9, 1.6]} />
        <meshPhysicalMaterial color="#0B0E15" roughness={0.65} clearcoat={0.2} />
      </mesh>
      {/* hood */}
      <mesh position={[0, 0.84, -1.95]} rotation={[0.08, 0, 0]}>
        <boxGeometry args={[1.95, 0.06, 1.7]} />
        <meshPhysicalMaterial color="#101727" roughness={0.45} metalness={0.5} clearcoat={0.5} clearcoatRoughness={0.3} />
      </mesh>
    </group>
  );
};

const Road: React.FC<{t: number; speed: number}> = ({t, speed}) => {
  const dashes = useMemo(() => Array.from({length: 40}, (_, i) => i), []);
  const poles = useMemo(() => Array.from({length: 12}, (_, i) => i), []);
  const phase = (t * speed) % 12;
  const polePhase = (t * speed) % 36;
  return (
    <group position={[0, -0.28, 0]}>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, 0, -120]} receiveShadow>
        <planeGeometry args={[16, 300]} />
        <meshStandardMaterial color="#0A0C12" roughness={0.92} />
      </mesh>
      {dashes.map((i) => (
        <mesh key={i} position={[1.8, 0.005, -3 - i * 12 + phase]} rotation={[-Math.PI / 2, 0, 0]}>
          <planeGeometry args={[0.12, 3]} />
          <meshStandardMaterial color="#95A0B8" emissive="#95A0B8" emissiveIntensity={0.3} />
        </mesh>
      ))}
      {dashes.map((i) => (
        <mesh key={`l${i}`} position={[-1.8, 0.005, -3 - i * 12 + phase]} rotation={[-Math.PI / 2, 0, 0]}>
          <planeGeometry args={[0.12, 3]} />
          <meshStandardMaterial color="#95A0B8" emissive="#95A0B8" emissiveIntensity={0.3} />
        </mesh>
      ))}
      {poles.map((i) =>
        ([-5.6, 5.6] as const).map((x) => (
          <group key={`${i}${x}`} position={[x, 0, -22 - i * 36 + polePhase]}>
            <mesh position={[0, 3, 0]}>
              <cylinderGeometry args={[0.05, 0.07, 6, 10]} />
              <meshStandardMaterial color="#161B28" roughness={0.6} />
            </mesh>
            <mesh position={[x < 0 ? 1.0 : -1.0, 5.9, 0]}>
              <sphereGeometry args={[0.14, 12, 12]} />
              <meshStandardMaterial color="#FFE6C0" emissive="#FFDCA8" emissiveIntensity={3.2} toneMapped={false} />
            </mesh>
            <pointLight position={[x < 0 ? 1.0 : -1.0, 5.7, 0]} intensity={2.0} distance={13} decay={2} color="#FFDCA8" />
          </group>
        )),
      )}
      <mesh position={[0, 7, -240]}>
        <planeGeometry args={[260, 22]} />
        <meshBasicMaterial color="#17264F" transparent opacity={0.85} />
      </mesh>
    </group>
  );
};

export const Cockpit: React.FC<{state: DriveState; cam: CamState; panelSrc: string; hdri?: string}> = ({state, cam, panelSrc, hdri}) => (
  <>
    <Camera cam={cam} />
    <color attach="background" args={['#05080F']} />
    <fog attach="fog" args={['#070B16', 10, 190]} />
    <Environment files={hdri ?? staticFile('hdri/shanghai_bund_1k.hdr')} environmentIntensity={0.45} />
    <ambientLight intensity={0.1} color="#9FB4E0" />
    <directionalLight position={[2.0, 3.5, -4.0]} intensity={1.3} color="#A9C6FF" castShadow shadow-mapSize={[2048, 2048]} />
    <directionalLight position={[-3.0, 2.5, 2.0]} intensity={0.3} color="#5F76A8" />
    <Cabin flow={state.flow} />
    <Cluster flow={state.flow} blinker={state.blinker} blinkOn={state.blinkOn} />
    <Wheel steer={state.steer} />
    <Stalk blinker={state.blinker} />
    <Footwell brake={state.brake} throttle={state.throttle} />
    <Display src={panelSrc} glow={state.flow} />
    <Road t={state.t} speed={state.speed} />
    <EffectComposer multisampling={4}>
      <Bloom luminanceThreshold={0.95} intensity={0.5} mipmapBlur radius={0.5} />
      <ChromaticAberration offset={[0.0004, 0.0004]} radialModulation modulationOffset={0.35} />
      <Vignette eskil={false} offset={0.2} darkness={0.72} />
      <Noise opacity={0.028} />
      <ToneMapping mode={ToneMappingMode.ACES_FILMIC} />
    </EffectComposer>
  </>
);
