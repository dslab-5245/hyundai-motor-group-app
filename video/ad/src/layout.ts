// The overlay grid: fixed, disjoint zones per orientation, so nothing ever sits on top of anything else.
// All numbers are composition pixels. Zones are anchored by their top-left (x, y) or by `bottom` (distance from the
// bottom edge) and have a width; text that would exceed a zone width fails tools/verify.py --plan.
import type React from 'react';

export const PANE = {x: 640, y: 101, w: 1252, h: 821}; // the app pane inside the 1920x1080 Pleos display
export const RING = {x: 0.168, y: 0.474}; // the Flow ring centre, as a fraction of the pane

export interface Zone {x: number; y?: number; bottom?: number; w: number; align: 'left' | 'right' | 'center'}
export interface Box {left: number; top: number; width: number; height: number}

export interface Layout {
  margin: number;
  kicker: Zone; // 3D scenes and cards: top-left
  title: Zone;
  message: Zone; // 3D scenes: bottom-left, max 2 lines
  callout: Zone; // 3D scenes: bottom-right pills
  label: Zone; // 3D scenes: top-right phase label
  insert: Box; // the app pane, flat
  insertText: Zone; // beside the insert
  insertCallout: Zone;
  insertLabel: Zone;
  full: Box; // the whole display recording (demo)
  fullLabel: Zone;
  fullCallout: Zone;
}

export const layoutFor = (width: number, height: number): Layout => {
  const vertical = height > width;
  if (vertical) {
    const m = 72;
    const iw = width - 2 * m;
    const ih = Math.round((iw * PANE.h) / PANE.w);
    const fw = width - 2 * m;
    const fh = Math.round((fw * 1080) / 1920);
    return {
      margin: m,
      kicker: {x: m, y: 132, w: iw, align: 'left'},
      title: {x: m, y: 168, w: iw, align: 'left'},
      message: {x: m, y: 320, w: iw, align: 'left'},
      callout: {x: m, bottom: 300, w: iw, align: 'center'},
      label: {x: m, y: 132, w: iw, align: 'right'},
      insert: {left: m, top: 600, width: iw, height: ih},
      insertText: {x: m, y: 300, w: iw, align: 'left'},
      insertCallout: {x: m, y: 600 + ih + 56, w: iw, align: 'center'},
      insertLabel: {x: m, y: 132, w: iw, align: 'right'},
      full: {left: m, top: 620, width: fw, height: fh},
      fullLabel: {x: m, y: 620 + fh + 48, w: fw, align: 'left'},
      fullCallout: {x: m, y: 620 + fh + 190, w: fw, align: 'left'},
    };
  }
  const m = 96;
  const iw = 1100;
  const ih = Math.round((iw * PANE.h) / PANE.w); // 721
  const col = m + iw + 64; // right column x
  const colW = width - m - col; // 564
  const fw = 1560;
  const fh = Math.round((fw * 1080) / 1920); // 878
  return {
    margin: m,
    kicker: {x: m, y: 76, w: 900, align: 'left'},
    title: {x: m, y: 108, w: 900, align: 'left'},
    message: {x: m, bottom: m, w: 920, align: 'left'},
    callout: {x: width - m - 560, bottom: m, w: 560, align: 'right'},
    label: {x: width - m - 620, y: 76, w: 620, align: 'right'},
    insert: {left: m, top: 176, width: iw, height: ih},
    insertText: {x: col, y: 330, w: colW, align: 'left'},
    insertCallout: {x: col, bottom: height - (176 + ih), w: colW, align: 'left'},
    insertLabel: {x: col, y: 176, w: colW, align: 'left'},
    full: {left: (width - fw) / 2, top: 56, width: fw, height: fh},
    fullLabel: {x: (width - fw) / 2, y: 56 + fh + 26, w: 900, align: 'left'},
    fullCallout: {x: (width - fw) / 2 + fw - 560, y: 56 + fh + 26, w: 560, align: 'right'},
  };
};

export const zoneStyle = (z: Zone): React.CSSProperties => ({
  position: 'absolute',
  left: z.x,
  width: z.w,
  ...(z.bottom !== undefined ? {bottom: z.bottom} : {top: z.y}),
  display: 'flex',
  flexDirection: 'column',
  alignItems: z.align === 'right' ? 'flex-end' : z.align === 'center' ? 'center' : 'flex-start',
  textAlign: z.align,
});
