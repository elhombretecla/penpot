import type { BoolShape } from '../../penpot.types';
import type { ConverterContext } from '../types';
import { tag } from '../utils/html';
import { mergeStyles } from '../utils/style';
import { resolvePositionOutput } from '../visual/position';
import { baseStyles } from '../visual/base';
import { hexOpacityToCss } from '../utils/color';

export function renderBool(shape: BoolShape, ctx: ConverterContext): string {
  const base = baseStyles(shape, ctx);

  const fills = shape.fills ?? [];
  const strokes = shape.strokes ?? [];

  const firstFill = fills[0];
  const firstStroke = strokes[0];

  const fillAttr = firstFill?.fillColor
    ? hexOpacityToCss(firstFill.fillColor, firstFill.fillOpacity)
    : 'none';

  const strokeAttr = firstStroke?.strokeColor
    ? hexOpacityToCss(firstStroke.strokeColor, firstStroke.strokeOpacity)
    : undefined;

  const strokeWidthAttr = firstStroke?.strokeWidth ? String(firstStroke.strokeWidth) : undefined;

  const pathEl = tag('path', {
    d: shape.content,
    transform: `translate(${-(shape.x ?? 0)}, ${-(shape.y ?? 0)})`,
    fill: fillAttr,
    stroke: strokeAttr,
    'stroke-width': strokeWidthAttr,
  });

  const posStyle = resolvePositionOutput(shape, ctx);
  const style = mergeStyles(posStyle, base);

  return tag(
    'svg',
    {
      'data-id': shape.id,
      'data-type': shape.type,
      width: String(shape.width ?? 0),
      height: String(shape.height ?? 0),
      xmlns: 'http://www.w3.org/2000/svg',
      style: style || undefined,
    },
    pathEl,
  );
}
