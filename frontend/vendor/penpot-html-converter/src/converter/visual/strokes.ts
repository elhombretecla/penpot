import type { Stroke } from '../../penpot.types';
import { hexOpacityToCss } from '../utils/color';
import { tokenToCssVar } from '../tokens';
import { decl } from '../decl';

const STROKE_STYLE_VALUE: Record<string, string> = {
  solid: 'solid',
  dashed: 'dashed',
  dotted: 'dotted',
};

export function solidStrokeToStyle(
  stroke: Stroke,
  strokeTokenName?: string,
  tokens?: Map<string, string>,
): string {
  if (!stroke.strokeColor && !stroke.strokeWidth) {
    return '';
  }

  const rawColor = stroke.strokeColor
    ? hexOpacityToCss(stroke.strokeColor, stroke.strokeOpacity)
    : 'transparent';
  const color = strokeTokenName ? tokenToCssVar(strokeTokenName, tokens) : rawColor;
  const width = stroke.strokeWidth ?? 1;
  const alignment = stroke.strokeAlignment ?? 'center';

  if (alignment === 'outer') {
    return decl.boxShadow(`0 0 0 ${width}px ${color}`);
  }

  // Penpot strokes never consume layout space — they paint over the shape's
  // geometry. A CSS `border` (with the document's box-sizing: border-box)
  // shrinks the content box instead, shifting flex/grid children inwards and
  // breaking layouts that Penpot measured without the stroke. Solid strokes
  // therefore map to layout-neutral box-shadow rings (they also follow
  // border-radius). Dashed/dotted strokes can't be expressed as a shadow, so
  // they keep the border mapping and accept the small content-box deviation.
  const borderStyle = stroke.strokeStyle
    ? (STROKE_STYLE_VALUE[stroke.strokeStyle] ?? 'solid')
    : 'solid';
  if (borderStyle !== 'solid') {
    return decl.border(`${width}px ${borderStyle} ${color}`);
  }

  if (alignment === 'inner') {
    return decl.boxShadow(`inset 0 0 0 ${width}px ${color}`);
  }

  // center alignment: half the width inside, half outside.
  const half = width / 2;
  return decl.boxShadow(`inset 0 0 0 ${half}px ${color}, 0 0 0 ${half}px ${color}`);
}
