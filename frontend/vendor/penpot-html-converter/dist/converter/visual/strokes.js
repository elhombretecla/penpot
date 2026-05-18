import { hexOpacityToCss } from '../utils/color';
import { tokenToCssVar } from '../tokens';
import { decl } from '../decl';
const STROKE_STYLE_VALUE = {
    solid: 'solid',
    dashed: 'dashed',
    dotted: 'dotted',
};
export function solidStrokeToStyle(stroke, strokeTokenName, tokens) {
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
    // inner and center alignment — border is drawn inside the element's dimensions
    // (box-sizing: border-box keeps it within bounds).
    const borderStyle = stroke.strokeStyle
        ? (STROKE_STYLE_VALUE[stroke.strokeStyle] ?? 'solid')
        : 'solid';
    return decl.border(`${width}px ${borderStyle} ${color}`);
}
//# sourceMappingURL=strokes.js.map