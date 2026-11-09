import React from 'react';
import {Sequence, interpolate, useCurrentFrame, useVideoConfig} from 'remotion';
import type {CompPlan, LabelItem} from '../plan';
import {modeAt} from '../plan';
import {layoutFor, zoneStyle} from '../layout';
import {C, FONT} from '../theme';

const One: React.FC<{item: LabelItem; comp: CompPlan}> = ({item, comp}) => {
  const f = useCurrentFrame();
  const {width, height} = useVideoConfig();
  const L = layoutFor(width, height);
  const mode = modeAt(comp, item.start + f);
  const zone = mode === 'full' ? L.fullLabel : mode === 'insert' ? L.insertLabel : L.label;
  const inP = interpolate(f, [0, 10], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const outP = interpolate(f, [item.dur - 8, item.dur], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const style = zoneStyle(zone);
  return (
    <div style={{...style, opacity: inP * outP, transform: `translateY(${((1 - inP) * 8).toFixed(1)}px)`, fontFamily: FONT, gap: 6}}>
      <div style={{display: 'flex', alignItems: 'center', gap: 12, flexDirection: zone.align === 'right' ? 'row-reverse' : 'row'}}>
        <div style={{width: 8, height: 8, borderRadius: 4, background: C.accent, boxShadow: `0 0 12px ${C.accent}`}} />
        <span style={{fontSize: 26, fontWeight: 500, color: C.text, letterSpacing: -0.4}}>{item.label}</span>
      </div>
      {item.line && <div style={{fontSize: 21, fontWeight: 400, color: C.dim, letterSpacing: -0.2, lineHeight: 1.35, maxWidth: zone.w}}>{item.line}</div>}
    </div>
  );
};

/** Phase / cue labels: what the music is doing right now, in a fixed zone, never on the picture. */
export const Labels: React.FC<{items: LabelItem[]; comp: CompPlan}> = ({items, comp}) => (
  <>
    {items.map((it, i) => (
      <Sequence key={i} from={it.start} durationInFrames={it.dur} layout="none">
        <One item={it} comp={comp} />
      </Sequence>
    ))}
  </>
);
