// Review sheet: frame N shows camera preset N (in the order of CAM) with a fixed, telling drive state, so every
// preset can be judged from one still each. Not part of the film.
import React from 'react';
import {AbsoluteFill, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {ThreeCanvas} from '@remotion/three';
import * as THREE from 'three';
import {CAM} from './camera';
import {DisplayImage, type DriveState} from './Cockpit';
import {Scene} from './Scene';

export const PRESET_NAMES = Object.keys(CAM);

export const PresetSpike: React.FC = () => {
  const frame = useCurrentFrame();
  const {width, height} = useVideoConfig();
  const name = PRESET_NAMES[frame % PRESET_NAMES.length];
  const alt = Math.floor(frame / PRESET_NAMES.length) % 2 === 1; // second pass: braking, steering, left indicator
  const state: DriveState = alt
    ? {steer: 0.6, brake: 0.9, throttle: 0, blinker: 'left', blinkOn: true, flow: 0.2, pulse: 0.6, speed: 9, dist: 137.3, t: 3.1, wiper: 0, rain: 1}
    : {steer: 0.05, brake: 0, throttle: 0.4, blinker: 'right', blinkOn: true, flow: 0.95, pulse: 0, speed: 16, dist: 52.7, t: 1.3, wiper: 0, rain: 0};
  return (
    <AbsoluteFill style={{background: '#060911'}}>
      <ThreeCanvas width={width} height={height} shadows dpr={1} gl={{antialias: true, toneMapping: THREE.NoToneMapping, outputColorSpace: THREE.SRGBColorSpace, powerPreference: 'high-performance'}} camera={{fov: 40, near: 0.04, far: 600, position: [5.6, 0.9, -0.2]}}>
        <Scene state={state} cam={CAM[name]} display={<DisplayImage src={staticFile(alt ? 'run/panel_still_stumble_pane.png' : 'run/panel_still_flow_pane.png')} glow={state.flow} />} />
      </ThreeCanvas>
      <div style={{position: 'absolute', left: 24, top: 16, color: '#fff', fontSize: 28, fontFamily: 'monospace'}}>{name}{alt ? ' (brake)' : ''}</div>
    </AbsoluteFill>
  );
};
