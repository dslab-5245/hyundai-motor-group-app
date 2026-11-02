import React from 'react';
import {Sequence, interpolate, spring, useCurrentFrame, useVideoConfig} from 'remotion';
import type {TextItem} from '../plan';
import {layoutFor} from '../layout';
import {C} from '../theme';

const SIZES: Record<string, {size: number; weight: number; lh: number; ls: number}> = {
  title: {size: 128, weight: 800, lh: 1.05, ls: -4},
  xl: {size: 86, weight: 800, lh: 1.16, ls: -2.5},
  l: {size: 54, weight: 700, lh: 1.24, ls: -1.2},
  m: {size: 40, weight: 500, lh: 1.35, ls: -0.6},
};

/** One line of kinetic type: each word rises and sharpens with its own spring, 2 frames apart. */
const Line: React.FC<{text: string; delay: number; size: number; weight: number; ls: number; color: string; exitP: number}> = ({text, delay, size, weight, ls, color, exitP}) => {
  const f = useCurrentFrame();
  const {fps} = useVideoConfig();
  const words = text.split(' ');
  return (
    <div style={{display: 'flex', flexWrap: 'wrap', gap: `0 ${size * 0.26}px`, whiteSpace: 'nowrap'}}>
      {words.map((w, i) => {
        const s = spring({frame: f - delay - i * 2, fps, config: {damping: 19, stiffness: 150, mass: 0.9}});
        const y = (1 - s) * size * 0.45;
        const blur = (1 - s) * 10;
        const o = Math.max(0, Math.min(1, (f - delay - i * 2) / 6)) * (1 - exitP);
        return (
          <span key={i} style={{
            display: 'inline-block', fontSize: size, fontWeight: weight, letterSpacing: ls, color, lineHeight: 1,
            transform: `translateY(${(y - exitP * size * 0.25).toFixed(1)}px)`, opacity: o,
            filter: blur > 0.4 ? `blur(${blur.toFixed(1)}px)` : undefined,
            textShadow: '0 2px 24px rgba(0,0,0,0.55)',
          }}>{w}</span>
        );
      })}
    </div>
  );
};

const Block: React.FC<{item: TextItem; vertical: boolean}> = ({item, vertical}) => {
  const f = useCurrentFrame();
  const {width, height} = useVideoConfig();
  const L = layoutFor(width, height, 'pane');
  const sz = SIZES[item.size] ?? SIZES.l;
  const scaleV = vertical ? 0.92 : 1;
  const size = Math.round(sz.size * scaleV);
  const exitP = interpolate(f, [item.dur - 10, item.dur], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const color = item.size === 'm' ? C.dim : C.text;
  // position
  let style: React.CSSProperties = {position: 'absolute'};
  const pos = item.pos;
  if (pos === 'center' || pos === 'center2') {
    style = {position: 'absolute', left: 0, right: 0, top: pos === 'center' ? (vertical ? 560 : 400) : (vertical ? 760 : 560), display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6};
    if (item.size === 'title' && pos === 'center') style.top = vertical ? 520 : 380;
  } else if (pos === 'top') {
    style = {position: 'absolute', left: L.textTop.x, top: L.textTop.y, width: L.textTop.w, display: 'flex', flexDirection: 'column', gap: 6};
  } else if (pos === 'topleft') {
    style = {position: 'absolute', left: L.textTop.x, top: L.textTop.y, display: 'flex', flexDirection: 'column'};
  } else if (pos === 'topleft2') {
    style = {position: 'absolute', left: L.textTop.x, top: L.textTop.y + 150, display: 'flex', flexDirection: 'column', gap: 4};
  } else {
    // left column, vertically centred on the panel
    const total = item.lines.length * size * sz.lh;
    style = {position: 'absolute', left: L.textLeft.x, top: L.textLeft.y + (vertical ? 0 : 120 - total / 2), width: L.textLeft.w, display: 'flex', flexDirection: 'column', gap: Math.round(size * 0.18)};
  }
  // title: a slow settle from slightly larger
  const settle = item.size === 'title' ? 1 + 0.06 * Math.max(0, 1 - f / 24) : 1;
  const accentRule = item.size === 'title' || item.size === 'xl';
  return (
    <div style={{...style, transform: `scale(${settle.toFixed(4)})`, transformOrigin: pos.startsWith('center') ? '50% 50%' : '0 50%'}}>
      {item.lines.map((ln, i) => (
        <Line key={i} text={ln} delay={i * 7} size={size} weight={sz.weight} ls={sz.ls} color={color} exitP={exitP} />
      ))}
      {accentRule && (
        <div style={{
          marginTop: Math.round(size * 0.35), height: 4, width: interpolate(f, [6, 26], [0, 96], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'}), background: C.amberHi, borderRadius: 2,
          opacity: 1 - exitP, alignSelf: pos.startsWith('center') ? 'center' : 'flex-start',
        }} />
      )}
    </div>
  );
};

export const Texts: React.FC<{items: TextItem[]; vertical: boolean}> = ({items, vertical}) => (
  <>
    {items.map((t, i) => (
      <Sequence key={i} from={t.start} durationInFrames={t.dur} layout="none">
        <Block item={t} vertical={vertical} />
      </Sequence>
    ))}
  </>
);
