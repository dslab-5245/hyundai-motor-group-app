// Throwaway spike: 10 s of scripted driving with a camera move that orbits the car, dives to the pedal on a brake
// stab and pulls back behind the car, to judge the exterior look and the exterior<->interior transitions.
import React, {useEffect, useState} from 'react';
import {AbsoluteFill, continueRender, delayRender, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {ThreeCanvas} from '@remotion/three';
import * as THREE from 'three';
import {CAM, camPath, type CamKey} from './camera';
import {DisplayImage, type DriveState} from './Cockpit';
import {Scene} from './Scene';
import {ensureFonts} from '../fonts';

const FONT = "'Pretendard', 'Noto Sans KR', 'Malgun Gothic', sans-serif";

const KEYS: CamKey[] = [
  {at: 0.0, cam: CAM.side, hold: true},
  {at: 1.2, cam: CAM.side},
  {at: 3.2, cam: CAM.frontQR, orbit: true},
  {at: 4.6, cam: CAM.lowFront, orbit: true, hold: true},
  {at: 5.0, cam: CAM.pedal, cut: true, hold: true},
  {at: 6.2, cam: CAM.rearLow, cut: true},
  {at: 8.2, cam: CAM.follow},
  {at: 10.0, cam: CAM.top},
];

export const ExteriorSpike: React.FC = () => {
  const frame = useCurrentFrame();
  const {width, height, fps} = useVideoConfig();
  const t = frame / fps;
  const [handle] = useState(() => delayRender('fonts'));
  useEffect(() => {
    ensureFonts().then(() => continueRender(handle));
  }, [handle]);

  const stab = t > 5.0 && t < 5.7 ? Math.sin(((t - 5.0) / 0.7) * Math.PI) : t > 6.4 && t < 7.0 ? Math.sin(((t - 6.4) / 0.6) * Math.PI) : 0;
  const steer = 0.08 * Math.sin(t * 0.9) + (t > 4.4 && t < 5.2 ? 0.6 * Math.sin(((t - 4.4) / 0.8) * Math.PI) : 0);
  const blinker: DriveState['blinker'] = t > 1.0 && t < 3.4 ? 'right' : 'off';
  const blinkOn = Math.floor(t * 1.5) % 2 === 0;
  const flow = t < 5.1 ? 0.96 : Math.max(0.12, Math.min(0.96, 0.12 + (t - 5.4) * 0.11));
  const speed = 16 - stab * 5;
  const pulse = stab > 0 ? stab * 0.8 : 0;
  const state: DriveState = {steer, brake: stab, throttle: t < 5 ? 0.35 : 0.1, blinker, blinkOn, flow, pulse, speed, dist: 16 * t, t, wiper: t > 8.0 ? 1 : 0, rain: t > 8.0 ? 1 : 0};
  const cam = camPath(KEYS, t);
  const panel = staticFile(t > 5.2 && t < 8.5 ? 'run/panel_still_stumble_pane.png' : 'run/panel_still_flow_pane.png');

  return (
    <AbsoluteFill style={{background: '#060911'}}>
      <ThreeCanvas
        width={width}
        height={height}
        shadows
        dpr={1}
        gl={{antialias: true, toneMapping: THREE.NoToneMapping, outputColorSpace: THREE.SRGBColorSpace, powerPreference: 'high-performance'}}
        camera={{fov: 40, near: 0.04, far: 600, position: [5.6, 0.9, -0.2]}}
      >
        <Scene state={state} cam={cam} display={<DisplayImage src={panel} glow={flow} />} />
      </ThreeCanvas>
      <div style={{position: 'absolute', left: 96, top: 84, fontFamily: FONT, color: '#C9D6F2'}}>
        <div style={{fontSize: 18, letterSpacing: 6, fontWeight: 500, opacity: 0.7}}>DRIVE SYMPHONY · PLEOS CONNECT</div>
        <div style={{fontSize: 64, fontWeight: 400, letterSpacing: -1, marginTop: 14, lineHeight: 1.1}}>드라이브 심포니</div>
      </div>
      <div style={{position: 'absolute', left: 96, bottom: 96, fontFamily: FONT, color: '#C9D6F2'}}>
        <div style={{width: 42, height: 2, background: '#8FB3FF', marginBottom: 18, opacity: 0.9}} />
        <div style={{fontSize: 30, fontWeight: 400, lineHeight: 1.35}}>{t > 5 && t < 8 ? '급제동 · 음악이 잠시 가라앉습니다' : '부드럽게 달릴수록, 레이어가 하나씩 열립니다'}</div>
        <div style={{fontSize: 18, fontWeight: 400, opacity: 0.55, marginTop: 10, letterSpacing: 1}}>
          FLOW {Math.round(flow * 100)} · BRAKE {Math.round(stab * 100)}% · STEER {Math.round(steer * 100)} · CAM {cam.pos.map((v) => v.toFixed(1)).join(' ')}
        </div>
      </div>
    </AbsoluteFill>
  );
};
