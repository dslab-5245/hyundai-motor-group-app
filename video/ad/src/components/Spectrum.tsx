import React from 'react';
import {useVideoConfig} from 'remotion';
import type {Features} from '../audio';
import {C} from '../theme';

/** A mirrored bar spectrum along the bottom edge, driven by the app's own music. */
export const Spectrum: React.FC<{features: Features; y: number; h: number; opacity?: number}> = ({features, y, h, opacity = 0.6}) => {
  const {width} = useVideoConfig();
  if (!features.active) return null;
  const n = features.bars.length;
  const margin = 120;
  const gap = 6;
  const bw = (width - margin * 2 - gap * (n - 1)) / n;
  return (
    <svg style={{position: 'absolute', left: 0, top: y, opacity}} width={width} height={h}>
      <defs>
        <linearGradient id="specg" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor={C.amberHi} stopOpacity="0.95" />
          <stop offset="1" stopColor={C.amber} stopOpacity="0.35" />
        </linearGradient>
      </defs>
      {features.bars.map((v, i) => {
        const bh = Math.max(2, v * h * 0.95);
        return <rect key={i} x={margin + i * (bw + gap)} y={h / 2 - bh / 2} width={bw} height={bh} rx={bw / 2} fill="url(#specg)" />;
      })}
    </svg>
  );
};
