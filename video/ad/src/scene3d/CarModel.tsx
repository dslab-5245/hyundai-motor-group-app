// The car: an unbranded concept coupe (Khronos glTF sample "Car Concept", CC-BY 4.0, built on a CC0 model; see
// CREDITS.md) repainted deep navy, with the logo plate and emblem removed, and wired to the driving signals:
// wheels roll with the distance and steer, the steering wheel turns, pedals travel, the tail lights flare on the
// brake, indicators blink per side, the body pitches and rolls a little. The driver sits in the centre (it is a
// three-seater); the app's display floats on the dash to the right of the wheel.
import React, {useLayoutEffect, useMemo} from 'react';
import * as THREE from 'three';
import {useGLTF} from '@react-three/drei';
import {staticFile} from 'remotion';
import type {DriveState} from './Cockpit';
import {flowColor} from './Cockpit';

export const GROUND_Y = 0;
export const WHEEL_R = 0.384;
const MODEL = 'models/carconcept/CarConcept.glb';

const ICE = new THREE.Color('#8FB3FF');
const AMBER = new THREE.Color('#FF9A1F');
const HEAD = new THREE.Color('#DCE8FF');

interface Rig {
  root: THREE.Object3D;
  wheels: {node: THREE.Object3D; front: boolean; pad: THREE.Object3D | null; padBase: THREE.Quaternion}[];
  steering: THREE.Object3D | null;
  steeringBase: THREE.Quaternion;
  brakePedal: THREE.Object3D | null;
  accelPedal: THREE.Object3D | null;
  brakeY: number;
  accelY: number;
  glass: THREE.MeshPhysicalMaterial;
  brakeMat: THREE.MeshStandardMaterial | null;
  sigL: THREE.MeshStandardMaterial;
  sigR: THREE.MeshStandardMaterial;
  headL: THREE.MeshStandardMaterial;
  headR: THREE.MeshStandardMaterial;
  dash: THREE.MeshStandardMaterial | null;
}

const LEFT = new THREE.Plane(new THREE.Vector3(-1, 0, 0), 0); // keeps world x <= 0 (the driver's left)
const RIGHT = new THREE.Plane(new THREE.Vector3(1, 0, 0), 0);

/** Splits a mesh that spans both sides into a left and a right copy (clipped at the centre plane) with own materials. */
const splitSides = (mesh: THREE.Mesh, base: THREE.MeshStandardMaterial): [THREE.MeshStandardMaterial, THREE.MeshStandardMaterial] => {
  const l = base.clone(), r = base.clone();
  l.clippingPlanes = [LEFT];
  r.clippingPlanes = [RIGHT];
  const ml = mesh.clone(), mr = mesh.clone();
  ml.material = l;
  mr.material = r;
  mesh.parent?.add(ml, mr);
  mesh.visible = false;
  return [l, r];
};

const buildRig = (scene: THREE.Object3D): Rig => {
  const root = scene;
  const glass = new THREE.MeshPhysicalMaterial({color: '#0A1018', metalness: 0, roughness: 0.03, transparent: true, opacity: 0.5, envMapIntensity: 1.6, clearcoat: 1, clearcoatRoughness: 0.02, depthWrite: false, side: THREE.DoubleSide});
  let brakeMat: THREE.MeshStandardMaterial | null = null;
  let dash: THREE.MeshStandardMaterial | null = null;
  let sigMesh: THREE.Mesh | null = null, headMesh: THREE.Mesh | null = null;
  const seen = new Set<THREE.Material>();
  root.traverse((o) => {
    const m = o as THREE.Mesh;
    if (!m.isMesh) return;
    m.castShadow = true;
    m.receiveShadow = true;
    if (o.name === 'License_Plate' || o.name === 'InteriorSteeringEmblem') m.visible = false;
    if (o.name === 'BodyTurnsignalsRear') sigMesh = m;
    if (o.name === 'BodyHeadlights') headMesh = m;
    const mats = Array.isArray(m.material) ? m.material : [m.material];
    mats.forEach((mat, i) => {
      const s = mat as THREE.MeshPhysicalMaterial;
      if (s.name === 'Glass') {
        if (Array.isArray(m.material)) m.material[i] = glass;
        else m.material = glass;
        m.castShadow = false;
        m.renderOrder = 5;
        return;
      }
      if (seen.has(s)) return;
      seen.add(s);
      s.envMapIntensity = 1.0;
      switch (s.name) {
        case 'Paint 1 Carmine': // the body colour: deep metallic navy under a clear coat
          s.color.set('#0E2350');
          s.metalness = 0.85;
          s.roughness = 0.3;
          s.clearcoat = 1;
          s.clearcoatRoughness = 0.04;
          s.envMapIntensity = 1.5;
          if (s.normalMap) s.normalScale.setScalar(0.25); // the flake normal map sparkles too much at night
          break;
        case 'Paint 2 Carmine':
          s.color.set('#05070B');
          s.metalness = 0.6;
          s.roughness = 0.22;
          s.envMapIntensity = 1.2;
          break;
        case 'Interior 3 Carmine': // the accent upholstery: slate blue instead of red
          s.color.set('#1B2740');
          s.roughness = 0.55;
          break;
        case 'Tireside': // the sidewall texture carries lettering
          s.map = null;
          s.color.set('#0A0A0B');
          s.roughness = 0.75;
          s.needsUpdate = true;
          break;
        case 'Brakelight':
          brakeMat = s;
          s.emissive.set('#FF1A22');
          break;
        case 'Dashboard':
          dash = s;
          s.emissive.copy(ICE);
          s.emissiveIntensity = 1.2;
          break;
        case 'Rim2':
          s.envMapIntensity = 1.6;
          break;
        default:
      }
    });
  });
  const sigBase = (sigMesh as THREE.Mesh | null)?.material as THREE.MeshStandardMaterial;
  const headBase = (headMesh as THREE.Mesh | null)?.material as THREE.MeshStandardMaterial;
  const [sigL, sigR] = splitSides(sigMesh!, sigBase);
  const [headL, headR] = splitSides(headMesh!, headBase);
  const wheels = ['WheelFrontL', 'WheelFrontR', 'WheelRearL', 'WheelRearR'].map((n) => {
    const node = root.getObjectByName(n)!;
    // the file bakes an arbitrary roll (and a steer on the fronts) into each wheel; the caliper keeps that roll angle
    const ax = new THREE.Vector3(1, 0, 0).applyQuaternion(node.quaternion);
    const padBase = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 0, 1), -Math.atan2(ax.y, ax.x)).multiply(node.quaternion);
    return {node, front: n.includes('Front'), pad: root.getObjectByName(`${n}BrakePad`) ?? null, padBase};
  });
  const steering = root.getObjectByName('InteriorSteeringCylinder') ?? null;
  const brakePedal = root.getObjectByName('InteriorPedalBrake') ?? null;
  const accelPedal = root.getObjectByName('InteriorPedalAccel') ?? null;
  return {
    root, wheels, steering, steeringBase: steering ? steering.quaternion.clone() : new THREE.Quaternion(),
    brakePedal, accelPedal, brakeY: brakePedal?.position.y ?? 0, accelY: accelPedal?.position.y ?? 0,
    glass, brakeMat, sigL, sigR, headL, headR, dash,
  };
};

const X = new THREE.Vector3(1, 0, 0), Y = new THREE.Vector3(0, 1, 0), Z = new THREE.Vector3(0, 0, 1);
const q1 = new THREE.Quaternion(), q2 = new THREE.Quaternion();

/** A soft dark blob under the car: it grounds the body where the shadow map is too coarse. */
const blobTexture = () => {
  const c = document.createElement('canvas');
  c.width = c.height = 256;
  const ctx = c.getContext('2d')!;
  const g = ctx.createRadialGradient(128, 128, 20, 128, 128, 128);
  g.addColorStop(0, 'rgba(0,0,0,0.92)');
  g.addColorStop(0.6, 'rgba(0,0,0,0.55)');
  g.addColorStop(1, 'rgba(0,0,0,0)');
  ctx.fillStyle = g;
  ctx.fillRect(0, 0, 256, 256);
  return new THREE.CanvasTexture(c);
};

export const ConceptCar: React.FC<{state: DriveState; glassOpacity: number; inside: number; display: React.ReactNode}> = ({state, glassOpacity, inside, display}) => {
  const gltf = useGLTF(staticFile(MODEL));
  const rig = useMemo(() => buildRig(gltf.scene), [gltf.scene]);
  const blob = useMemo(blobTexture, []);
  const l = state.blinker === 'left' && state.blinkOn, r = state.blinker === 'right' && state.blinkOn;

  useLayoutEffect(() => {
    const spin = state.dist / WHEEL_R;
    const steerAngle = -state.steer * 0.45;
    for (const w of rig.wheels) {
      // in the model's own (z-up) frame: steer about z, roll about the axle (x)
      q1.setFromAxisAngle(Z, w.front ? steerAngle : 0);
      q2.setFromAxisAngle(X, spin);
      w.node.quaternion.copy(q1).multiply(q2);
      if (w.pad) w.pad.quaternion.copy(q2).invert().multiply(w.padBase); // the caliper does not roll with the wheel
    }
    if (rig.steering) {
      q1.setFromAxisAngle(Y, -state.steer * 1.9); // the column's own axis points at the driver: right = clockwise
      rig.steering.quaternion.copy(rig.steeringBase).multiply(q1);
    }
    if (rig.brakePedal) rig.brakePedal.position.y = rig.brakeY - 0.06 * state.brake;
    if (rig.accelPedal) rig.accelPedal.position.y = rig.accelY - 0.045 * state.throttle;
    rig.glass.opacity = glassOpacity;
    if (rig.brakeMat) rig.brakeMat.emissiveIntensity = 2.2 + state.brake * 16;
    rig.sigL.emissive.copy(AMBER);
    rig.sigR.emissive.copy(AMBER);
    rig.sigL.emissiveIntensity = l ? 14 : 0.25;
    rig.sigR.emissiveIntensity = r ? 14 : 0.25;
    // the headlight strips double as the front indicators
    rig.headL.emissive.copy(l ? AMBER : HEAD);
    rig.headR.emissive.copy(r ? AMBER : HEAD);
    rig.headL.emissiveIntensity = l ? 14 : 7;
    rig.headR.emissiveIntensity = r ? 14 : 7;
    if (rig.dash) rig.dash.emissiveIntensity = 0.8 + 0.8 * state.flow;
  }, [rig, state.dist, state.steer, state.brake, state.throttle, glassOpacity, l, r, state.flow]);

  const yaw = -state.steer * 0.05;
  const pitch = state.brake * 0.018 - state.throttle * 0.004;
  const roll = state.steer * 0.02;
  return (
    <group>
      <mesh rotation={[-Math.PI / 2, 0, 0]} position={[0, GROUND_Y + 0.012, -0.2]} renderOrder={1}>
        <planeGeometry args={[3.3, 6.2]} />
        <meshBasicMaterial map={blob} transparent depthWrite={false} toneMapped={false} />
      </mesh>
      <group position={[0, GROUND_Y - state.brake * 0.008, 0]} rotation={[pitch, yaw, roll]}>
        {/* the model's nose points at +z; the film's car drives toward -z */}
        <group rotation={[0, Math.PI, 0]}>
          <primitive object={rig.root} />
        </group>
        {display}
        {/* indicator tell-tales above the cluster */}
        {([-1, 1] as const).map((s) => (
          <mesh key={s} position={[s * 0.115, 0.835, -1.235]} rotation={[-0.35, 0, s < 0 ? Math.PI : 0]}>
            <circleGeometry args={[0.012, 3]} />
            <meshBasicMaterial color={(s < 0 ? l : r) ? '#FFB02E' : '#2A2010'} toneMapped={false} />
          </mesh>
        ))}
        {/* cabin: a low glow in the flow colour from the dash, and a soft key so the wheel and hands-area read */}
        <pointLight position={[0, 0.72, -1.05]} intensity={0.5 + 0.5 * state.flow} distance={1.6} decay={2} color={flowColor(state.flow)} />
        <pointLight position={[0, 1.02, -0.2]} intensity={0.25 + 0.35 * inside} distance={1.8} decay={2} color="#C8D6F4" />
        {/* tail: the brake flare on the road behind */}
        <TailGlow brake={state.brake} />
      </group>
      {/* headlights on the road ahead (outside the pitching group so the pools stay calm) */}
      {([-0.72, 0.72] as const).map((x) => (
        <HeadBeam key={x} x={x} />
      ))}
    </group>
  );
};

/** The red wash the tail lights throw on the road behind (a spot aimed back and down, so it never lights the car). */
const TailGlow: React.FC<{brake: number}> = ({brake}) => {
  const target = useMemo(() => {
    const o = new THREE.Object3D();
    o.position.set(0, 0, 7);
    return o;
  }, []);
  return (
    <>
      <primitive object={target} />
      <spotLight position={[0, 0.85, 2.0]} target={target} angle={0.95} penumbra={1} intensity={1.5 + brake * 22} distance={12} decay={1.8} color="#FF2A35" />
    </>
  );
};

const HeadBeam: React.FC<{x: number}> = ({x}) => {
  const target = useMemo(() => {
    const o = new THREE.Object3D();
    o.position.set(x * 1.2, -0.4, -22);
    return o;
  }, [x]);
  return (
    <>
      <primitive object={target} />
      <spotLight position={[x, 0.66, -2.2]} target={target} angle={0.42} penumbra={0.75} intensity={26} distance={60} decay={1.6} color="#DDE8FF" />
    </>
  );
};

useGLTF.preload(staticFile(MODEL));
