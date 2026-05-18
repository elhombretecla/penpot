import { tag } from '../utils/html';
import { mergeStyles } from '../utils/style';
import { resolvePositionOutput } from '../visual/position';
import { baseStyles } from '../visual/base';
import { fillsToOutput } from '../visual/fills';
import { solidStrokeToStyle } from '../visual/strokes';
export function renderRect(shape, _parent, ctx) {
    const base = baseStyles(shape, ctx);
    const fills = fillsToOutput(shape.fills, ctx, shape.appliedTokens?.fill);
    const posStyle = resolvePositionOutput(shape, ctx);
    const firstStroke = (shape.strokes ?? [])[0];
    const stroke = firstStroke
        ? solidStrokeToStyle(firstStroke, shape.appliedTokens?.strokeColor, ctx.tokens)
        : '';
    const style = mergeStyles(posStyle, base, fills, stroke);
    return tag(ctx.tagOverride?.(shape) ?? 'div', { 'data-id': shape.id, 'data-type': shape.type, style: style || undefined }, '');
}
//# sourceMappingURL=rect.js.map