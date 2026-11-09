// plan.json (written by tools/plan.py) — the layered timelines the Film renders.
export type Tone = 'warn' | 'good' | 'grace' | 'info';

export interface SourceInfo {
  video: string;
  audio: string | null;
  badge_s: number;
  duration: number;
  music_end: number;
  gain_db: number;
  events: Array<{t: number; type: string; graced: boolean; flow: number | null; label: string; line: string; tone: Tone}>;
}

export interface BgItem {start: number; dur: number; clip: string; offset: number; zoom: number; dim: number; blur: number; fadeIn: number}
export interface FlowKey {at: number; flow: number}
export interface Shot {
  start: number; dur: number; src: string; offset: number;
  crop: 'pane' | 'full';
  style: 'reveal' | 'float' | 'dip' | 'macro' | 'settle' | 'full';
  enter: 'cut' | 'fly' | 'flash' | 'rise';
  exit: 'cut' | 'sink';
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
  background: BgItem[]; panel: Shot[]; text: TextItem[]; callouts: Callout[]; labels: LabelItem[]; cards: CardItem[];
  music: MusicSeg[]; voice: VoiceItem[];
}

export interface Plan {
  fps: number;
  sources: Record<string, SourceInfo>;
  narration: Record<string, {file: string; duration: number; text: string}>;
  voice: string | null;
  broll: Record<string, {file: string; duration: number; roles: string[]}>;
  hashtags: string;
  footer: string;
  noise?: string;
  compositions: Record<string, CompPlan>;
}
