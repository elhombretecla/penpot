import type { PathShape, StrokeCap } from '../../penpot.types';
import type { ConverterContext } from '../types';
import { tag } from '../utils/html';
import { mergeStyles } from '../utils/style';
import { decl } from '../decl';
import { resolvePositionOutput, combinedTransformStyle } from '../visual/position';
import { blendModeToStyle, opacityToStyle, hiddenToStyle } from '../visual/blend';
import { blurToStyle } from '../visual/blur';
import { shadowsToFilterStyle } from '../visual/shadows';
import { radiusToStyle } from '../visual/radius';
import { hexOpacityToCss } from '../utils/color';

function buildMarkerDef(capType: StrokeCap, color: string, id: string): string | null {
  if (!capType || capType === 'round' || capType === 'square') return null;

  let inner: string;
  let viewBox: string;
  let refX: string;
  let refY: string;
  let markerWidth: string;
  let markerHeight: string;

  switch (capType) {
    case 'line-arrow':
      viewBox = '0 0 3 6';
      refX = '2';
      refY = '3';
      markerWidth = '8.5';
      markerHeight = '8.5';
      inner = tag('path', {
        d: 'M 0.5 0.5 L 3 3 L 0.5 5.5 L 0 5 L 2 3 L 0 1 z',
        fill: color,
        'fill-opacity': '1',
      });
      break;
    case 'triangle-arrow':
      viewBox = '0 0 6 6';
      refX = '3';
      refY = '3';
      markerWidth = '8.5';
      markerHeight = '8.5';
      inner = tag('path', {
        d: 'M 0 0 L 6 3 L 0 6 z',
        fill: color,
        'fill-opacity': '1',
      });
      break;
    case 'circle-marker':
      viewBox = '0 0 6 6';
      refX = '3';
      refY = '3';
      markerWidth = '5';
      markerHeight = '5';
      inner = tag('circle', { cx: '3', cy: '3', r: '2.5', fill: color, 'fill-opacity': '1' });
      break;
    case 'square-marker':
      viewBox = '0 0 6 6';
      refX = '3';
      refY = '3';
      markerWidth = '5';
      markerHeight = '5';
      inner = tag('rect', {
        x: '0.5',
        y: '0.5',
        width: '5',
        height: '5',
        fill: color,
        'fill-opacity': '1',
      });
      break;
    case 'diamond-marker':
      viewBox = '0 0 6 6';
      refX = '3';
      refY = '3';
      markerWidth = '5';
      markerHeight = '5';
      inner = tag('path', {
        d: 'M 3 0 L 6 3 L 3 6 L 0 3 z',
        fill: color,
        'fill-opacity': '1',
      });
      break;
    default:
      return null;
  }

  return tag(
    'marker',
    { id, viewBox, refX, refY, markerWidth, markerHeight, orient: 'auto-start-reverse' },
    inner,
  );
}

export function renderPath(shape: PathShape, ctx: ConverterContext): string {
  const fills = shape.fills ?? [];
  const strokes = shape.strokes ?? [];

  const firstFill = fills[0];
  const firstStroke = strokes[0];

  const x = shape.x ?? shape.selrect?.x ?? 0;
  const y = shape.y ?? shape.selrect?.y ?? 0;
  const width = shape.width ?? shape.selrect?.width ?? 0;
  const height = shape.height ?? shape.selrect?.height ?? 0;

  // A strictly vertical or horizontal line (start.x === end.x or
  // start.y === end.y) lands here with selrect.width or selrect.height ≈ 0.
  // The natural svg viewport — width="0.01" height="174" — collapses in
  // Chromium: even with `overflow: visible`, a sub-pixel-wide viewport is not
  // painted at all and the stroke/marker disappear. Inflate the box (and shift
  // it back the same amount) so the viewport has actual paint area while the
  // path coordinates still land where the design expects.
  const DEGENERATE_PAD = 16;
  const inflateX = width < 1 ? DEGENERATE_PAD : 0;
  const inflateY = height < 1 ? DEGENERATE_PAD : 0;
  const svgX = x - inflateX;
  const svgY = y - inflateY;
  const svgWidth = width + 2 * inflateX;
  const svgHeight = height + 2 * inflateY;

  let fillAttr: string;
  let defsInner = '';
  if (firstFill?.fillImage) {
    const patternId = `img-${shape.id}`;
    const url = ctx.resolveImageUrl(firstFill.fillImage.id);
    const imageEl = tag('image', {
      href: url,
      x: '0',
      y: '0',
      width: String(width),
      height: String(height),
      preserveAspectRatio: 'xMidYMid slice',
    });
    const patternEl = tag(
      'pattern',
      {
        id: patternId,
        patternUnits: 'userSpaceOnUse',
        x: String(x),
        y: String(y),
        width: String(width),
        height: String(height),
      },
      imageEl,
    );
    defsInner += patternEl;
    fillAttr = `url(#${patternId})`;
  } else if (firstFill?.fillColor) {
    fillAttr = hexOpacityToCss(firstFill.fillColor, firstFill.fillOpacity);
  } else {
    fillAttr = 'none';
  }

  const strokeColor = firstStroke?.strokeColor
    ? hexOpacityToCss(firstStroke.strokeColor, firstStroke.strokeOpacity)
    : undefined;

  const strokeWidthAttr = firstStroke?.strokeWidth ? String(firstStroke.strokeWidth) : undefined;

  let markerStartAttr: string | undefined;
  let markerEndAttr: string | undefined;
  let linecap: string | undefined;

  if (firstStroke) {
    const { strokeCapStart, strokeCapEnd } = firstStroke;
    const markerColor = strokeColor ?? 'black';

    const linecapCap = strokeCapStart ?? strokeCapEnd;
    if (linecapCap === 'round' || linecapCap === 'square') {
      linecap = linecapCap;
    }

    if (strokeCapStart && strokeCapStart !== 'round' && strokeCapStart !== 'square') {
      const markerId = `marker-${shape.id}-start`;
      const markerDef = buildMarkerDef(strokeCapStart, markerColor, markerId);
      if (markerDef) {
        defsInner += markerDef;
        markerStartAttr = `url(#${markerId})`;
      }
    }

    if (strokeCapEnd && strokeCapEnd !== 'round' && strokeCapEnd !== 'square') {
      const markerId = `marker-${shape.id}-end`;
      const markerDef = buildMarkerDef(strokeCapEnd, markerColor, markerId);
      if (markerDef) {
        defsInner += markerDef;
        markerEndAttr = `url(#${markerId})`;
      }
    }
  }

  const pathStyleParts: string[] = [];
  if (linecap) pathStyleParts.push(`stroke-linecap:${linecap}`);
  if (markerStartAttr) pathStyleParts.push(`marker-start:${markerStartAttr}`);
  if (markerEndAttr) pathStyleParts.push(`marker-end:${markerEndAttr}`);
  const pathStyle = pathStyleParts.length ? pathStyleParts.join(';') : undefined;

  const pathEl = tag('path', {
    d: shape.content,
    fill: fillAttr,
    stroke: strokeColor,
    'stroke-width': strokeWidthAttr,
    style: pathStyle,
  });

  // Use the (possibly inflated) box for positioning so left/top/width/height
  // match the svg viewport that gets painted. The path's `d` is unchanged and
  // its absolute page coordinates still land at the same visual position
  // because the viewBox is shifted by the same amount.
  const effectiveShape = {
    ...shape,
    x: svgX,
    y: svgY,
    width: svgWidth,
    height: svgHeight,
  };
  const posStyle = resolvePositionOutput(effectiveShape, ctx);

  // Path coordinates in `content` are already in page-absolute space — rotation
  // and matrix are baked into the path data. Applying the shape's own transform
  // would double-transform and distort it; only a transformed ancestor's
  // counter-transform is emitted (combinedTransformStyle with baked=true).
  const baseNoTransform = mergeStyles(
    opacityToStyle(shape.opacity),
    blendModeToStyle(shape.blendMode),
    hiddenToStyle(shape.hidden),
    blurToStyle(shape.blur),
    radiusToStyle(shape),
    // drop-shadow() follows the painted path; box-shadow would draw a
    // rectangle around the (transparent) svg viewport box.
    shadowsToFilterStyle(shape.shadow),
    combinedTransformStyle(effectiveShape, ctx, true),
  );

  // The svg presentation attribute `overflow="visible"` is overridden by the
  // UA stylesheet `svg:not(:root) { overflow: hidden }` (presentation attrs
  // have specificity 0). Setting it inline ensures stroke/marker painted
  // beyond the viewport (e.g. an arrowhead extending past the path endpoint)
  // stay visible.
  const style = mergeStyles(posStyle, baseNoTransform, decl.overflow('visible'));

  const defs = defsInner ? tag('defs', {}, defsInner) : '';

  return tag(
    'svg',
    {
      'data-id': shape.id,
      'data-type': shape.type,
      width: String(svgWidth),
      height: String(svgHeight),
      viewBox: `${svgX} ${svgY} ${svgWidth} ${svgHeight}`,
      overflow: 'visible',
      xmlns: 'http://www.w3.org/2000/svg',
      style: style || undefined,
    },
    defs + pathEl,
  );
}
