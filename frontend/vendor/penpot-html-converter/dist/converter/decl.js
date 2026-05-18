/**
 * Single source of truth for every CSS declaration the converter emits.
 *
 * Each entry is a small typed function that returns a `prop: value;` string.
 * Existing visual / layout / shape modules build their composite output by
 * calling these instead of writing template literals — so consumers (the MCP,
 * `html-to-penpot`'s tailwind mapper, future codegen) only have to look here
 * to know what CSS the converter ever produces.
 *
 * Adding a new property: extend this object, optionally extend `Length` /
 * `Box4` if you need a new domain shape, and call it from the relevant
 * shape/visual/layout module. The `SUPPORTED_PROPS` runtime list updates
 * automatically.
 */
import { px } from './utils/css';
function lengthValue(v) {
    return typeof v === 'number' ? px(v) : v;
}
function box4Value(v) {
    if (typeof v === 'number')
        return px(v);
    // The converter emits raw `${n}px` (no rounding) for the tuple form. Keep
    // that to preserve byte-for-byte output while migrating call sites.
    return `${v[0]}px ${v[1]}px ${v[2]}px ${v[3]}px`;
}
function fmt(prop, value) {
    return `${prop}: ${value};`;
}
export const decl = {
    // Display & visibility
    display: (v) => fmt('display', v),
    overflow: (v) => fmt('overflow', v),
    opacity: (v) => fmt('opacity', String(v)),
    // Position
    position: (v) => fmt('position', v),
    top: (v) => fmt('top', lengthValue(v)),
    right: (v) => fmt('right', lengthValue(v)),
    bottom: (v) => fmt('bottom', lengthValue(v)),
    left: (v) => fmt('left', lengthValue(v)),
    zIndex: (v) => fmt('z-index', String(v)),
    // Sizing
    width: (v) => fmt('width', lengthValue(v)),
    height: (v) => fmt('height', lengthValue(v)),
    minWidth: (v) => fmt('min-width', lengthValue(v)),
    minHeight: (v) => fmt('min-height', lengthValue(v)),
    maxWidth: (v) => fmt('max-width', lengthValue(v)),
    maxHeight: (v) => fmt('max-height', lengthValue(v)),
    // Spacing
    margin: (v) => fmt('margin', box4Value(v)),
    padding: (v) => fmt('padding', box4Value(v)),
    // Flex container
    flexDirection: (v) => fmt('flex-direction', v),
    flexWrap: (v) => fmt('flex-wrap', v),
    justifyContent: (v) => fmt('justify-content', v),
    alignItems: (v) => fmt('align-items', v),
    gap: (v) => fmt('gap', px(v)),
    rowGap: (v) => fmt('row-gap', px(v)),
    columnGap: (v) => fmt('column-gap', px(v)),
    // Flex item
    flex: (v) => fmt('flex', v),
    flexShrink: (v) => fmt('flex-shrink', String(v)),
    alignSelf: (v) => fmt('align-self', v),
    justifySelf: (v) => fmt('justify-self', v),
    // Grid
    gridTemplateColumns: (v) => fmt('grid-template-columns', v),
    gridTemplateRows: (v) => fmt('grid-template-rows', v),
    gridRowStart: (v) => fmt('grid-row-start', String(v)),
    gridRowEnd: (v) => fmt('grid-row-end', String(v)),
    gridColumnStart: (v) => fmt('grid-column-start', String(v)),
    gridColumnEnd: (v) => fmt('grid-column-end', String(v)),
    // Background. Penpot fills can be solid colours, gradients, or images, so
    // every value here is a raw CSS string composed by `visual/fills.ts`.
    background: (v) => fmt('background', v),
    backgroundColor: (v) => fmt('background-color', v),
    backgroundImage: (v) => fmt('background-image', v),
    backgroundSize: (v) => fmt('background-size', v),
    backgroundPosition: (v) => fmt('background-position', v),
    backgroundRepeat: (v) => fmt('background-repeat', v),
    // Color & borders
    color: (v) => fmt('color', v),
    border: (v) => fmt('border', v),
    borderRadius: (v) => {
        if (Array.isArray(v)) {
            return fmt('border-radius', `${v[0]}px ${v[1]}px ${v[2]}px ${v[3]}px`);
        }
        return fmt('border-radius', lengthValue(v));
    },
    boxShadow: (v) => fmt('box-shadow', v),
    // Effects
    filter: (v) => fmt('filter', v),
    backdropFilter: (v) => fmt('backdrop-filter', v),
    mixBlendMode: (v) => fmt('mix-blend-mode', v),
    transform: (v) => fmt('transform', v),
    // Typography
    fontFamily: (v) => fmt('font-family', v),
    fontSize: (v) => fmt('font-size', px(v)),
    fontWeight: (v) => fmt('font-weight', String(v)),
    fontStyle: (v) => fmt('font-style', v),
    lineHeight: (v) => fmt('line-height', String(v)),
    letterSpacing: (v) => fmt('letter-spacing', `${v}px`),
    textAlign: (v) => fmt('text-align', v),
    textTransform: (v) => fmt('text-transform', v),
    textDecoration: (v) => fmt('text-decoration', v),
    whiteSpace: (v) => fmt('white-space', v),
};
/** Runtime list of every CSS property name the converter can emit. */
export const SUPPORTED_PROPS = Object.keys(decl);
//# sourceMappingURL=decl.js.map