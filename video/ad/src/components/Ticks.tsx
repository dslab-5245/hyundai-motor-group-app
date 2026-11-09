import React from 'react';
import {Audio, Sequence, staticFile} from 'remotion';
import type {Plan} from '../plan';

const GAIN = 0.063; // −24 dB

/** The turn-signal relay: one soft click per blink phase while a 3D scene shows the signal on. */
export const Ticks: React.FC<{frames: number[]; plan: Plan}> = ({frames, plan}) => {
  if (!plan.tick) return null;
  return (
    <>
      {frames.map((f, i) => (
        <Sequence key={i} from={f} durationInFrames={8} layout="none">
          <Audio src={staticFile(plan.tick as string)} volume={GAIN * (i % 2 === 0 ? 1 : 0.8)} />
        </Sequence>
      ))}
    </>
  );
};
