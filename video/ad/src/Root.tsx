import React from 'react';
import {Composition, staticFile} from 'remotion';
import {Film, type FilmProps} from './Film';
import type {Plan} from './plan';
import {CockpitSpike} from './scene3d/CockpitSpike';

let cached: Promise<Plan> | null = null;
const loadPlan = (): Promise<Plan> => {
  if (!cached) cached = fetch(staticFile('run/plan.json')).then((r) => r.json());
  return cached;
};

const comps: Array<{id: string; w: number; h: number}> = [
  {id: 'Ad16x9', w: 1920, h: 1080},
  {id: 'Short9x16', w: 1080, h: 1920},
  {id: 'DemoFull16x9', w: 1920, h: 1080},
];

export const Root: React.FC = () => (
  <>
    {comps.map((c) => (
      <Composition
        key={c.id}
        id={c.id}
        component={Film as unknown as React.FC<Record<string, unknown>>}
        width={c.w}
        height={c.h}
        fps={30}
        durationInFrames={300}
        defaultProps={{comp: c.id, plan: null}}
        calculateMetadata={async ({props}) => {
          const plan = await loadPlan();
          const p = props as unknown as FilmProps;
          const cp = plan.compositions[p.comp];
          return {durationInFrames: cp.duration, width: cp.width, height: cp.height, fps: plan.fps, props: {...p, plan} as unknown as Record<string, unknown>};
        }}
      />
    ))}
    <Composition id="Cockpit3D" component={CockpitSpike} width={1920} height={1080} fps={30} durationInFrames={240} />
  </>
);
