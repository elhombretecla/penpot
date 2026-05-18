import type { ImageShape } from '../../penpot.types';
import type { ConverterContext } from '../types';
import { tag } from '../utils/html';
import { mergeStyles } from '../utils/style';
import { resolvePositionOutput } from '../visual/position';
import { baseStyles } from '../visual/base';

export function renderImage(shape: ImageShape, ctx: ConverterContext): string {
  const base = baseStyles(shape, ctx);
  const src = ctx.resolveImageUrl(shape.metadata.id);
  const posStyle = resolvePositionOutput(shape, ctx);

  const style = mergeStyles(posStyle, base);

  return tag('img', {
    'data-id': shape.id,
    'data-type': shape.type,
    src,
    width: String(shape.width),
    height: String(shape.height),
    alt: '',
    style: style || undefined,
  });
}
