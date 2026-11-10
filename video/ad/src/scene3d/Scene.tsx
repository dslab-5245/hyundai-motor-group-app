// One frame of the 3D film: camera, lighting, the car (the concept coupe of CarModel.tsx), the world and the film look (bloom, slight
// aberration that grows with the penalty pulse, vignette, grain, ACES). Rendered inside a ThreeCanvas by the Film's
// Scene3D layer and by the spikes.
import React, {useLayoutEffect, useMemo, useRef} from 'react';
import {useThree} from '@react-three/fiber';
import * as THREE from 'three';
import {Environment} from '@react-three/drei';
import {Bloom, ChromaticAberration, EffectComposer, Noise, ToneMapping, Vignette} from '@react-three/postprocessing';
import {ToneMappingMode} from 'postprocessing';
import {staticFile} from 'remotion';
import {RectAreaLightUniformsLib} from 'three/examples/jsm/lights/RectAreaLightUniformsLib.js';
import {type CamState, insideness} from './camera';
import {ConceptCar} from './CarModel';
import type {DriveState} from './Cockpit';
import {World} from './World';

const Camera: React.FC<{cam: CamState; shake: number; t: number}> = ({cam, shake, t}) => {
  const {camera, gl, invalidate} = useThree();
  useLayoutEffect(() => {
    gl.localClippingEnabled = true; // the left/right split of the indicator and headlight strips
    const c = camera as THREE.PerspectiveCamera;
    // a little hand-held life on the penalty pulse: two incommensurate sines, a few millimetres
    const sx = shake * 0.006 * Math.sin(t * 31.0), sy = shake * 0.004 * Math.sin(t * 23.7 + 1.0);
    c.position.set(cam.pos[0] + sx, cam.pos[1] + sy, cam.pos[2]);
    c.lookAt(cam.look[0] + sx * 0.5, cam.look[1] + sy * 0.5, cam.look[2]);
    c.fov = cam.fov;
    c.near = 0.04;
    c.far = 600;
    c.updateProjectionMatrix();
    invalidate();
  }, [camera, gl, invalidate, cam.pos[0], cam.pos[1], cam.pos[2], cam.look[0], cam.look[1], cam.look[2], cam.fov, shake, t]);
  return null;
};

/** A soft studio panel: the long highlight streaks car films live on. */
const AreaLight: React.FC<{pos: [number, number, number]; at: [number, number, number]; w: number; h: number; intensity: number; color?: string}> = ({pos, at, w, h, intensity, color = '#BFD3FF'}) => {
  const ref = useRef<THREE.RectAreaLight>(null);
  useLayoutEffect(() => {
    ref.current?.lookAt(at[0], at[1], at[2]);
  }, [at[0], at[1], at[2]]);
  return <rectAreaLight ref={ref} position={pos} args={[color, intensity, w, h]} />;
};

let areaInit = false;

export const Scene: React.FC<{state: DriveState; cam: CamState; display: React.ReactNode; hdri?: string}> = ({state, cam, display, hdri}) => {
  if (!areaInit) {
    RectAreaLightUniformsLib.init();
    areaInit = true;
  }
  const inside = insideness(cam.pos);
  const glass = 0.86 * (1 - inside);
  const studio = 1 - 0.85 * inside; // the area panels are for the exterior; inside they would flat-light the dash
  const abr = useMemo(() => new THREE.Vector2(0.0004 + state.pulse * 0.0016, 0.0004 + state.pulse * 0.0016), [state.pulse]);
  return (
    <>
      <Camera cam={cam} shake={state.pulse} t={state.t} />
      <color attach="background" args={['#05080F']} />
      <fog attach="fog" args={['#070B16', 14, 240]} />
      <Environment files={hdri ?? staticFile('hdri/moonless_golf_1k.hdr')} environmentIntensity={2.2} />
      <ambientLight intensity={0.08} color="#9FB4E0" />
      <directionalLight
        position={[3.0, 6.0, -5.0]}
        intensity={1.1 * (1 - 0.55 * inside)}
        color="#A9C6FF"
        castShadow
        shadow-mapSize={[2048, 2048]}
        shadow-camera-near={0.5}
        shadow-camera-far={40}
        shadow-camera-left={-8}
        shadow-camera-right={8}
        shadow-camera-top={8}
        shadow-camera-bottom={-8}
        shadow-bias={-0.0006}
      />
      <directionalLight position={[-4.0, 3.0, 6.0]} intensity={0.5 * (1 - 0.5 * inside)} color="#5F76A8" />
      <directionalLight position={[6.0, 2.0, 3.0]} intensity={0.35 * (1 - 0.5 * inside)} color="#8FB3FF" />
      <AreaLight pos={[0, 5.5, -0.8]} at={[0, 0, -0.8]} w={7} h={1.4} intensity={9 * studio} />
      <AreaLight pos={[-6.5, 1.7, 0.2]} at={[0, 0.6, 0.2]} w={6} h={0.7} intensity={6 * studio} />
      <AreaLight pos={[6.5, 1.7, 0.2]} at={[0, 0.6, 0.2]} w={6} h={0.7} intensity={6 * studio} />
      <ConceptCar state={state} glassOpacity={glass} inside={inside} display={display} />
      <World dist={state.dist} t={state.t} rain={state.rain} />
      <EffectComposer multisampling={4}>
        <Bloom luminanceThreshold={0.92} intensity={0.55} mipmapBlur radius={0.55} />
        <ChromaticAberration offset={abr} radialModulation modulationOffset={0.35} />
        <Vignette eskil={false} offset={0.2} darkness={0.7} />
        <Noise opacity={0.028} />
        <ToneMapping mode={ToneMappingMode.ACES_FILMIC} />
      </EffectComposer>
    </>
  );
};
