import React from 'react';
import {interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import type {Callout, Shot} from '../plan';
import {layoutFor} from '../layout';
import {C, FONT} from '../theme';
import {amberFor} from './Panel';

const HOLD = 78; // frames a call-out stays unless a newer one replaces it

/** Event call-outs: appear on the exact event frame, pulse, and read in sync with the music's reaction. */
export const Callouts: React.FC<{items: Callout[]; shots: Shot[]; vertical: boolean}> = ({items, shots, vertical}) => {
  const f = useCurrentFrame();
  const {fps, width, height} = useVideoConfig();
  // latest call-out at or before f (within HOLD), and the shot it belongs to (for layout)
  let cur: Callout | null = null;
  for (const c of items) {
    if (c.start <= f && f < c.start + HOLD) cur = c;
    if (c.start > f) break;
  }
  if (!cur) return null;
  const shot = shots.find((s) => s.start <= f && f < s.start + s.dur);
  if (!shot) return null;
  const L = layoutFor(width, height, shot.crop);
  const age = f - cur.start;
  // how many of the same type in the last 1.5 s (repeats show a counter instead of re-reading)
  const same = items.filter((c) => c.type === cur!.type && c.graced === cur!.graced && c.start <= cur!.start && cur!.start - c.start <= 1.5 * fps).length;
  const col = amberFor(cur.tone);
  const s = spring({frame: age, fps, config: {damping: 15, stiffness: 220, mass: 0.7}});
  const pulse = interpolate(age, [0, 6, 14], [2.4, 1.25, 1], {extrapolateRight: 'clamp'});
  const out = interpolate(age, [HOLD - 10, HOLD], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const o = Math.min(1, age / 4) * out;
  const align = L.callout.align;
  const panelEdgeX = align === 'left' ? L.panel.left - 6 : null;
  const anchorY = L.panel.top + L.panel.height * 0.474; // the Flow ring height

  return (
    <div style={{position: 'absolute', left: L.callout.x, top: L.callout.y, width: L.callout.w, display: 'flex', justifyContent: align === 'right' ? 'flex-end' : align === 'center' ? 'center' : 'flex-start', opacity: o, pointerEvents: 'none'}}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 18, padding: vertical ? '18px 28px' : '16px 24px', borderRadius: 18,
        background: 'rgba(16,14,12,0.78)', border: `1px solid ${col}55`, boxShadow: `0 20px 60px rgba(0,0,0,0.5), 0 0 0 1px rgba(255,255,255,0.03)`,
        backdropFilter: 'blur(14px)', transform: `scale(${(0.9 + 0.1 * s).toFixed(4)}) translateY(${((1 - s) * 18).toFixed(1)}px)`, transformOrigin: align === 'right' ? '100% 50%' : '0 50%',
        fontFamily: FONT, maxWidth: L.callout.w,
      }}>
        <div style={{position: 'relative', width: 18, height: 18, flex: '0 0 auto'}}>
          <div style={{position: 'absolute', inset: 0, borderRadius: 9, background: col, transform: `scale(${pulse.toFixed(3)})`, opacity: 1 / pulse, boxShadow: `0 0 18px ${col}` }} />
          <div style={{position: 'absolute', inset: 4, borderRadius: 5, background: col}} />
        </div>
        <div style={{display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0}}>
          <div style={{display: 'flex', alignItems: 'baseline', gap: 12}}>
            <span style={{fontSize: vertical ? 40 : 34, fontWeight: 700, color: C.text, letterSpacing: -0.8, whiteSpace: 'nowrap'}}>{cur.label}</span>
            {same > 1 && <span style={{fontSize: vertical ? 28 : 24, fontWeight: 600, color: col, letterSpacing: 0}}>×{same}</span>}
          </div>
          <span style={{fontSize: vertical ? 27 : 23, fontWeight: 400, color: C.dim, letterSpacing: -0.3, whiteSpace: 'nowrap'}}>{cur.line}</span>
        </div>
      </div>
      {/* leader to the display (16:9 pane layout only) */}
      {panelEdgeX !== null && (
        <svg style={{position: 'absolute', left: 0, top: 0, overflow: 'visible'}} width={1} height={1}>
          <path d={`M ${L.callout.w * 0.55} 24 C ${L.callout.w * 0.8} 24, ${panelEdgeX - L.callout.x - 60} ${anchorY - L.callout.y}, ${panelEdgeX - L.callout.x} ${anchorY - L.callout.y}`}
            stroke={col} strokeWidth={1.5} fill="none" opacity={0.45 * Math.min(1, age / 8)} strokeDasharray="4 6" />
          <circle cx={panelEdgeX - L.callout.x} cy={anchorY - L.callout.y} r={4} fill={col} opacity={0.9} />
        </svg>
      )}
    </div>
  );
};
