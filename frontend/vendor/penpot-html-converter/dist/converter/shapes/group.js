import { tag } from '../utils/html';
import { mergeStyles } from '../utils/style';
import { resolvePositionOutput } from '../visual/position';
import { baseStyles } from '../visual/base';
import { hexOpacityToCss } from '../utils/color';
import { renderShape } from './dispatch';
function isSvgOnlyGroup(children) {
    const visible = children.filter((c) => !c.hidden);
    if (visible.length === 0)
        return false;
    const hasAbsoluteCoordPath = visible.some((c) => c.type === 'path' && c.x == null);
    if (!hasAbsoluteCoordPath)
        return false;
    return visible.every((c) => c.type === 'path' || c.type === 'circle');
}
function renderPathElement(shape) {
    const fills = shape.fills ?? [];
    const strokes = shape.strokes ?? [];
    const firstFill = fills[0];
    const firstStroke = strokes[0];
    const fillAttr = firstFill?.fillColor
        ? hexOpacityToCss(firstFill.fillColor, firstFill.fillOpacity ?? 1)
        : 'none';
    const strokeAttr = firstStroke?.strokeColor
        ? hexOpacityToCss(firstStroke.strokeColor, firstStroke.strokeOpacity ?? 1)
        : undefined;
    const strokeWidthAttr = firstStroke?.strokeWidth ? String(firstStroke.strokeWidth) : undefined;
    const svgAttrs = shape.svgAttrs ?? {};
    return tag('path', {
        d: shape.content,
        fill: fillAttr,
        stroke: strokeAttr,
        'stroke-width': strokeWidthAttr,
        'fill-rule': svgAttrs['fillRule'],
        'clip-rule': svgAttrs['clipRule'],
    });
}
function renderCircleElement(shape) {
    const fills = shape.fills ?? [];
    const strokes = shape.strokes ?? [];
    const firstFill = fills[0];
    const firstStroke = strokes[0];
    const fillAttr = firstFill?.fillColor
        ? hexOpacityToCss(firstFill.fillColor, firstFill.fillOpacity ?? 1)
        : 'none';
    const strokeAttr = firstStroke?.strokeColor
        ? hexOpacityToCss(firstStroke.strokeColor, firstStroke.strokeOpacity ?? 1)
        : undefined;
    const strokeWidthAttr = firstStroke?.strokeWidth != null ? String(firstStroke.strokeWidth) : undefined;
    const cx = shape.x + shape.width / 2;
    const cy = shape.y + shape.height / 2;
    let rx = shape.width / 2;
    let ry = shape.height / 2;
    if (firstStroke?.strokeWidth) {
        const half = firstStroke.strokeWidth / 2;
        if (firstStroke.strokeAlignment === 'inner') {
            rx -= half;
            ry -= half;
        }
        else if (firstStroke.strokeAlignment === 'outer') {
            rx += half;
            ry += half;
        }
    }
    if (rx === ry) {
        return tag('circle', {
            cx: String(cx),
            cy: String(cy),
            r: String(rx),
            fill: fillAttr,
            stroke: strokeAttr,
            'stroke-width': strokeWidthAttr,
        });
    }
    return tag('ellipse', {
        cx: String(cx),
        cy: String(cy),
        rx: String(rx),
        ry: String(ry),
        fill: fillAttr,
        stroke: strokeAttr,
        'stroke-width': strokeWidthAttr,
    });
}
function renderGroupAsSvg(shape, children, ctx) {
    const vx = shape.x;
    const vy = shape.y;
    const vw = shape.width;
    const vh = shape.height;
    const base = baseStyles(shape, ctx);
    const posStyle = resolvePositionOutput(shape, ctx);
    const style = mergeStyles(posStyle, base);
    const inner = children
        .filter((c) => !c.hidden)
        .map((c) => {
        if (c.type === 'path')
            return renderPathElement(c);
        if (c.type === 'circle')
            return renderCircleElement(c);
        return '';
    })
        .filter(Boolean)
        .join('');
    return tag('svg', {
        'data-id': shape.id,
        'data-type': shape.type,
        width: String(vw),
        height: String(vh),
        viewBox: `${vx} ${vy} ${vw} ${vh}`,
        xmlns: 'http://www.w3.org/2000/svg',
        fill: 'none',
        style: style || undefined,
    }, inner);
}
export function renderGroup(shape, children, objects, ctx) {
    if (isSvgOnlyGroup(children)) {
        return renderGroupAsSvg(shape, children, ctx);
    }
    const base = baseStyles(shape, ctx);
    const posStyle = resolvePositionOutput(shape, ctx);
    const maskStyle = shape.maskedGroup ? 'overflow: hidden;' : '';
    // Group children render with position: absolute offset by the group's x/y.
    // When the group itself is a flex/grid child, resolvePositionOutput emits no
    // CSS position (only layout-item sizing), leaving the div position: static —
    // absolute children would escape to the nearest positioned ancestor. Add
    // position: relative so the group becomes their containing block.
    const needsContainingBlock = ctx._parentIsLayout && children.length > 0;
    const extraPositionStyle = needsContainingBlock ? 'position: relative;' : '';
    const style = mergeStyles(posStyle, extraPositionStyle, base, maskStyle);
    const childCtx = {
        ...ctx,
        _isCanvasTopLevel: false,
        _forceRelative: false,
        _parentIsLayout: false,
        _parentIsLayoutAutoW: undefined,
        _parentIsLayoutAutoH: undefined,
        _parentLayoutItemStyles: undefined,
        _offsetX: shape.x ?? 0,
        _offsetY: shape.y ?? 0,
    };
    const inner = children.map((child) => renderShape(child, objects, childCtx)).join('');
    return tag(ctx.tagOverride?.(shape) ?? 'div', { 'data-id': shape.id, 'data-type': shape.type, style: style || undefined }, inner);
}
//# sourceMappingURL=group.js.map