// The 3D layer of a composition: for each scene3d item of the plan, a ThreeCanvas with the car, the world and the
// cabin driven by the scenario signals at (clip time − scenario offset), the display showing the real recording at
// the same clip time, and the camera following the item's keyframes. Only one item is mounted at a time.
import React, {useMemo} from 'react';
import {AbsoluteFill, Sequence, getRemotionEnvironment, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import {ThreeCanvas, useOffthreadVideoTexture} from '@remotion/three';
import * as THREE from 'three';
import type {Plan, Scene3DItem} from '../plan';
import {PANE} from '../layout';
import {CAM, camPath, type CamKey, type CamState} from '../scene3d/camera';
import {DisplayImage, DisplayPanel} from '../scene3d/Cockpit';
import {Scene} from '../scene3d/Scene';
import {stateAt, type Signals} from '../scene3d/signals';

const DisplayVideo: React.FC<{src: string; glow: number}> = ({src, glow}) => {
  const tex = useOffthreadVideoTexture({src, toneMapped: false});
  useMemo(() => {
    if (!tex) return;
    tex.colorSpace = THREE.SRGBColorSpace;
    tex.wrapS = tex.wrapT = THREE.ClampToEdgeWrapping;
    tex.repeat.set(PANE.w / 1920, PANE.h / 1080);
    tex.offset.set(PANE.x / 1920, 1 - (PANE.y + PANE.h) / 1080);
    tex.anisotropy = 8;
    tex.needsUpdate = true;
  }, [tex]);
  if (!tex) return null;
  return <DisplayPanel tex={tex} glow={glow} />;
};

const toKeys = (item: Scene3DItem): CamKey[] =>
  item.keys.map((k) => ({at: k.at, cam: k.cam in CAM ? CAM[k.cam] : CAM.wide, cut: k.cut, hold: k.hold, orbit: k.orbit}));

const Item: React.FC<{item: Scene3DItem; plan: Plan; signals: Signals; vertical: boolean}> = ({item, plan, signals, vertical}) => {
  const f = useCurrentFrame();
  const {fps, width, height} = useVideoConfig();
  const t = f / fps;
  const src = item.src ? plan.sources[item.src] : null;
  const clipT = item.offset + t;
  const scenarioT = clipT - (src?.scenario_offset ?? 1.0);
  const base = stateAt(signals, scenarioT);
  const state = item.rain !== undefined && item.rain !== null ? {...base, rain: item.rain, wiper: item.rain} : base;
  const keys = useMemo(() => toKeys(item), [item]);
  const c0 = camPath(keys, t);
  const cam: CamState = vertical ? {...c0, fov: Math.min(95, c0.fov * 1.38)} : c0;
  const fadeIn = interpolate(f, [0, 8], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const fadeOut = interpolate(f, [item.dur - 10, item.dur], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const {isRendering} = getRemotionEnvironment();
  const still = staticFile(state.flow < 0.5 ? 'run/panel_still_stumble_pane.png' : 'run/panel_still_flow_pane.png');
  const display = src && isRendering ? <DisplayVideo src={staticFile(src.video)} glow={state.flow} /> : <DisplayImage src={still} glow={state.flow} />;
  return (
    <AbsoluteFill style={{opacity: fadeIn * fadeOut, background: '#05080F'}}>
      <Sequence from={-Math.round(item.offset * fps)} layout="none">
        <ThreeCanvas
          width={width}
          height={height}
          shadows
          dpr={1}
          gl={{antialias: true, toneMapping: THREE.NoToneMapping, outputColorSpace: THREE.SRGBColorSpace, powerPreference: 'high-performance'}}
          camera={{fov: cam.fov, near: 0.04, far: 600, position: cam.pos}}
        >
          <Scene state={state} cam={cam} display={display} />
        </ThreeCanvas>
      </Sequence>
    </AbsoluteFill>
  );
};

export const Scene3D: React.FC<{items: Scene3DItem[]; plan: Plan; signals: Signals | null; vertical: boolean}> = ({items, plan, signals, vertical}) => {
  if (!signals) return null;
  return (
    <>
      {items.map((it, i) => (
        <Sequence key={i} from={it.start} durationInFrames={it.dur} layout="none">
          <Item item={it} plan={plan} signals={signals} vertical={vertical} />
        </Sequence>
      ))}
    </>
  );
};
