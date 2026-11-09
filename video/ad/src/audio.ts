import {useAudioData, visualizeAudio} from '@remotion/media-utils';
import {staticFile, useCurrentFrame, useVideoConfig} from 'remotion';
import type {MusicSeg, Plan} from './plan';

export interface Features {bass: number; mids: number; bars: number[]; active: boolean}

const EMPTY: Features = {bass: 0, mids: 0, bars: new Array(64).fill(0), active: false};

/** Spectrum of whatever music segment is audible at the current frame (drives the glow and the bottom spectrum). */
export const useMusicFeatures = (music: MusicSeg[], plan: Plan): Features => {
  const frame = useCurrentFrame();
  const {fps} = useVideoConfig();
  const names = Object.keys(plan.sources).filter((k) => plan.sources[k].audio);
  // hooks must be called unconditionally: one per source (three in practice: story, console, remix)
  const d0 = useAudioData(names[0] ? staticFile(plan.sources[names[0]].audio as string) : staticFile('run/plan.json'));
  const d1 = useAudioData(names[1] ? staticFile(plan.sources[names[1]].audio as string) : staticFile('run/plan.json'));
  const d2 = useAudioData(names[2] ? staticFile(plan.sources[names[2]].audio as string) : staticFile('run/plan.json'));
  const seg = music.find((m) => frame >= m.start && frame < m.start + m.dur);
  if (!seg) return EMPTY;
  const idx = names.indexOf(seg.src);
  const data = idx === 0 ? d0 : idx === 1 ? d1 : idx === 2 ? d2 : null;
  if (!data) return EMPTY;
  const srcFrame = Math.round(seg.offset * fps) + (frame - seg.start);
  const raw = visualizeAudio({fps, frame: srcFrame, audioData: data, numberOfSamples: 256, optimizeFor: 'speed'});
  // 256 bins over 0..24 kHz; keep the musical range (~0..6 kHz) with a gentle log spacing into 64 bars
  const bars: number[] = [];
  for (let i = 0; i < 64; i++) {
    const a = Math.floor(Math.pow(i / 64, 2.0) * 48) + 1;
    const b = Math.max(a + 1, Math.floor(Math.pow((i + 1) / 64, 2.0) * 48) + 1);
    let s = 0;
    for (let k = a; k < b; k++) s += raw[k] ?? 0;
    bars.push(Math.min(1, Math.pow(s / (b - a), 0.45) * 1.5 * (1 + 1.4 * (i / 63))));
  }
  const bass = Math.min(1, (raw[1] + raw[2] + raw[3] + raw[4]) / 4 * 2.2);
  const mids = Math.min(1, (raw[8] + raw[12] + raw[16] + raw[24]) / 4 * 2.6);
  let env = 1;
  if (seg.fadeIn && frame - seg.start < seg.fadeIn) env = (frame - seg.start) / seg.fadeIn;
  if (seg.fadeOut && seg.start + seg.dur - frame < seg.fadeOut) env = Math.min(env, (seg.start + seg.dur - frame) / seg.fadeOut);
  return {bass: bass * env, mids: mids * env, bars: bars.map((v) => v * env), active: true};
};
