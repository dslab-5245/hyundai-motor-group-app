// Where things sit, per orientation and panel crop. All numbers are composition pixels.
export const PANE = {x: 640, y: 101, w: 1252, h: 821}; // the app pane inside the 1920x1080 Pleos display
export const RING = {x: 0.168, y: 0.474}; // the Flow ring centre, as a fraction of the pane

export interface Layout {
  panel: {left: number; top: number; width: number; height: number};
  perspective: string;
  textLeft: {x: number; y: number; w: number};
  textTop: {x: number; y: number; w: number};
  callout: {x: number; y: number; w: number; align: 'left' | 'right' | 'center'};
  label: {x: number; y: number; w: number};
  spectrum: {y: number; h: number};
}

export const layoutFor = (width: number, height: number, crop: 'pane' | 'full'): Layout => {
  const vertical = height > width;
  if (vertical) {
    const w = 1000;
    const h = Math.round((w * PANE.h) / PANE.w);
    return {
      panel: {left: (width - w) / 2, top: 740, width: w, height: h},
      perspective: 'perspective(2200px) rotateX(6deg)',
      textLeft: {x: 72, y: 330, w: width - 144},
      textTop: {x: 72, y: 330, w: width - 144},
      callout: {x: 72, y: 740 + h + 70, w: width - 144, align: 'center'},
      label: {x: 72, y: 740 + h + 70, w: width - 144},
      spectrum: {y: height - 150, h: 90},
    };
  }
  if (crop === 'full') {
    const w = 1560;
    const h = Math.round((w * 1080) / 1920);
    return {
      panel: {left: (width - w) / 2, top: 56, width: w, height: h},
      perspective: 'perspective(3200px) rotateY(-3deg) rotateX(1.5deg)',
      textLeft: {x: 120, y: 400, w: 640},
      textTop: {x: 120, y: 110, w: 900},
      callout: {x: width - 180 - 620, y: 56 + h + 22, w: 620, align: 'right'},
      label: {x: 180, y: 56 + h + 22, w: 980},
      spectrum: {y: height - 40, h: 26},
    };
  }
  const w = 1040;
  const h = Math.round((w * PANE.h) / PANE.w);
  return {
    panel: {left: 770, top: 150, width: w, height: h},
    perspective: 'perspective(1900px) rotateY(-10deg) rotateX(2deg)',
    textLeft: {x: 120, y: 370, w: 630},
    textTop: {x: 120, y: 110, w: 900},
    callout: {x: 120, y: 700, w: 630, align: 'left'},
    label: {x: 120, y: 700, w: 630},
    spectrum: {y: height - 110, h: 80},
  };
};
