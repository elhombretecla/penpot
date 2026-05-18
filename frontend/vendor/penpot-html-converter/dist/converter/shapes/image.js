import { tag } from '../utils/html';
import { mergeStyles } from '../utils/style';
import { resolvePositionOutput } from '../visual/position';
import { baseStyles } from '../visual/base';
export function renderImage(shape, ctx) {
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
//# sourceMappingURL=image.js.map