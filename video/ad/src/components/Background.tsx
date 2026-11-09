import React from 'react';
import {AbsoluteFill, OffthreadVideo, Sequence, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import type {BgItem, Plan} from '../plan';
import {C} from '../theme';

const Clip: React.FC<{item: BgItem; plan: Plan}> = ({item, plan}) => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const clip = plan.broll[item.clip];
  if (!clip) return null;
  const t = frame / item.dur;
  const zoom = 1 + (item.zoom - 1) * t; // slow push-in over the whole item
  const fade = item.fadeIn > 0 ? interpolate(frame, [0, item.fadeIn], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'}) : 1;
  return (
    <AbsoluteFill style={{opacity: fade}}>
      <OffthreadVideo
        src={staticFile(clip.file)}
        startFrom={Math.round(item.offset * fps)}
        muted
        style={{
          width: '100%', height: '100%', objectFit: 'cover',
          transform: `scale(${zoom.toFixed(4)})`,
          filter: `brightness(${item.dim}) saturate(0.72) contrast(1.08)${item.blur ? ` blur(${item.blur}px)` : ''}`,
        }}
      />
    </AbsoluteFill>
  );
};

/** Driving POV footage behind everything: graded dark and warm, vignetted, never presented as app footage. */
export const Background: React.FC<{items: BgItem[]; plan: Plan}> = ({items, plan}) => {
  return (
    <AbsoluteFill style={{background: `linear-gradient(160deg, ${C.bg0} 0%, ${C.bg1} 100%)`}}>
      {items.map((it, i) => (
        <Sequence key={i} from={it.start} durationInFrames={it.dur} layout="none">
          <Clip item={it} plan={plan} />
        </Sequence>
      ))}
      {/* warm grade + vignette */}
      <AbsoluteFill style={{background: 'linear-gradient(180deg, rgba(11,11,13,0.35) 0%, rgba(21,18,14,0.15) 45%, rgba(11,11,13,0.75) 100%)'}} />
      <AbsoluteFill style={{background: 'rgba(200,134,43,0.10)', mixBlendMode: 'overlay'}} />
      <AbsoluteFill style={{background: 'radial-gradient(ellipse 70% 60% at 50% 45%, rgba(0,0,0,0) 40%, rgba(5,4,3,0.72) 100%)'}} />
    </AbsoluteFill>
  );
};
