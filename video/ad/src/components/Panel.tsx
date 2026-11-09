import React from 'react';
import {AbsoluteFill, OffthreadVideo, Sequence, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import type {Plan, Shot} from '../plan';
import {PANE, layoutFor} from '../layout';
import {C} from '../theme';
import type {Features} from '../audio';

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

const clamp = (v: number, a: number, b: number) => Math.max(a, Math.min(b, v));

const ShotView: React.FC<{shot: Shot; plan: Plan; features: Features; vertical: boolean}> = ({shot, plan, features, vertical}) => {
  const f = useCurrentFrame();
  const {fps, width, height} = useVideoConfig();
  const L = layoutFor(width, height, shot.crop);
  const src = plan.sources[shot.src];
  if (!src) return null;
  const flow = flowAt(shot, f);
  const dipAmt = 1 - flow;

  // enter
  let enterS = 1, enterY = 0, enterRot = 0, enterBlur = 0, enterO = 1, flash = 0;
  if (shot.enter === 'fly') {
    const s = spring({frame: f, fps, config: {damping: 16, stiffness: 90, mass: 1.1}});
    enterS = 0.78 + 0.22 * s; enterY = 140 * (1 - s); enterRot = -30 * (1 - s); enterBlur = 14 * (1 - s); enterO = clamp(f / 8, 0, 1);
  } else if (shot.enter === 'rise') {
    const s = spring({frame: f, fps, config: {damping: 20, stiffness: 70, mass: 1.2}});
    enterY = 160 * (1 - s); enterO = clamp(f / 10, 0, 1); enterS = 0.96 + 0.04 * s;
  } else if (shot.enter === 'flash') {
    flash = interpolate(f, [0, 2, 9], [0.0, 0.85, 0], {extrapolateRight: 'clamp'});
    enterS = 1 + 0.025 * (1 - clamp(f / 9, 0, 1));
  }
  // exit
  let exitY = 0, exitO = 1;
  if (shot.exit === 'sink') {
    const e = clamp((f - (shot.dur - 18)) / 18, 0, 1);
    const ease = e * e;
    exitY = 120 * ease; exitO = 1 - ease;
  }
  // float / dip
  const bob = Math.sin(f / 37) * 4 + Math.sin(f / 71) * 2;
  const drift = Math.sin(f / 53) * 0.8;
  const dipY = dipAmt * 38;
  const dipRot = dipAmt * -4;
  const brightness = 1 - dipAmt * 0.22;

  // crop maths: the pane (or the whole display) scaled into the panel box
  const cw = shot.crop === 'full' ? 1920 : PANE.w;
  const ch = shot.crop === 'full' ? 1080 : PANE.h;
  const cx = shot.crop === 'full' ? 0 : PANE.x;
  const cy = shot.crop === 'full' ? 0 : PANE.y;
  const scale = L.panel.width / cw;
  const bezel = vertical ? 12 : 14;
  const radius = vertical ? 28 : 30;
  const glow = 0.18 + features.bass * 0.55 * (0.35 + 0.65 * flow);
  const macro = shot.style === 'macro' ? shot.zoom : 1;
  const focus = shot.focus ?? [0.5, 0.5];
  const macroOrigin = `${(focus[0] * 100).toFixed(1)}% ${(focus[1] * 100).toFixed(1)}%`;

  return (
    <AbsoluteFill style={{opacity: enterO * exitO}}>
      {/* reactive amber glow behind the display */}
      <div style={{
        position: 'absolute', left: L.panel.left - 160, top: L.panel.top - 120, width: L.panel.width + 320, height: L.panel.height + 240,
        background: `radial-gradient(ellipse 55% 55% at 40% 55%, rgba(224,164,88,${(glow * 0.85).toFixed(3)}) 0%, rgba(200,134,43,${(glow * 0.35).toFixed(3)}) 35%, rgba(0,0,0,0) 70%)`,
        filter: 'blur(18px)', transform: `translateY(${(enterY + exitY + dipY + bob).toFixed(1)}px)`,
      }} />
      <div style={{
        position: 'absolute', left: L.panel.left, top: L.panel.top, width: L.panel.width, height: L.panel.height,
        transformStyle: 'preserve-3d',
        transform: `${L.perspective} translateY(${(enterY + exitY + dipY + bob).toFixed(1)}px) rotateY(${(enterRot + drift).toFixed(2)}deg) rotateX(${dipRot.toFixed(2)}deg) scale(${enterS.toFixed(4)})`,
        transformOrigin: '50% 60%',
        filter: enterBlur > 0.3 ? `blur(${enterBlur.toFixed(1)}px)` : undefined,
      }}>
        {/* bezel */}
        <div style={{
          position: 'absolute', inset: -bezel, borderRadius: radius, background: 'linear-gradient(160deg, #221d18 0%, #120f0c 100%)',
          boxShadow: `0 60px 140px rgba(0,0,0,0.70), 0 10px 30px rgba(0,0,0,0.55), inset 0 0 0 1px rgba(255,255,255,0.07), -18px 0 40px rgba(224,164,88,${(0.10 + features.bass * 0.12).toFixed(3)})`,
        }} />
        {/* the real UI, with a reflection under it */}
        <div style={{
          position: 'absolute', inset: 0, borderRadius: radius - bezel + 2, overflow: 'hidden', background: '#0e0d0c',
          WebkitBoxReflect: shot.crop === 'pane' ? `below ${bezel + 10}px linear-gradient(transparent 74%, rgba(255,255,255,0.09))` : undefined,
          filter: `brightness(${brightness.toFixed(3)})`,
        }}>
          <div style={{position: 'absolute', inset: 0, transform: `scale(${macro})`, transformOrigin: macroOrigin}}>
            <OffthreadVideo
              src={staticFile(src.video)}
              startFrom={Math.round(shot.offset * fps)}
              muted
              style={{position: 'absolute', width: 1920 * scale, height: 1080 * scale, left: -cx * scale, top: -cy * scale}}
            />
          </div>
          {/* glass: top-left sheen */}
          <div style={{position: 'absolute', inset: 0, background: 'linear-gradient(115deg, rgba(255,255,255,0.10) 0%, rgba(255,255,255,0.02) 30%, rgba(0,0,0,0) 55%, rgba(0,0,0,0.08) 100%)', pointerEvents: 'none'}} />
          {flash > 0 && <div style={{position: 'absolute', inset: 0, background: '#15120E', opacity: flash}} />}
        </div>
      </div>
      {/* cool cast while the music is sagging */}
      {dipAmt > 0.05 && (
        <AbsoluteFill style={{background: 'rgba(60,80,110,1)', mixBlendMode: 'overlay', opacity: dipAmt * 0.4, pointerEvents: 'none'}} />
      )}
    </AbsoluteFill>
  );
};

export const Panels: React.FC<{shots: Shot[]; plan: Plan; features: Features; vertical: boolean}> = ({shots, plan, features, vertical}) => (
  <>
    {shots.map((s, i) => (
      <Sequence key={i} from={s.start} durationInFrames={s.dur} layout="none">
        <ShotView shot={s} plan={plan} features={features} vertical={vertical} />
      </Sequence>
    ))}
  </>
);

export const amberFor = (tone: string) => (tone === 'warn' ? '#E8743A' : tone === 'good' ? C.amberHi : tone === 'grace' ? '#F3EEE6' : C.muted);
