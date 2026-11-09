import React from 'react';
import {interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import type {Callout, CompPlan} from '../plan';
import {modeAt} from '../plan';
import {layoutFor, zoneStyle} from '../layout';
import {C, FONT, toneColor} from '../theme';

const HOLD = 96; // frames a pill stays unless a newer one replaces it

/** Event pills in a fixed zone: the newest on the event frame, the previous one fading above it. No leader lines. */
export const Callouts: React.FC<{items: Callout[]; comp: CompPlan}> = ({items, comp}) => {
  const f = useCurrentFrame();
  const {fps, width, height} = useVideoConfig();
  const vertical = height > width;
  const mode = modeAt(comp, f);
  if (mode === 'none') return null;
  const L = layoutFor(width, height);
  const zone = mode === 'insert' ? L.insertCallout : mode === 'full' ? L.fullCallout : L.callout;
  const live = items.filter((c) => c.start <= f && f < c.start + HOLD).slice(-2);
  if (live.length === 0) return null;
  const style = zoneStyle(zone);
  return (
    <div style={{...style, gap: 10, pointerEvents: 'none', fontFamily: FONT}}>
      {live.map((cur, idx) => {
        const newest = idx === live.length - 1;
        const age = f - cur.start;
        const same = items.filter((c) => c.type === cur.type && c.graced === cur.graced && c.start <= cur.start && cur.start - c.start <= 1.5 * fps).length;
        const col = toneColor(cur.tone);
        const s = spring({frame: age, fps, config: {damping: 18, stiffness: 190, mass: 0.7}});
        const out = interpolate(age, [HOLD - 12, HOLD], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
        const o = Math.min(1, age / 4) * out * (newest ? 1 : 0.45);
        const pulse = interpolate(age, [0, 6, 16], [2.2, 1.2, 1], {extrapolateRight: 'clamp'});
        return (
          <div
            key={`${cur.start}${cur.type}`}
            style={{
              display: 'flex', alignItems: 'center', gap: 16, padding: vertical ? '16px 24px' : '14px 22px', borderRadius: 16,
              background: C.pill, border: `1px solid ${col}44`, boxShadow: '0 18px 50px rgba(0,0,0,0.45)', backdropFilter: 'blur(12px)',
              opacity: o, transform: `translateY(${((1 - s) * 14).toFixed(1)}px)`, maxWidth: zone.w,
            }}
          >
            <div style={{position: 'relative', width: 14, height: 14, flex: '0 0 auto'}}>
              <div style={{position: 'absolute', inset: 0, borderRadius: 7, background: col, transform: `scale(${pulse.toFixed(3)})`, opacity: 1 / pulse, boxShadow: `0 0 14px ${col}`}} />
              <div style={{position: 'absolute', inset: 3, borderRadius: 4, background: col}} />
            </div>
            <div style={{display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0}}>
              <div style={{display: 'flex', alignItems: 'baseline', gap: 10}}>
                <span style={{fontSize: vertical ? 32 : 28, fontWeight: 500, color: C.text, letterSpacing: -0.6, whiteSpace: 'nowrap'}}>{cur.label}</span>
                {same > 1 && <span style={{fontSize: vertical ? 24 : 21, fontWeight: 500, color: col}}>×{same}</span>}
              </div>
              <span style={{fontSize: vertical ? 24 : 21, fontWeight: 400, color: C.dim, letterSpacing: -0.2, lineHeight: 1.3}}>{cur.line}</span>
            </div>
          </div>
        );
      })}
    </div>
  );
};
