// plan.json (written by tools/plan.py) — the layered timelines the Film renders.
export type Tone = 'warn' | 'good' | 'grace' | 'info';

export interface SourceInfo {
  video: string;
  audio: string | null;
  badge_s: number;
  duration: number;
  music_end: number;
  gain_db: number;
  scenario_offset: number; // clip seconds − scenario seconds (signals sync)
  events: Array<{t: number; type: string; graced: boolean; flow: number | null; label: string; line: string; tone: Tone}>;
}

export interface Scene3DKey {at: number; cam: string; cut?: boolean; hold?: boolean; orbit?: boolean}
export interface Scene3DItem {start: number; dur: number; src: string | null; offset: number; keys: Scene3DKey[]; rain?: number | null}
export interface FlowKey {at: number; flow: number}
export interface Shot {
  start: number; dur: number; src: string; offset: number;
  crop: 'pane' | 'full';
  style: 'insert' | 'full' | 'macro';
  zoom: number;
  focus: [number, number] | null;
  flow: FlowKey[];
}
export interface TextItem {start: number; dur: number; lines: string[]; size: 'title' | 'xl' | 'l' | 'm'; pos: string; style: string}
export interface Callout {start: number; type: string; label: string; line: string; tone: Tone; flow: number | null; graced: boolean; src: string; t: number}
export interface LabelItem {start: number; dur: number; label: string; line: string}
export interface CardItem {
  start: number; dur: number; kind: 'chapter' | 'end' | 'outro';
  kicker?: string; title?: string; latin?: string; line?: string; features?: string[]; hashtags?: boolean;
}
export interface MusicSeg {start: number; dur: number; src: string; offset: number; fadeIn: number; fadeOut: number; gain: number}
export interface VoiceItem {id: string; start: number; dur: number; text: string}

export interface CompPlan {
  width: number; height: number; duration: number;
  beats: Array<{id: string; start: number; dur: number}>;
  scene3d: Scene3DItem[]; panel: Shot[]; text: TextItem[]; callouts: Callout[]; labels: LabelItem[]; cards: CardItem[];
  music: MusicSeg[]; voice: VoiceItem[]; ticks: number[];
}

export interface Plan {
  fps: number;
  sources: Record<string, SourceInfo>;
  narration: Record<string, {file: string; duration: number; text: string}>;
  voice: string | null;
  signals: string | null; // run/signals_story.json
  tick: string | null; // gen/tick.wav
  hashtags: string;
  footer: string;
  noise?: string;
  compositions: Record<string, CompPlan>;
}

/** Which overlay grid is active at a frame: a 3D scene, an app insert, a full-frame recording, or nothing (cards). */
export type Mode = 'scene' | 'insert' | 'full' | 'none';
export const modeAt = (c: CompPlan, f: number): Mode => {
  for (const s of c.panel) if (s.start <= f && f < s.start + s.dur) return s.crop === 'full' ? 'full' : 'insert';
  for (const s of c.scene3d) if (s.start <= f && f < s.start + s.dur) return 'scene';
  return 'none';
};
