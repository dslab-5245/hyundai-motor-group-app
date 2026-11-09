import React from 'react';
import {AbsoluteFill, useCurrentFrame} from 'remotion';
import {C} from '../theme';

/** Behind everything: deep navy with a slow, faint light sweep and a vignette. Never footage. */
export const Backdrop: React.FC = () => {
  const f = useCurrentFrame();
  const sweep = ((f / 900) % 1) * 160 - 30; // one pass every 30 s
  return (
    <AbsoluteFill style={{background: `radial-gradient(ellipse 90% 70% at 50% 40%, ${C.bg1} 0%, ${C.bg0} 72%)`}}>
      <AbsoluteFill style={{background: `linear-gradient(115deg, rgba(143,179,255,0) ${sweep - 18}%, rgba(143,179,255,0.045) ${sweep}%, rgba(143,179,255,0) ${sweep + 18}%)`}} />
      <AbsoluteFill style={{background: 'radial-gradient(ellipse 75% 65% at 50% 50%, rgba(0,0,0,0) 45%, rgba(3,5,10,0.6) 100%)'}} />
    </AbsoluteFill>
  );
};
