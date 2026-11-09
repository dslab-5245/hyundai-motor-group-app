import React from 'react';
import {AbsoluteFill, OffthreadVideo, Sequence, interpolate, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import type {Plan, Shot} from '../plan';
import {PANE, layoutFor} from '../layout';
import {C} from '../theme';

/** The app's flow (0..1) at a local frame: the last key, approached over 12 frames. */
export const flowAt = (shot: Shot, f: number): number => {
  let v = shot.flow.length ? shot.flow[0].flow : 1;
  let prev = v;
  for (const k of shot.flow) {
    if (k.at > f) break;
    prev = v;
    const p = Math.min(1, (f - k.at) / 12);
    v = prev + (k.flow - prev) * (1 - Math.pow(1 - p, 3));
    if (p >= 1) prev = k.flow;
  }
  return v;
};

/** The real UI, flat, in a slim frame, in its grid cell. Enters by rising a little, leaves by fading. */
const ShotView: React.FC<{shot: Shot; plan: Plan}> = ({shot, plan}) => {
  const f = useCurrentFrame();
  const {fps, width, height} = useVideoConfig();
  const L = layoutFor(width, height);
  const box = shot.crop === 'full' ? L.full : L.insert;
  const src = plan.sources[shot.src];
  if (!src) return null;
  const flow = flowAt(shot, f);
  const dip = 1 - flow;
  const inP = interpolate(f, [0, 14], [0, 1], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const ease = 1 - Math.pow(1 - inP, 3);
  const outP = interpolate(f, [shot.dur - 10, shot.dur], [1, 0], {extrapolateLeft: 'clamp', extrapolateRight: 'clamp'});
  const cw = shot.crop === 'full' ? 1920 : PANE.w;
  const cx = shot.crop === 'full' ? 0 : PANE.x;
  const cy = shot.crop === 'full' ? 0 : PANE.y;
  const scale = box.width / cw;
  const macro = shot.style === 'macro' ? shot.zoom : 1;
  const focus = shot.focus ?? [0.5, 0.5];
  const radius = 22;
  return (
    <AbsoluteFill style={{opacity: ease * outP}}>
      <div style={{position: 'absolute', left: box.left, top: box.top, width: box.width, height: box.height, transform: `translateY(${((1 - ease) * 18).toFixed(1)}px)`}}>
        <div style={{position: 'absolute', inset: -1, borderRadius: radius + 1, border: `1px solid ${C.line}`, boxShadow: '0 40px 120px rgba(0,0,0,0.55), 0 0 0 1px rgba(0,0,0,0.6)'}} />
        <div style={{position: 'absolute', inset: 0, borderRadius: radius, overflow: 'hidden', background: '#0A0D14', filter: `brightness(${(1 - dip * 0.14).toFixed(3)})`}}>
          <div style={{position: 'absolute', inset: 0, transform: `scale(${macro})`, transformOrigin: `${(focus[0] * 100).toFixed(1)}% ${(focus[1] * 100).toFixed(1)}%`}}>
            <OffthreadVideo
              src={staticFile(src.video)}
              startFrom={Math.round(shot.offset * fps)}
              muted
              style={{position: 'absolute', width: 1920 * scale, height: 1080 * scale, left: -cx * scale, top: -cy * scale}}
            />
          </div>
          <div style={{position: 'absolute', inset: 0, background: 'linear-gradient(170deg, rgba(255,255,255,0.05) 0%, rgba(255,255,255,0) 35%)', pointerEvents: 'none'}} />
        </div>
      </div>
      {dip > 0.05 && <AbsoluteFill style={{background: 'rgba(40,60,100,1)', mixBlendMode: 'overlay', opacity: dip * 0.25, pointerEvents: 'none'}} />}
    </AbsoluteFill>
  );
};

export const Panels: React.FC<{shots: Shot[]; plan: Plan}> = ({shots, plan}) => (
  <>
    {shots.map((s, i) => (
      <Sequence key={i} from={s.start} durationInFrames={s.dur} layout="none">
        <ShotView shot={s} plan={plan} />
      </Sequence>
    ))}
  </>
);
