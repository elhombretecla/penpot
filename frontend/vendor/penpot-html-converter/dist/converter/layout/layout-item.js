import { mergeStyles } from '../utils/style';
import { decl } from '../decl';
export function layoutItemSizingStyle(shape, parent, parentWraps = false) {
    const isRowDir = parent.layoutFlexDir === 'row' ||
        parent.layoutFlexDir === 'row-reverse' ||
        parent.layoutFlexDir === undefined;
    const hSizing = shape.layoutItemHSizing;
    const vSizing = shape.layoutItemVSizing;
    // Path shapes carry geometry in `selrect` and leave `width`/`height` null.
    // Fall back to selrect so flex sizing doesn't collapse them to 0.
    const w = shape.width ?? shape.selrect?.width ?? 0;
    const h = shape.height ?? shape.selrect?.height ?? 0;
    const parts = [];
    let hIsExplicit = false;
    let vIsExplicit = false;
    if (hSizing === 'fill') {
        // Main axis (row → h-fill):
        //   • Non-wrapping parent → `flex: 1` (basis 0) so children share
        //     the row equally.
        //   • Wrapping parent → `flex: 1 0 <w>px`. Setting flex-basis to the
        //     stored width is critical for wrap to behave: with basis 0
        //     every child "wants" 0 width, fits on one line, and never wraps
        //     — that's why the previous code piled `width: 100%` on top and
        //     ended up pushing every child to its own line. With an
        //     explicit basis the line breaks where Penpot's layout breaks,
        //     and flex-grow still lets the items stretch to fill each line.
        //     The duplicated `width: <w>` keeps the position-resolver from
        //     adding its `width: 100%` fallback.
        // Cross axis (column → h-fill): explicit px to prevent content
        // overflow from inflating the flex container's cross-axis size.
        if (isRowDir) {
            if (parentWraps) {
                parts.push(decl.flex('1 0 ' + w + 'px'));
                parts.push(decl.width(w));
            }
            else {
                parts.push(decl.flex('1'));
            }
        }
        else {
            parts.push(decl.width(w));
            hIsExplicit = true;
        }
    }
    else if (hSizing !== 'auto') {
        parts.push(decl.width(w));
        hIsExplicit = true;
    }
    if (vSizing === 'fill') {
        // Cross axis on a wrapping row needs explicit px: `height: 100%` resolves
        // to the parent's inner height, not the per-line height, so the child
        // would inflate well beyond the wrapped row Penpot laid it on.
        // Main axis on a wrapping column (col-dir + v-fill) suffers the
        // mirror-image bug to wrapping-row + h-fill: use an explicit
        // basis so wrap breaks land at Penpot's column boundaries.
        if (isRowDir) {
            parts.push(parentWraps ? decl.height(h) : decl.height('100%'));
        }
        else if (parentWraps) {
            parts.push(decl.flex('1 0 ' + h + 'px'));
            parts.push(decl.height(h));
        }
        else {
            parts.push(decl.flex('1'));
        }
    }
    else if (vSizing !== 'auto') {
        parts.push(decl.height(h));
        vIsExplicit = true;
    }
    // Fix-sized children should not shrink on the main axis. Without this,
    // an item with `width: 36px` + `margin: 30px` inside a flex-row with a
    // 36px content area (padding 30 on a 96 container) hits −60px free space
    // and the default `flex-shrink: 1` collapses the item to min-content (0),
    // making it invisible.
    const mainExplicit = isRowDir ? hIsExplicit : vIsExplicit;
    if (mainExplicit)
        parts.push(decl.flexShrink(0));
    return parts.join(' ');
}
export function layoutItemMarginStyle(shape) {
    const margin = shape.layoutItemMargin;
    if (!margin)
        return '';
    const m1 = margin.m1 ?? 0;
    const m2 = margin.m2 ?? 0;
    const m3 = margin.m3 ?? 0;
    const m4 = margin.m4 ?? 0;
    if (m1 === m2 && m2 === m3 && m3 === m4) {
        return decl.margin(m1);
    }
    return decl.margin([m1, m2, m3, m4]);
}
const ALIGN_SELF_VALUE = {
    start: 'flex-start',
    center: 'center',
    end: 'flex-end',
    stretch: 'stretch',
};
export function layoutItemAlignSelfStyle(shape) {
    if (shape.layoutItemAlignSelf === undefined)
        return '';
    return decl.alignSelf(ALIGN_SELF_VALUE[shape.layoutItemAlignSelf]);
}
export function layoutItemMinMaxStyle(shape) {
    // Penpot only applies layoutItemMin*/Max* when the axis grows/shrinks
    // (sizing = fill / auto). For a fix axis the size is explicit and min/max
    // are stale data — emitting them would let min-width / min-height override
    // the declared width / height in CSS.
    const hFixed = shape.layoutItemHSizing === undefined || shape.layoutItemHSizing === 'fix';
    const vFixed = shape.layoutItemVSizing === undefined || shape.layoutItemVSizing === 'fix';
    const parts = [];
    if (!hFixed) {
        if (shape.layoutItemMinW !== undefined)
            parts.push(decl.minWidth(shape.layoutItemMinW));
        if (shape.layoutItemMaxW !== undefined)
            parts.push(decl.maxWidth(shape.layoutItemMaxW));
    }
    if (!vFixed) {
        if (shape.layoutItemMinH !== undefined)
            parts.push(decl.minHeight(shape.layoutItemMinH));
        if (shape.layoutItemMaxH !== undefined)
            parts.push(decl.maxHeight(shape.layoutItemMaxH));
    }
    return parts.join(' ');
}
export function layoutItemZIndexStyle(shape) {
    if (shape.layoutItemZIndex === undefined || shape.layoutItemZIndex === 0)
        return '';
    return decl.zIndex(shape.layoutItemZIndex);
}
export function layoutItemAbsoluteStyle(shape, offsetX = 0, offsetY = 0) {
    if (!shape.layoutItemAbsolute)
        return '';
    const x = (shape.x ?? 0) - offsetX;
    const y = (shape.y ?? 0) - offsetY;
    return mergeStyles(decl.position('absolute'), decl.left(x), decl.top(y));
}
//# sourceMappingURL=layout-item.js.map