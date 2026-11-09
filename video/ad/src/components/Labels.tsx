import React from 'react';
import {Sequence, interpolate, useCurrentFrame, useVideoConfig} from 'remotion';
import type {LabelItem, Shot} from '../plan';
import {layoutFor} from '../layout';
import {C} from '../theme';

const One: React.FC<{item: LabelItem; crop: 'pane' | 'full'}> = ({item, crop}) => {
  const f = useCurrentFrame();
  const {width, height} = useVideoConfig();
  const L = layoutFor(width, height, crop);
  const inP = interpolate(f, [0, 10], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const outP = interpolate(f, [item.dur - 8, item.dur], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const o = inP * outP;
  return (
    <div style={{position: 'absolute', left: L.label.x, top: L.label.y, width: L.label.w, opacity: o, transform: `translateY(${((1 - inP) * 12).toFixed(1)}px)`}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 14}}>
        <div style={{width: 10, height: 10, borderRadius: 5, background: C.amberHi, boxShadow: `0 0 14px ${C.amberHi}`}} />
        <span style={{fontSize: 30, fontWeight: 700, color: C.amberHi, letterSpacing: -0.5}}>{item.label}</span>
      </div>
      {item.line && <div style={{marginTop: 6, marginLeft: 24, fontSize: 25, fontWeight: 400, color: C.dim, letterSpacing: -0.3, whiteSpace: 'nowrap'}}>{item.line}</div>}
    </div>
  );
};

/** Phase / cue labels for the full demo: what the music is doing right now, in the design language, not subtitles. */
export const Labels: React.FC<{items: LabelItem[]; shots: Shot[]}> = ({items, shots}) => (
  <>
    {items.map((it, i) => {
      const shot = shots.find((s) => s.start <= it.start && it.start < s.start + s.dur);
      return (
        <Sequence key={i} from={it.start} durationInFrames={it.dur} layout="none">
          <One item={it} crop={shot?.crop ?? 'full'} />
        </Sequence>
      );
    })}
  </>
);
