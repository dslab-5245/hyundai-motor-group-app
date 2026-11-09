import React from 'react';
import {AbsoluteFill, Img, random, staticFile, useCurrentFrame} from 'remotion';

/** Film grain: a tiled noise texture offset randomly per frame (deterministic), very faint. */
export const Grain: React.FC<{src?: string; opacity?: number}> = ({src, opacity = 0.06}) => {
  const f = useCurrentFrame();
  if (!src) return null;
  const dx = Math.floor(random(`gx${f}`) * 512);
  const dy = Math.floor(random(`gy${f}`) * 512);
  return (
    <AbsoluteFill style={{overflow: 'hidden', pointerEvents: 'none', opacity, mixBlendMode: 'overlay'}}>
      <div style={{position: 'absolute', left: -dx, top: -dy, width: '200%', height: '200%', backgroundImage: `url(${staticFile(src)})`, backgroundRepeat: 'repeat', backgroundSize: '512px 512px'}} />
      <Img src={staticFile(src)} style={{display: 'none'}} />
    </AbsoluteFill>
  );
};
