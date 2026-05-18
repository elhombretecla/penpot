import { mergeStyles } from '../utils/style';
import { blendModeToStyle, opacityToStyle, hiddenToStyle } from './blend';
import { blurToStyle } from './blur';
import { shadowsToStyle } from './shadows';
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
export function baseStyles(shape, _ctx) {
    return mergeStyles(opacityToStyle(shape.opacity), blendModeToStyle(shape.blendMode), hiddenToStyle(shape.hidden), blurToStyle(shape.blur), radiusToStyle(shape), shadowsToStyle(shape.shadow), combinedTransformStyle(shape), zIndexStyle(shape));
}
//# sourceMappingURL=base.js.map