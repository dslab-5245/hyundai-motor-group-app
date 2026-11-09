// Design system v3 for every Drive Symphony composition: deep navy, ice accent, Pretendard 400/500, no heavy weights.
export const C = {
  bg0: '#060A12',
  bg1: '#0B1220',
  text: '#C9D6F2',
  muted: '#6F7F9C',
  dim: 'rgba(201,214,242,0.62)',
  accent: '#8FB3FF',
  accentSoft: 'rgba(143,179,255,0.16)',
  warn: '#FFA98F',
  good: '#8FE3B8',
  grace: '#C7B3FF',
  info: '#8FB3FF',
  pill: 'rgba(8,13,24,0.74)',
  line: 'rgba(143,179,255,0.22)',
};
export const toneColor = (tone: string) => (tone === 'warn' ? C.warn : tone === 'good' ? C.good : tone === 'grace' ? C.grace : C.info);
export const FONT = "'Pretendard', 'Noto Sans KR', 'Malgun Gothic', sans-serif";
export const FPS = 30;
