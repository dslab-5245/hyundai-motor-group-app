// Throwaway spike: 8 seconds of the 3D cockpit with a scripted brake stab, to judge the look before the real director
// drives it from the scenario signals. Also previews the new typography (deep navy / ice, light weights, grid).
import React, {useEffect, useState} from 'react';
import {AbsoluteFill, continueRender, delayRender, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {ThreeCanvas} from '@remotion/three';
import * as THREE from 'three';
import {CAM, mixCam} from './camera';
import {DisplayImage, type DriveState} from './Cockpit';
import {Scene} from './Scene';
import {ensureFonts} from '../fonts';

const FONT = "'Pretendard', 'Noto Sans KR', 'Malgun Gothic', sans-serif";
const smooth = (a: number, b: number, x: number) => {
  const k = Math.min(1, Math.max(0, (x - a) / (b - a)));
  return k * k * (3 - 2 * k);
};

export const CockpitSpike: React.FC = () => {
  const frame = useCurrentFrame();
  const {width, height, fps} = useVideoConfig();
  const t = frame / fps;
  const [handle] = useState(() => delayRender('fonts'));
  useEffect(() => {
    ensureFonts().then(() => continueRender(handle));
  }, [handle]);

  // scripted signals: gentle steering, blinker right 1.5-3.5 s, brake stab at 4.0 s, flow dips then recovers
  const stab = t > 4.0 && t < 4.7 ? Math.sin(((t - 4.0) / 0.7) * Math.PI) : 0;
  const steer = 0.1 * Math.sin(t * 0.9) + (t > 3.9 && t < 4.5 ? 0.55 * Math.sin(((t - 3.9) / 0.6) * Math.PI) : 0);
  const blinker: DriveState['blinker'] = t > 1.5 && t < 3.5 ? 'right' : 'off';
  const blinkOn = Math.floor(t * 2.6) % 2 === 0;
  const flow = t < 4.1 ? 0.96 : Math.min(0.96, 0.12 + (t - 4.4) * 0.11);
  const speed = 18 - stab * 6;
  const state: DriveState = {steer, brake: stab, throttle: t < 4 ? 0.35 : 0.1, blinker, blinkOn, flow: Math.max(0.1, flow), pulse: stab * 0.8, speed, dist: speed * t, t, wiper: 0, rain: 0};
  // camera: wide, dips to the pedal around the stab, back to wide
  const k = smooth(3.5, 4.0, t) * (1 - smooth(5.2, 6.4, t));
  const cam = mixCam(CAM.wide, CAM.pedal, k);
  const panel = staticFile(t > 4.2 && t < 7 ? 'run/panel_still_stumble_pane.png' : 'run/panel_still_flow_pane.png');

  return (
    <AbsoluteFill style={{background: '#060911'}}>
      <ThreeCanvas
        width={width}
        height={height}
        shadows
        dpr={1}
        gl={{antialias: true, toneMapping: THREE.NoToneMapping, outputColorSpace: THREE.SRGBColorSpace, powerPreference: 'high-performance'}}
        camera={{fov: 50, near: 0.04, far: 500, position: [-0.14, 1.19, 0.5]}}
      >
        <Scene state={state} cam={cam} display={<DisplayImage src={panel} glow={state.flow} />} />
      </ThreeCanvas>
      {/* typography preview: kicker + title top-left, a status line bottom-left, nothing overlaps the display */}
      <div style={{position: 'absolute', left: 96, top: 84, fontFamily: FONT, color: '#C9D6F2'}}>
        <div style={{fontSize: 18, letterSpacing: 6, fontWeight: 500, opacity: 0.7}}>DRIVE SYMPHONY · PLEOS CONNECT</div>
        <div style={{fontSize: 64, fontWeight: 400, letterSpacing: -1, marginTop: 14, lineHeight: 1.1}}>드라이브 심포니</div>
      </div>
      <div style={{position: 'absolute', left: 96, bottom: 96, fontFamily: FONT, color: '#C9D6F2'}}>
        <div style={{width: 42, height: 2, background: '#8FB3FF', marginBottom: 18, opacity: 0.9}} />
        <div style={{fontSize: 30, fontWeight: 400, lineHeight: 1.35}}>{stab > 0 || (t > 4 && t < 7) ? '급제동 · 음악이 잠시 가라앉습니다' : '부드럽게 달릴수록, 레이어가 하나씩 열립니다'}</div>
        <div style={{fontSize: 18, fontWeight: 400, opacity: 0.55, marginTop: 10, letterSpacing: 1}}>FLOW {Math.round(state.flow * 100)} · BRAKE {Math.round(stab * 100)}% · STEER {Math.round(steer * 100)}</div>
      </div>
    </AbsoluteFill>
  );
};
