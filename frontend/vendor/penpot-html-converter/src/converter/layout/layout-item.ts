import type { ShapeCommon, FrameShape, LayoutItemAlignSelf } from '../../penpot.types';
import { mergeStyles } from '../utils/style';
import { isIdentityMatrix } from '../utils/transform';
import { decl } from '../decl';

export function layoutItemSizingStyle(
  shape: ShapeCommon,
  parent: FrameShape,
  parentWraps = false,
): string {
  const isRowDir =
    parent.layoutFlexDir === 'row' ||
    parent.layoutFlexDir === 'row-reverse' ||
    parent.layoutFlexDir === undefined;

  const hSizing = shape.layoutItemHSizing;
  const vSizing = shape.layoutItemVSizing;

  // Path shapes carry geometry in `selrect` and leave `width`/`height` null.
  // Fall back to selrect so flex sizing doesn't collapse them to 0.
  const w = shape.width ?? shape.selrect?.width ?? 0;
  const h = shape.height ?? shape.selrect?.height ?? 0;

  const parts: string[] = [];
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
        parts.push(decl.flex(`1 0 ${w}px`));
        parts.push(decl.width(w));
      } else {
        parts.push(decl.flex('1'));
      }
    } else {
      parts.push(decl.width(w));
      hIsExplicit = true;
    }
  } else if (hSizing !== 'auto') {
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
    } else if (parentWraps) {
      parts.push(decl.flex(`1 0 ${h}px`));
      parts.push(decl.height(h));
    } else {
      parts.push(decl.flex('1'));
    }
  } else if (vSizing !== 'auto') {
    parts.push(decl.height(h));
    vIsExplicit = true;
  }

  // Penpot's layout engine NEVER shrinks children to make them fit — an
  // over-full container simply overflows (the design scrolls). CSS defaults
  // to `flex-shrink: 1`, which compresses fix/hug children of an over-full
  // column/row (screens taller than their board squeeze every section, and
  // with min-width/height reset to 0 there is no content-minimum backstop
  // left). Emit `flex-shrink: 0` for every item whose MAIN axis is not
  // `fill` — fill items manage shrinking through their `flex` shorthand.
  const mainSizing = isRowDir ? hSizing : vSizing;
  if (mainSizing !== 'fill') parts.push(decl.flexShrink(0));

  return parts.join(' ');
}

// A transformed flex child occupies its ROTATED bounding box in Penpot's
// layout, but the emitted element keeps its untransformed selrect dims (the
// CSS transform is visual-only and doesn't affect flow). Compensate with
// per-axis margins of (AABB - box)/2 — negative when the box is wider than
// its AABB — so the flex footprint matches Penpot's while the transform
// still rotates about the element (= slot) center.
function rotatedFootprintDelta(shape: ShapeCommon): { dx: number; dy: number } | null {
  const m = shape.transform;
  if (!m || isIdentityMatrix(m)) return null;
  if (shape.layoutItemAbsolute) return null;
  const w = shape.width ?? shape.selrect?.width ?? 0;
  const h = shape.height ?? shape.selrect?.height ?? 0;
  const bw = Math.abs(m.a) * w + Math.abs(m.c) * h;
  const bh = Math.abs(m.b) * w + Math.abs(m.d) * h;
  const dx = (bw - w) / 2;
  const dy = (bh - h) / 2;
  if (Math.abs(dx) < 0.01 && Math.abs(dy) < 0.01) return null;
  return { dx, dy };
}

export function layoutItemMarginStyle(shape: ShapeCommon): string {
  const margin = shape.layoutItemMargin;
  const delta = rotatedFootprintDelta(shape);
  if (!margin && !delta) return '';

  const round = (n: number) => Math.round(n * 100) / 100;
  const m1 = round((margin?.m1 ?? 0) + (delta?.dy ?? 0));
  const m2 = round((margin?.m2 ?? 0) + (delta?.dx ?? 0));
  const m3 = round((margin?.m3 ?? 0) + (delta?.dy ?? 0));
  const m4 = round((margin?.m4 ?? 0) + (delta?.dx ?? 0));

  if (m1 === m2 && m2 === m3 && m3 === m4) {
    return decl.margin(m1);
  }

  return decl.margin([m1, m2, m3, m4]);
}

const ALIGN_SELF_VALUE: Record<LayoutItemAlignSelf, Parameters<typeof decl.alignSelf>[0]> = {
  start: 'flex-start',
  center: 'center',
  end: 'flex-end',
  stretch: 'stretch',
};

export function layoutItemAlignSelfStyle(shape: ShapeCommon): string {
  if (shape.layoutItemAlignSelf === undefined) return '';
  return decl.alignSelf(ALIGN_SELF_VALUE[shape.layoutItemAlignSelf]);
}

export function layoutItemMinMaxStyle(shape: ShapeCommon): string {
  // Penpot only applies layoutItemMin*/Max* when the axis grows/shrinks
  // (sizing = fill / auto). For a fix axis the size is explicit and min/max
  // are stale data — emitting them would let min-width / min-height override
  // the declared width / height in CSS.
  const hFixed = shape.layoutItemHSizing === undefined || shape.layoutItemHSizing === 'fix';
  const vFixed = shape.layoutItemVSizing === undefined || shape.layoutItemVSizing === 'fix';

  const parts: string[] = [];
  if (!hFixed) {
    if (shape.layoutItemMinW !== undefined) parts.push(decl.minWidth(shape.layoutItemMinW));
    if (shape.layoutItemMaxW !== undefined) parts.push(decl.maxWidth(shape.layoutItemMaxW));
  }
  if (!vFixed) {
    if (shape.layoutItemMinH !== undefined) parts.push(decl.minHeight(shape.layoutItemMinH));
    if (shape.layoutItemMaxH !== undefined) parts.push(decl.maxHeight(shape.layoutItemMaxH));
  }
  return parts.join(' ');
}

export function layoutItemZIndexStyle(shape: ShapeCommon): string {
  if (shape.layoutItemZIndex === undefined || shape.layoutItemZIndex === 0) return '';
  return decl.zIndex(shape.layoutItemZIndex);
}

export function layoutItemAbsoluteStyle(shape: ShapeCommon, offsetX = 0, offsetY = 0): string {
  if (!shape.layoutItemAbsolute) return '';
  const x = (shape.x ?? 0) - offsetX;
  const y = (shape.y ?? 0) - offsetY;
  return mergeStyles(decl.position('absolute'), decl.left(x), decl.top(y));
}
