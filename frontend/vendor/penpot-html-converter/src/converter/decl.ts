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

/** Numeric pixel value, or a CSS keyword the converter actually emits. */
export type Length = number | 'auto' | '100%';

/** Single value applies to all four sides; tuple is `[top, right, bottom, left]`. */
export type Box4 = number | [number, number, number, number];

function lengthValue(v: Length): string {
  return typeof v === 'number' ? px(v) : v;
}

function box4Value(v: Box4): string {
  if (typeof v === 'number') return px(v);
  // The converter emits raw `${n}px` (no rounding) for the tuple form. Keep
  // that to preserve byte-for-byte output while migrating call sites.
  return `${v[0]}px ${v[1]}px ${v[2]}px ${v[3]}px`;
}

function fmt(prop: string, value: string): string {
  return `${prop}: ${value};`;
}

export const decl = {
  // Display & visibility
  display: (
    v: 'flex' | 'grid' | 'block' | 'inline' | 'inline-block' | 'inline-flex' | 'none',
  ) => fmt('display', v),
  overflow: (v: 'visible' | 'hidden' | 'auto' | 'scroll') => fmt('overflow', v),
  opacity: (v: number) => fmt('opacity', String(v)),

  // Position
  position: (v: 'static' | 'relative' | 'absolute' | 'fixed' | 'sticky') =>
    fmt('position', v),
  top: (v: Length) => fmt('top', lengthValue(v)),
  right: (v: Length) => fmt('right', lengthValue(v)),
  bottom: (v: Length) => fmt('bottom', lengthValue(v)),
  left: (v: Length) => fmt('left', lengthValue(v)),
  zIndex: (v: number) => fmt('z-index', String(v)),

  // Sizing
  width: (v: Length) => fmt('width', lengthValue(v)),
  height: (v: Length) => fmt('height', lengthValue(v)),
  minWidth: (v: Length) => fmt('min-width', lengthValue(v)),
  minHeight: (v: Length) => fmt('min-height', lengthValue(v)),
  maxWidth: (v: Length) => fmt('max-width', lengthValue(v)),
  maxHeight: (v: Length) => fmt('max-height', lengthValue(v)),

  // Spacing
  margin: (v: Box4) => fmt('margin', box4Value(v)),
  padding: (v: Box4) => fmt('padding', box4Value(v)),

  // Flex container
  flexDirection: (v: 'row' | 'column' | 'row-reverse' | 'column-reverse') =>
    fmt('flex-direction', v),
  flexWrap: (v: 'wrap' | 'nowrap' | 'wrap-reverse') => fmt('flex-wrap', v),
  justifyContent: (
    v:
      | 'flex-start'
      | 'flex-end'
      | 'center'
      | 'space-between'
      | 'space-around'
      | 'space-evenly'
      | 'stretch',
  ) => fmt('justify-content', v),
  alignItems: (
    v:
      | 'flex-start'
      | 'flex-end'
      | 'center'
      | 'stretch'
      | 'baseline'
      | 'space-between'
      | 'space-around'
      | 'space-evenly',
  ) => fmt('align-items', v),
  alignContent: (
    v:
      | 'flex-start'
      | 'flex-end'
      | 'center'
      | 'stretch'
      | 'space-between'
      | 'space-around'
      | 'space-evenly',
  ) => fmt('align-content', v),
  gap: (v: number) => fmt('gap', px(v)),
  rowGap: (v: number) => fmt('row-gap', px(v)),
  columnGap: (v: number) => fmt('column-gap', px(v)),

  // Flex item
  flex: (v: string) => fmt('flex', v),
  flexShrink: (v: number) => fmt('flex-shrink', String(v)),
  alignSelf: (
    v:
      | 'auto'
      | 'start'
      | 'end'
      | 'flex-start'
      | 'flex-end'
      | 'center'
      | 'stretch'
      | 'baseline',
  ) => fmt('align-self', v),
  justifySelf: (v: 'auto' | 'start' | 'end' | 'center' | 'stretch') =>
    fmt('justify-self', v),

  // Grid
  gridTemplateColumns: (v: string) => fmt('grid-template-columns', v),
  gridTemplateRows: (v: string) => fmt('grid-template-rows', v),
  gridRowStart: (v: number | string) => fmt('grid-row-start', String(v)),
  gridRowEnd: (v: number | string) => fmt('grid-row-end', String(v)),
  gridColumnStart: (v: number | string) => fmt('grid-column-start', String(v)),
  gridColumnEnd: (v: number | string) => fmt('grid-column-end', String(v)),

  // Background. Penpot fills can be solid colours, gradients, or images, so
  // every value here is a raw CSS string composed by `visual/fills.ts`.
  background: (v: string) => fmt('background', v),
  backgroundColor: (v: string) => fmt('background-color', v),
  backgroundImage: (v: string) => fmt('background-image', v),
  backgroundSize: (v: string) => fmt('background-size', v),
  backgroundPosition: (v: string) => fmt('background-position', v),
  backgroundRepeat: (v: string) => fmt('background-repeat', v),

  // Color & borders
  color: (v: string) => fmt('color', v),
  border: (v: string) => fmt('border', v),
  borderRadius: (v: Length | [number, number, number, number]) => {
    if (Array.isArray(v)) {
      return fmt('border-radius', `${v[0]}px ${v[1]}px ${v[2]}px ${v[3]}px`);
    }
    return fmt('border-radius', lengthValue(v));
  },
  boxShadow: (v: string) => fmt('box-shadow', v),

  // Effects
  filter: (v: string) => fmt('filter', v),
  backdropFilter: (v: string) => fmt('backdrop-filter', v),
  mixBlendMode: (
    v:
      | 'multiply'
      | 'screen'
      | 'overlay'
      | 'darken'
      | 'lighten'
      | 'color-dodge'
      | 'color-burn'
      | 'hard-light'
      | 'soft-light'
      | 'difference'
      | 'exclusion'
      | 'hue'
      | 'saturation'
      | 'color'
      | 'luminosity',
  ) => fmt('mix-blend-mode', v),
  transform: (v: string) => fmt('transform', v),

  // Typography
  fontFamily: (v: string) => fmt('font-family', v),
  fontSize: (v: number) => fmt('font-size', px(v)),
  fontWeight: (v: number | string) => fmt('font-weight', String(v)),
  fontStyle: (v: 'normal' | 'italic' | 'oblique') => fmt('font-style', v),
  lineHeight: (v: number | string) => fmt('line-height', String(v)),
  letterSpacing: (v: number) => fmt('letter-spacing', `${v}px`),
  textAlign: (v: 'left' | 'right' | 'center' | 'justify' | 'start' | 'end') =>
    fmt('text-align', v),
  textTransform: (v: 'uppercase' | 'lowercase' | 'capitalize' | 'none') =>
    fmt('text-transform', v),
  textDecoration: (v: 'underline' | 'line-through' | 'none') =>
    fmt('text-decoration', v),
  whiteSpace: (v: 'normal' | 'nowrap' | 'pre' | 'pre-line' | 'pre-wrap') =>
    fmt('white-space', v),
} as const;

/** Runtime list of every CSS property name the converter can emit. */
export const SUPPORTED_PROPS = Object.keys(decl) as readonly (keyof typeof decl)[];
