import React from 'react';
import {AbsoluteFill, Sequence, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import type {CardItem, Plan} from '../plan';
import {C, FONT} from '../theme';

/** The Flow-ring motif: a thin 270° arc drawing itself. */
const Ring: React.FC<{size: number; p: number}> = ({size, p}) => {
  const r = size / 2 - 8;
  const circ = 2 * Math.PI * r;
  const arc = circ * 0.75;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{transform: 'rotate(135deg)'}}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="rgba(201,214,242,0.10)" strokeWidth={6} strokeDasharray={`${arc} ${circ}`} strokeLinecap="round" />
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={C.accent} strokeWidth={6} strokeDasharray={`${arc * p} ${circ}`} strokeLinecap="round" style={{filter: `drop-shadow(0 0 12px rgba(143,179,255,0.5))`}} />
    </svg>
  );
};

const Card: React.FC<{item: CardItem; plan: Plan}> = ({item, plan}) => {
  const f = useCurrentFrame();
  const {fps, width, height} = useVideoConfig();
  const vertical = height > width;
  const inP = interpolate(f, [0, 14], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const outP = interpolate(f, [item.dur - 14, item.dur], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const s = spring({frame: f, fps, config: {damping: 22, stiffness: 100, mass: 1}});
  const ringP = interpolate(f, [6, 54], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const ease = 1 - Math.pow(1 - ringP, 3);

  if (item.kind === 'chapter') {
    return (
      <AbsoluteFill style={{opacity: inP * outP, alignItems: 'center', justifyContent: 'center', fontFamily: FONT}}>
        <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', transform: `translateY(${((1 - s) * 24).toFixed(1)}px)`}}>
          <div style={{fontSize: 20, fontWeight: 500, color: C.muted, letterSpacing: 6}}>{item.kicker}</div>
          <div style={{marginTop: 20, fontSize: 76, fontWeight: 400, color: C.text, letterSpacing: -2.4}}>{item.title}</div>
          <div style={{marginTop: 24, height: 2, width: 56 * ease, background: C.accent}} />
          <div style={{marginTop: 26, fontSize: 28, fontWeight: 400, color: C.dim, letterSpacing: -0.4, textAlign: 'center', maxWidth: width - 240, lineHeight: 1.45}}>{item.line}</div>
        </div>
      </AbsoluteFill>
    );
  }
  const ringSize = vertical ? 360 : 260;
  const titleSize = vertical ? 92 : 104;
  return (
    <AbsoluteFill style={{opacity: inP * outP, alignItems: 'center', justifyContent: 'center', fontFamily: FONT}}>
      <div style={{display: 'flex', flexDirection: vertical ? 'column' : 'row', alignItems: 'center', gap: vertical ? 48 : 96, transform: `translateY(${((1 - s) * 24 - (vertical ? 120 : 0)).toFixed(1)}px)`}}>
        <Ring size={ringSize} p={ease} />
        <div style={{display: 'flex', flexDirection: 'column', alignItems: vertical ? 'center' : 'flex-start'}}>
          <div style={{fontSize: 18, fontWeight: 500, color: C.muted, letterSpacing: 6}}>{item.latin?.toUpperCase()}</div>
          <div style={{marginTop: 14, fontSize: titleSize, fontWeight: 400, color: C.text, letterSpacing: -3, lineHeight: 1.05}}>{item.title}</div>
          <div style={{marginTop: 26, fontSize: 34, fontWeight: 400, color: C.dim, letterSpacing: -0.6, opacity: interpolate(f, [16, 30], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'})}}>{item.line}</div>
          {item.features && (
            <div style={{marginTop: 34, display: 'flex', flexDirection: 'column', gap: 12}}>
              {item.features.map((ft, i) => (
                <div key={i} style={{display: 'flex', alignItems: 'center', gap: 14, fontSize: 24, color: C.dim, letterSpacing: -0.2, opacity: interpolate(f, [30 + i * 10, 44 + i * 10], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'})}}>
                  <div style={{width: 6, height: 6, borderRadius: 3, background: C.accent}} /> {ft}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
      {item.hashtags && (
        <div style={{position: 'absolute', left: 0, right: 0, top: vertical ? height - 330 : height - 170, textAlign: 'center', fontSize: vertical ? 30 : 26, fontWeight: 500, color: C.accent, letterSpacing: -0.2, opacity: interpolate(f, [30, 46], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'})}}>{plan.hashtags}</div>
      )}
      <div style={{position: 'absolute', left: 0, right: 0, bottom: vertical ? 150 : 44, textAlign: 'center', fontSize: vertical ? 20 : 19, color: C.muted, letterSpacing: -0.2, opacity: interpolate(f, [24, 40], [0, 0.9], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'})}}>{plan.footer}</div>
    </AbsoluteFill>
  );
};

export const Cards: React.FC<{items: CardItem[]; plan: Plan}> = ({items, plan}) => (
  <>
    {items.map((c, i) => (
      <Sequence key={i} from={c.start} durationInFrames={c.dur} layout="none">
        <Card item={c} plan={plan} />
      </Sequence>
    ))}
  </>
);
