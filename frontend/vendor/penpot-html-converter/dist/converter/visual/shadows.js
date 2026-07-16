import { hexOpacityToCss } from '../utils/color';
import { decl } from '../decl';
export function shadowToStyle(shadow) {
    if (shadow.hidden)
        return '';
    const color = hexOpacityToCss(shadow.color.color, shadow.color.opacity);
    const { offsetX, offsetY, blur, spread } = shadow;
    const inset = shadow.style === 'inner-shadow' ? 'inset ' : '';
    return `${inset}${offsetX}px ${offsetY}px ${blur}px ${spread}px ${color}`;
}
export function shadowsToStyle(shadows) {
    if (!shadows || shadows.length === 0)
        return '';
    const values = shadows.map(shadowToStyle).filter(Boolean);
    if (values.length === 0)
        return '';
    return decl.boxShadow(values.join(', '));
}
// Text shapes must NOT use box-shadow: it paints a rectangle around the
// whole text box instead of shadowing the glyphs. CSS text-shadow has no
// spread and no inset, so spread is dropped and inner shadows are skipped
// (Penpot renders text shadows with an SVG drop-shadow filter, which has
// the same limitations).
export function shadowsToTextStyle(shadows) {
    if (!shadows || shadows.length === 0)
        return '';
    const values = shadows
        .filter((s) => !s.hidden && s.style !== 'inner-shadow')
        .map((s) => {
        const color = hexOpacityToCss(s.color.color, s.color.opacity);
        return `${s.offsetX}px ${s.offsetY}px ${s.blur}px ${color}`;
    });
    if (values.length === 0)
        return '';
    return `text-shadow: ${values.join(', ')};`;
}
// Shapes rendered as inline <svg> over a transparent box (paths, bools,
// svg-only groups) and plain groups (a sizeless wrapper around arbitrary
// children) need filter: drop-shadow() so the shadow follows the painted
// silhouette instead of the bounding rectangle. drop-shadow() has no
// spread and no inset; inner shadows are skipped, matching what Penpot's
// own SVG filter pipeline shows for these shapes.
export function shadowsToFilterStyle(shadows) {
    if (!shadows || shadows.length === 0)
        return '';
    const values = shadows
        .filter((s) => !s.hidden && s.style !== 'inner-shadow')
        .map((s) => {
        const color = hexOpacityToCss(s.color.color, s.color.opacity);
        return `drop-shadow(${s.offsetX}px ${s.offsetY}px ${s.blur}px ${color})`;
    });
    if (values.length === 0)
        return '';
    return decl.filter(values.join(' '));
}
//# sourceMappingURL=shadows.js.map