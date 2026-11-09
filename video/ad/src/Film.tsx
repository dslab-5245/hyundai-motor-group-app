import React from 'react';
import {AbsoluteFill, useVideoConfig} from 'remotion';
import type {Plan} from './plan';
import {C, FONT} from './theme';
import {ensureFonts} from './fonts';
import {Backdrop} from './components/Backdrop';
import {Scene3D} from './components/Scene3D';
import {Panels} from './components/Panel';
import {Texts} from './components/Kinetic';
import {Callouts} from './components/Callouts';
import {Labels} from './components/Labels';
import {Cards} from './components/Cards';
import {Music, Voice} from './components/Sound';
import {Ticks} from './components/Ticks';
import {Grain} from './components/Grain';
import type {Signals} from './scene3d/signals';

ensureFonts();

export interface FilmProps {comp: string; plan: Plan | null; signals: Signals | null}

/** Renders one composition of plan.json: 3D scenes, flat app inserts, type in fixed zones, pills, cards, sound. */
export const Film: React.FC<FilmProps> = ({comp, plan, signals}) => {
  const {width, height} = useVideoConfig();
  if (!plan) return <AbsoluteFill style={{background: C.bg0}} />;
  const c = plan.compositions[comp];
  if (!c) return <AbsoluteFill style={{background: C.bg0}} />;
  const vertical = height > width;
  return (
    <AbsoluteFill style={{background: C.bg0, fontFamily: FONT, color: C.text, overflow: 'hidden'}}>
      <Backdrop />
      <Scene3D items={c.scene3d} plan={plan} signals={signals} vertical={vertical} />
      <Panels shots={c.panel} plan={plan} />
      <Texts items={c.text} />
      <Labels items={c.labels} comp={c} />
      <Callouts items={c.callouts} comp={c} />
      <Cards items={c.cards} plan={plan} />
      <Grain src={plan.noise} />
      <Music segs={c.music} voice={c.voice} plan={plan} />
      <Voice items={c.voice} plan={plan} />
      <Ticks frames={c.ticks} plan={plan} />
    </AbsoluteFill>
  );
};
