import { decl } from '../decl';
const FLEX_DIR_VALUE = {
    row: 'row',
    column: 'column',
    // Penpot's reverse directions store children in visual order (leftmost/topmost first),
    // unlike regular row/column where children are in Z-order (rightmost/bottommost first).
    // Using row / column (not CSS reverse) keeps the same justify-content semantics.
    'row-reverse': 'row',
    'column-reverse': 'column',
};
const ALIGN_ITEMS_VALUE = {
    start: 'flex-start',
    center: 'center',
    end: 'flex-end',
    stretch: 'stretch',
    'space-between': 'space-between',
    'space-around': 'space-around',
    'space-evenly': 'space-evenly',
};
const JUSTIFY_CONTENT_VALUE = {
    start: 'flex-start',
    center: 'center',
    end: 'flex-end',
    stretch: 'stretch',
    'space-between': 'space-between',
    'space-around': 'space-around',
    'space-evenly': 'space-evenly',
};
// Detect wrap from stored child positions when the row/column they fall on
// can't be explained by single-line align variations. Penpot occasionally
// keeps `layoutWrapType: 'nowrap'` while its canvas lays children out across
// multiple rows/columns, so the data alone can't be trusted.
export function frameWillWrap(shape, children) {
    if (shape.layoutWrapType === 'wrap')
        return true;
    return childrenIndicateWrap(shape, children);
}
function childrenIndicateWrap(shape, children) {
    const dir = shape.layoutFlexDir;
    const isRow = dir === 'row' || dir === 'row-reverse' || dir === undefined;
    const isCol = dir === 'column' || dir === 'column-reverse';
    if (!isRow && !isCol)
        return false;
    // `layoutItemAbsolute` children are pulled out of the flex flow; their
    // stored x/y is independent of the layout and would skew the heuristic.
    const flowChildren = children.filter((c) => !c.layoutItemAbsolute);
    if (flowChildren.length < 2)
        return false;
    const dims = flowChildren.map((c) => ({
        pos: isRow ? (c.y ?? c.selrect?.y ?? 0) : (c.x ?? c.selrect?.x ?? 0),
        size: isRow ? (c.height ?? c.selrect?.height ?? 0) : (c.width ?? c.selrect?.width ?? 0),
    }));
    const maxSize = Math.max(...dims.map((d) => d.size));
    if (maxSize <= 0)
        return false;
    const range = Math.max(...dims.map((d) => d.pos)) - Math.min(...dims.map((d) => d.pos));
    // Within a single line, mixed-height align variations spread positions by
    // at most (max - min) child size. Crossing into a new row/column requires
    // an offset of at least the tallest/widest child.
    return range >= maxSize;
}
export function flexContainerStyle(shape, children = []) {
    const parts = [decl.display('flex')];
    if (shape.layoutFlexDir) {
        parts.push(decl.flexDirection(FLEX_DIR_VALUE[shape.layoutFlexDir]));
    }
    const alignValue = shape.layoutAlignItems ? ALIGN_ITEMS_VALUE[shape.layoutAlignItems] : undefined;
    if (alignValue)
        parts.push(decl.alignItems(alignValue));
    const justifyValue = shape.layoutJustifyContent
        ? JUSTIFY_CONTENT_VALUE[shape.layoutJustifyContent]
        : undefined;
    if (justifyValue)
        parts.push(decl.justifyContent(justifyValue));
    // Penpot's data sometimes keeps `layoutWrapType: 'nowrap'` even when its
    // canvas laid the children across multiple rows/columns, so trust the
    // stored positions over the flag.
    if (frameWillWrap(shape, children)) {
        parts.push(decl.flexWrap('wrap'));
    }
    return parts.join(' ');
}
export function flexSpacingStyle(shape) {
    const parts = [];
    const rawGap = shape
        .layoutGap;
    const rowGap = shape.layoutRowGap ?? rawGap?.rowGap;
    const colGap = shape.layoutColumnGap ?? rawGap?.columnGap;
    if (rowGap !== undefined && colGap !== undefined) {
        if (rowGap === colGap) {
            parts.push(decl.gap(rowGap));
        }
        else {
            parts.push(decl.rowGap(rowGap));
            parts.push(decl.columnGap(colGap));
        }
    }
    else if (rowGap !== undefined) {
        parts.push(decl.rowGap(rowGap));
    }
    else if (colGap !== undefined) {
        parts.push(decl.columnGap(colGap));
    }
    const pad = shape.layoutPadding;
    if (pad) {
        const { p1, p2, p3, p4 } = pad;
        if (p1 === p2 && p2 === p3 && p3 === p4) {
            parts.push(decl.padding(p1));
        }
        else {
            parts.push(decl.padding([p1, p2, p3, p4]));
        }
    }
    return parts.join(' ');
}
//# sourceMappingURL=flex.js.map