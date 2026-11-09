import React from 'react';
import {AbsoluteFill, Sequence, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import type {CardItem, Plan} from '../plan';
import {C} from '../theme';
import type {Features} from '../audio';

/** The Flow-ring motif: a 270° arc drawing itself, breathing with the bass. */
const Ring: React.FC<{size: number; p: number; bass: number}> = ({size, p, bass}) => {
  const r = size / 2 - 14;
  const circ = 2 * Math.PI * r;
  const arc = circ * 0.75;
  return (
    <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{transform: `rotate(135deg) scale(${(1 + bass * 0.04).toFixed(3)})`}}>
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="rgba(243,238,230,0.10)" strokeWidth={14} strokeDasharray={`${arc} ${circ}`} strokeLinecap="round" />
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={C.amber} strokeWidth={14} strokeDasharray={`${arc * p} ${circ}`} strokeLinecap="round"
        style={{filter: `drop-shadow(0 0 ${(10 + bass * 26).toFixed(0)}px rgba(224,164,88,${(0.45 + bass * 0.4).toFixed(2)}))`}} />
    </svg>
  );
};

const Card: React.FC<{item: CardItem; plan: Plan; features: Features; vertical: boolean}> = ({item, plan, features, vertical}) => {
  const f = useCurrentFrame();
  const {fps, width, height} = useVideoConfig();
  const inP = interpolate(f, [0, 14], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const outP = interpolate(f, [item.dur - 14, item.dur], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const s = spring({frame: f, fps, config: {damping: 18, stiffness: 110, mass: 1}});
  const ringP = interpolate(f, [6, 50], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const ease = 1 - Math.pow(1 - ringP, 3);
  const bg = `radial-gradient(ellipse 80% 70% at 50% 40%, ${C.bg1} 0%, ${C.bg0} 70%)`;

  if (item.kind === 'chapter') {
    return (
      <AbsoluteFill style={{background: bg, opacity: inP * outP, alignItems: 'center', justifyContent: 'center'}}>
        <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', transform: `translateY(${((1 - s) * 30).toFixed(1)}px)`}}>
          <div style={{fontSize: 30, fontWeight: 600, color: C.amberHi, letterSpacing: 6}}>{item.kicker}</div>
          <div style={{marginTop: 18, fontSize: 92, fontWeight: 800, color: C.text, letterSpacing: -3}}>{item.title}</div>
          <div style={{marginTop: 22, height: 3, width: 96 * ease, background: C.amber, borderRadius: 2}} />
          <div style={{marginTop: 26, fontSize: 32, fontWeight: 400, color: C.dim, letterSpacing: -0.5, textAlign: 'center', maxWidth: width - 240}}>{item.line}</div>
        </div>
      </AbsoluteFill>
    );
  }
  const ringSize = vertical ? 420 : 300;
  const titleSize = vertical ? 104 : 118;
  return (
    <AbsoluteFill style={{background: bg, opacity: inP * outP, alignItems: 'center', justifyContent: 'center'}}>
      <div style={{display: 'flex', flexDirection: vertical ? 'column' : 'row', alignItems: 'center', gap: vertical ? 40 : 90, transform: `translateY(${((1 - s) * 30 - (vertical ? 120 : 0)).toFixed(1)}px)`}}>
        <Ring size={ringSize} p={ease} bass={features.bass} />
        <div style={{display: 'flex', flexDirection: 'column', alignItems: vertical ? 'center' : 'flex-start'}}>
          <div style={{fontSize: titleSize, fontWeight: 800, color: C.text, letterSpacing: -4, lineHeight: 1.05}}>{item.title}</div>
          <div style={{marginTop: 10, fontSize: 36, fontWeight: 500, color: C.amberHi, letterSpacing: 8, textTransform: 'uppercase'}}>{item.latin}</div>
          <div style={{marginTop: 30, fontSize: vertical ? 40 : 42, fontWeight: 400, color: C.dim, letterSpacing: -0.8, opacity: interpolate(f, [16, 30], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'})}}>{item.line}</div>
          {item.features && (
            <div style={{marginTop: 34, display: 'flex', flexDirection: 'column', gap: 12}}>
              {item.features.map((ft, i) => (
                <div key={i} style={{display: 'flex', alignItems: 'center', gap: 14, fontSize: 27, color: C.dim, letterSpacing: -0.3, opacity: interpolate(f, [30 + i * 10, 44 + i * 10], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'})}}>
                  <div style={{width: 8, height: 8, borderRadius: 4, background: C.amber}} /> {ft}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
      {item.hashtags && (
        <div style={{position: 'absolute', left: 0, right: 0, top: vertical ? height - 330 : height - 170, textAlign: 'center', fontSize: vertical ? 34 : 30, fontWeight: 600, color: C.amberHi, letterSpacing: -0.4,
          opacity: interpolate(f, [30, 46], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'})}}>{plan.hashtags}</div>
      )}
      <div style={{position: 'absolute', left: 0, right: 0, bottom: vertical ? 150 : 44, textAlign: 'center', fontSize: vertical ? 22 : 21, color: C.muted, letterSpacing: -0.2,
        opacity: interpolate(f, [24, 40], [0, 0.9], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'})}}>{plan.footer}</div>
    </AbsoluteFill>
  );
};

export const Cards: React.FC<{items: CardItem[]; plan: Plan; features: Features; vertical: boolean}> = ({items, plan, features, vertical}) => (
  <>
    {items.map((c, i) => (
      <Sequence key={i} from={c.start} durationInFrames={c.dur} layout="none">
        <Card item={c} plan={plan} features={features} vertical={vertical} />
      </Sequence>
    ))}
  </>
);
