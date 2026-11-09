import React from 'react';
import {Audio, Sequence, staticFile, useVideoConfig} from 'remotion';
import type {MusicSeg, Plan, VoiceItem} from '../plan';

const DUCK = 0.5; // -6 dB under speech, never muted
const RAMP = 8; // frames

/** 1 when no narration is near frame f, DUCK while a line is speaking, linear ramps in between. */
const duckAt = (f: number, voice: VoiceItem[]): number => {
  let g = 1;
  for (const v of voice) {
    const a = v.start - RAMP, b = v.start + v.dur + RAMP;
    if (f < a || f > b) continue;
    let k = 1;
    if (f < v.start) k = (f - a) / RAMP;
    else if (f > v.start + v.dur) k = (b - f) / RAMP;
    g = Math.min(g, 1 - (1 - DUCK) * k);
  }
  return g;
};

export const Music: React.FC<{segs: MusicSeg[]; voice: VoiceItem[]; plan: Plan}> = ({segs, voice, plan}) => {
  const {fps} = useVideoConfig();
  return (
    <>
      {segs.map((m, i) => {
        const src = plan.sources[m.src];
        if (!src || !src.audio) return null;
        const base = Math.pow(10, (src.gain_db || 0) / 20) * (m.gain || 1);
        return (
          <Sequence key={i} from={m.start} durationInFrames={m.dur} layout="none">
            <Audio
              src={staticFile(src.audio)}
              startFrom={Math.round(m.offset * fps)}
              volume={(f) => {
                let env = 1;
                if (m.fadeIn > 0 && f < m.fadeIn) env = f / m.fadeIn;
                if (m.fadeOut > 0 && m.dur - f < m.fadeOut) env = Math.min(env, Math.max(0, (m.dur - f) / m.fadeOut));
                return Math.max(0, Math.min(1, base * env * duckAt(m.start + f, voice)));
              }}
            />
          </Sequence>
        );
      })}
    </>
  );
};

export const Voice: React.FC<{items: VoiceItem[]; plan: Plan}> = ({items, plan}) => (
  <>
    {items.map((v, i) => {
      const n = plan.narration[v.id];
      if (!n) return null;
      return (
        <Sequence key={i} from={v.start} durationInFrames={v.dur + 4} layout="none">
          <Audio src={staticFile(n.file)} volume={1} />
        </Sequence>
      );
    })}
  </>
);
