import React from 'react';
import {AbsoluteFill, useVideoConfig} from 'remotion';
import type {Plan} from './plan';
import {C, FONT} from './theme';
import {ensureFonts} from './fonts';
import {useMusicFeatures} from './audio';
import {layoutFor} from './layout';
import {Background} from './components/Background';
import {Panels} from './components/Panel';
import {Texts} from './components/Kinetic';
import {Callouts} from './components/Callouts';
import {Labels} from './components/Labels';
import {Cards} from './components/Cards';
import {Spectrum} from './components/Spectrum';
import {Music, Voice} from './components/Sound';
import {Grain} from './components/Grain';

ensureFonts();

export interface FilmProps {comp: string; plan: Plan | null}

/** Renders one composition of plan.json: background footage, the floating display, type, call-outs, cards, sound. */
export const Film: React.FC<FilmProps> = ({comp, plan}) => {
  const {width, height} = useVideoConfig();
  if (!plan) return <AbsoluteFill style={{background: C.bg0}} />;
  const c = plan.compositions[comp];
  if (!c) return <AbsoluteFill style={{background: C.bg0}} />;
  const vertical = height > width;
  const crop = c.panel[0]?.crop ?? 'pane';
  const L = layoutFor(width, height, crop);
  return (
    <AbsoluteFill style={{background: C.bg0, fontFamily: FONT, color: C.text, overflow: 'hidden'}}>
      <Inner c={c} plan={plan} vertical={vertical} spectrumY={L.spectrum.y} spectrumH={L.spectrum.h} />
    </AbsoluteFill>
  );
};

const Inner: React.FC<{c: Plan['compositions'][string]; plan: Plan; vertical: boolean; spectrumY: number; spectrumH: number}> = ({c, plan, vertical, spectrumY, spectrumH}) => {
  const features = useMusicFeatures(c.music, plan);
  return (
    <>
      <Background items={c.background} plan={plan} />
      <Panels shots={c.panel} plan={plan} features={features} vertical={vertical} />
      <Spectrum features={features} y={spectrumY} h={spectrumH} opacity={vertical ? 0.55 : 0.5} />
      <Texts items={c.text} vertical={vertical} />
      <Labels items={c.labels} shots={c.panel} />
      <Callouts items={c.callouts} shots={c.panel} vertical={vertical} />
      <Cards items={c.cards} plan={plan} features={features} vertical={vertical} />
      <Grain src={plan.noise} />
      <Music segs={c.music} voice={c.voice} plan={plan} />
      <Voice items={c.voice} plan={plan} />
    </>
  );
};
