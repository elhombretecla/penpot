import { mergeStyles } from '../utils/style';
import { blendModeToStyle, opacityToStyle, hiddenToStyle } from './blend';
import { blurToStyle } from './blur';
import { shadowsToStyle, shadowsToTextStyle, shadowsToFilterStyle } from './shadows';
import { radiusToStyle } from './radius';
import { combinedTransformStyle } from './position';
import { decl } from '../decl';
// Penpot only models z-index via `:layout-item-z-index`, so we mirror
// it onto the shape's inline style for every shape — not just those
// inside a flex/grid layout. That way:
//   • Shapes inside a layout still get the same `z-index` they did
//     before (the original `layoutItemZIndexStyle` was redundant with
//     this; it's still emitted there for compatibility).
//   • Shapes inside plain frames (where Penpot persists the property
//     but the previous code dropped it) now expose `z-index` too, so
//     the HTML inspector shows it AND the painted stacking order
//     respects it.
function zIndexStyle(shape) {
    const z = shape.layoutItemZIndex;
    if (z === undefined || z === null || z === 0)
        return '';
    return decl.zIndex(z);
}
export function baseStyles(shape, ctx, opts = {}) {
    const shadowMode = opts.shadows ?? 'box';
    const shadowStyle = shadowMode === 'text'
        ? shadowsToTextStyle(shape.shadow)
        : shadowMode === 'filter'
            ? shadowsToFilterStyle(shape.shadow)
            : shadowsToStyle(shape.shadow);
    return mergeStyles(opacityToStyle(shape.opacity), blendModeToStyle(shape.blendMode), hiddenToStyle(shape.hidden), blurToStyle(shape.blur), radiusToStyle(shape), shadowStyle, 
    // `transform: false` marks shapes whose visual content is already baked
    // in page coordinates (paths, bools): they skip their own matrix but
    // still need the counter-transform of any transformed ancestor.
    opts.transform === false
        ? combinedTransformStyle(shape, ctx, true)
        : combinedTransformStyle(shape, ctx), zIndexStyle(shape));
}
//# sourceMappingURL=base.js.map