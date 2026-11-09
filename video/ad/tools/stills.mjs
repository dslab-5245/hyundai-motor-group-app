// Dev aid: render selected frames of one composition to PNGs with a single bundle.
//   node tools/stills.mjs <compId> <outDir> <frame> [frame...]
import {bundle} from '@remotion/bundler';
import {renderStill, selectComposition} from '@remotion/renderer';
import path from 'node:path';
import fs from 'node:fs';

const [compId, outDir, ...frames] = process.argv.slice(2);
const root = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
fs.mkdirSync(outDir, {recursive: true});
const serveUrl = await bundle({entryPoint: path.join(root, 'src', 'index.ts'), publicDir: path.join(root, 'public')});
const composition = await selectComposition({serveUrl, id: compId, inputProps: {}});
for (const f of frames) {
  const frame = Number(f);
  const output = path.join(outDir, `${compId}_${String(frame).padStart(5, '0')}.png`);
  await renderStill({composition, serveUrl, output, frame, chromiumOptions: {gl: 'angle'}});
  console.log('still', frame, output);
}
