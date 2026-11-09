import React from 'react';
import {Composition, staticFile} from 'remotion';
import {Film, type FilmProps} from './Film';
import type {Plan} from './plan';
import type {Signals} from './scene3d/signals';
import {CockpitSpike} from './scene3d/CockpitSpike';
import {ExteriorSpike} from './scene3d/ExteriorSpike';

let cached: Promise<{plan: Plan; signals: Signals | null}> | null = null;
const load = () => {
  if (!cached) {
    cached = fetch(staticFile('run/plan.json'))
      .then((r) => r.json() as Promise<Plan>)
      .then(async (plan) => {
        let signals: Signals | null = null;
        if (plan.signals) {
          try {
            signals = (await (await fetch(staticFile(plan.signals))).json()) as Signals;
          } catch {
            signals = null;
          }
        }
        return {plan, signals};
      });
  }
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
        defaultProps={{comp: c.id, plan: null, signals: null}}
        calculateMetadata={async ({props}) => {
          const {plan, signals} = await load();
          const p = props as unknown as FilmProps;
          const cp = plan.compositions[p.comp];
          return {durationInFrames: cp.duration, width: cp.width, height: cp.height, fps: plan.fps, props: {...p, plan, signals} as unknown as Record<string, unknown>};
        }}
      />
    ))}
    <Composition id="Cockpit3D" component={CockpitSpike} width={1920} height={1080} fps={30} durationInFrames={240} />
    <Composition id="Exterior3D" component={ExteriorSpike} width={1920} height={1080} fps={30} durationInFrames={300} />
  </>
);
