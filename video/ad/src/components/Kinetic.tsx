import React from 'react';
import {Sequence, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import type {TextItem} from '../plan';
import {layoutFor, zoneStyle, type Zone} from '../layout';
import {C, FONT} from '../theme';

const SIZES: Record<string, {size: number; weight: number; lh: number; ls: number}> = {
  title: {size: 96, weight: 400, lh: 1.08, ls: -2.4},
  xl: {size: 64, weight: 400, lh: 1.18, ls: -1.6},
  l: {size: 44, weight: 400, lh: 1.3, ls: -1.0},
  m: {size: 30, weight: 400, lh: 1.4, ls: -0.4},
};

/** One line: the words rise into place with their own springs, 2 frames apart; no blur, no bounce. */
const Line: React.FC<{text: string; delay: number; size: number; weight: number; ls: number; color: string; exitP: number; align: Zone['align']}> = ({text, delay, size, weight, ls, color, exitP, align}) => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();
  const words = text.split(' ');
  return (
    <div style={{display: 'flex', flexWrap: 'wrap', gap: `0 ${size * 0.26}px`, justifyContent: align === 'right' ? 'flex-end' : align === 'center' ? 'center' : 'flex-start', whiteSpace: 'nowrap'}}>
      {words.map((w, i) => {
        const s = spring({frame: f - delay - i * 2, fps, config: {damping: 24, stiffness: 150, mass: 0.9}});
        const y = (1 - s) * size * 0.3;
        const o = Math.max(0, Math.min(1, (f - delay - i * 2) / 7)) * (1 - exitP);
        return (
          <span key={i} style={{display: 'inline-block', fontSize: size, fontWeight: weight, letterSpacing: ls, color, lineHeight: 1.1, transform: `translateY(${(y - exitP * size * 0.15).toFixed(1)}px)`, opacity: o, textShadow: '0 2px 18px rgba(0,0,0,0.45)'}}>
            {w}
          </span>
        );
      })}
    </div>
  );
};

const Block: React.FC<{item: TextItem}> = ({item}) => {
  const f = useCurrentFrame();
  const {width, height} = useVideoConfig();
  const L = layoutFor(width, height);
  const vertical = height > width;
  const sz = SIZES[item.size] ?? SIZES.l;
  const size = Math.round(sz.size * (vertical ? 0.9 : 1));
  const exitP = interpolate(f, [item.dur - 9, item.dur], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const color = item.size === 'm' ? C.dim : C.text;
  const pos = item.pos;
  let zone: Zone;
  let kicker: string | null = null;
  if (pos === 'title') {
    zone = L.title;
    kicker = 'DRIVE SYMPHONY · PLEOS CONNECT';
  } else if (pos === 'insert') zone = L.insertText;
  else if (pos === 'center') zone = {x: L.margin, y: vertical ? 760 : 430, w: width - 2 * L.margin, align: 'center'};
  else zone = L.message;
  const style = zoneStyle(zone);
  const scrim = pos !== 'insert' && pos !== 'center';
  const o = Math.min(1, f / 10) * (1 - exitP);
  return (
    <div style={{...style, gap: Math.round(size * 0.22), fontFamily: FONT, isolation: 'isolate'}}>
      {scrim && (
        <div style={{position: 'absolute', zIndex: -1, left: -140, right: -200, top: -90, bottom: -110, opacity: 0.85 * o, filter: 'blur(26px)',
          background: `radial-gradient(ellipse at ${zone.align === 'right' ? '75%' : '25%'} 55%, rgba(6,10,18,0.9) 0%, rgba(6,10,18,0.62) 40%, rgba(6,10,18,0) 72%)`}} />
      )}
      {kicker && (
        <div style={{fontSize: 18, letterSpacing: 6, fontWeight: 500, color: C.muted, marginBottom: 14, opacity: Math.min(1, f / 8) * (1 - exitP)}}>{kicker}</div>
      )}
      {item.lines.map((ln, i) => (
        <Line key={i} text={ln} delay={i * 6} size={size} weight={sz.weight} ls={sz.ls} color={color} exitP={exitP} align={zone.align} />
      ))}
      {pos === 'title' && (
        <div style={{marginTop: Math.round(size * 0.28), height: 2, width: interpolate(f, [8, 30], [0, 56], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'}), background: C.accent, opacity: 0.9 * (1 - exitP)}} />
      )}
      {pos !== 'title' && pos !== 'center' && (
        <div style={{order: -1, height: 2, width: 42, background: C.accent, marginBottom: Math.round(size * 0.4), opacity: Math.min(1, f / 10) * 0.9 * (1 - exitP)}} />
      )}
    </div>
  );
};

export const Texts: React.FC<{items: TextItem[]}> = ({items}) => (
  <>
    {items.map((t, i) => (
      <Sequence key={i} from={t.start} durationInFrames={t.dur} layout="none">
        <Block item={t} />
      </Sequence>
    ))}
  </>
);
