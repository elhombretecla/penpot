import type { Fill, Gradient, GradientStop } from '../../penpot.types';
import type { ConverterContext } from '../types';
import { hexOpacityToCss } from '../utils/color';
import { tokenToCssVar } from '../tokens';
import { decl } from '../decl';

function gradientStopToCss(stop: GradientStop): string {
  const color = hexOpacityToCss(stop.color, stop.opacity);
  const offset = Math.round(stop.offset * 100);
  return `${color} ${offset}%`;
}

export function linearGradientToStyle(gradient: Gradient): string {
  const dx = gradient.endX - gradient.startX;
  const dy = gradient.endY - gradient.startY;
  const angleRad = Math.atan2(dx, -dy);
  const angleDeg = Math.round(angleRad * (180 / Math.PI));
  const normalizedAngle = ((angleDeg % 360) + 360) % 360;

  const stops = gradient.stops.map(gradientStopToCss).join(', ');
  return decl.background(`linear-gradient(${normalizedAngle}deg, ${stops})`);
}

export function radialGradientToStyle(gradient: Gradient): string {
  return decl.background(radialGradientValue(gradient));
}

// Penpot's radial gradient radius is the start→end handle distance, in
// coordinates relative to the shape box. Omitting an explicit size makes
// CSS fall back to `farthest-corner`, which ignores the handle entirely —
// short handles rendered as if they spanned the whole shape. An ellipse
// sized `r×100%` per axis matches Penpot's box-relative radius (exactly a
// circle on square shapes, Penpot's box-stretched ellipse otherwise).
function radialGradientValue(gradient: Gradient): string {
  const dx = gradient.endX - gradient.startX;
  const dy = gradient.endY - gradient.startY;
  const radius = Math.sqrt(dx * dx + dy * dy);

  const stops = gradient.stops.map(gradientStopToCss).join(', ');

  if (radius === 0) {
    return `radial-gradient(circle at 50% 50%, ${stops})`;
  }

  const centerX = Math.round(gradient.startX * 100);
  const centerY = Math.round(gradient.startY * 100);
  const r = Math.round(radius * 10000) / 100;
  return `radial-gradient(ellipse ${r}% ${r}% at ${centerX}% ${centerY}%, ${stops})`;
}

export function solidFillToStyle(fill: Fill): string {
  if (!fill.fillColor) return '';
  const cssColor = hexOpacityToCss(fill.fillColor, fill.fillOpacity);
  return decl.backgroundColor(cssColor);
}

export function imageFillToStyle(fill: Fill, ctx: ConverterContext): string {
  if (!fill.fillImage) return '';
  const url = ctx.resolveImageUrl(fill.fillImage.id);
  const safeUrl = url.replace(/'/g, '%27');
  return [
    decl.backgroundImage(`url('${safeUrl}')`),
    decl.backgroundSize('cover'),
    decl.backgroundPosition('center'),
    decl.backgroundRepeat('no-repeat'),
  ].join(' ');
}

function gradientToImageValue(gradient: Gradient): string {
  const stops = gradient.stops.map(gradientStopToCss).join(', ');
  if (gradient.type === 'linear') {
    const dx = gradient.endX - gradient.startX;
    const dy = gradient.endY - gradient.startY;
    const angleRad = Math.atan2(dx, -dy);
    const angleDeg = Math.round(angleRad * (180 / Math.PI));
    const normalizedAngle = ((angleDeg % 360) + 360) % 360;
    return `linear-gradient(${normalizedAngle}deg, ${stops})`;
  }
  return radialGradientValue(gradient);
}

function solidToImageValue(fill: Fill): string {
  const color = hexOpacityToCss(fill.fillColor!, fill.fillOpacity);
  return `linear-gradient(${color}, ${color})`;
}

interface BgLayer {
  image: string;
  size: string;
  position: string;
  repeat: string;
}

function fillToBgLayer(fill: Fill, ctx: ConverterContext): BgLayer | null {
  if (fill.fillColorGradient) {
    return {
      image: gradientToImageValue(fill.fillColorGradient),
      size: 'auto',
      position: '0% 0%',
      repeat: 'repeat',
    };
  }
  if (fill.fillImage) {
    const url = ctx.resolveImageUrl(fill.fillImage.id).replace(/'/g, '%27');
    return {
      image: `url('${url}')`,
      size: 'cover',
      position: 'center',
      repeat: 'no-repeat',
    };
  }
  if (fill.fillColor) {
    return {
      image: solidToImageValue(fill),
      size: 'auto',
      position: '0% 0%',
      repeat: 'repeat',
    };
  }
  return null;
}

export function fillsToOutput(
  fills: Fill[] | null | undefined,
  ctx: ConverterContext,
  fillTokenName?: string,
): string {
  if (!fills || fills.length === 0) return '';

  // A single fully-transparent fill wins over any applied token — Penpot lets a user
  // reference a color token and then override the opacity to 0 to hide the fill.
  if (fills.length === 1 && fills[0].fillOpacity === 0) return '';

  // The actual fill is the source of truth. Penpot keeps `appliedTokens.fill` even
  // after the user overrides the fill (with a different colour, a gradient, an image,
  // or a non-1 opacity), so we only honour the token when the live fill still matches
  // the token's resolved value.
  if (fillTokenName && ctx.tokens?.has(fillTokenName) && fills.length === 1) {
    const fill = fills[0];
    const tokenColor = ctx.tokens.get(fillTokenName)?.toLowerCase();
    const fillColor = fill.fillColor?.toLowerCase();
    const opacity = fill.fillOpacity ?? 1;
    const tokenStillApplies =
      !fill.fillColorGradient &&
      !fill.fillImage &&
      !!fillColor &&
      fillColor === tokenColor &&
      opacity === 1;
    if (tokenStillApplies) {
      return decl.backgroundColor(tokenToCssVar(fillTokenName, ctx.tokens));
    }
  }

  if (fills.length === 1) {
    const fill = fills[0];
    if (fill.fillColorGradient) {
      return fill.fillColorGradient.type === 'linear'
        ? linearGradientToStyle(fill.fillColorGradient)
        : radialGradientToStyle(fill.fillColorGradient);
    }
    if (fill.fillImage) return imageFillToStyle(fill, ctx);
    if (fill.fillColor) return solidFillToStyle(fill);
    return '';
  }

  // Multiple fills: build CSS layered background-image.
  // Penpot renders fills bottom-to-top (index 0 = bottom).
  // CSS background-image: the first value is on top — so reverse the array.
  const layers: BgLayer[] = [];
  for (const fill of [...fills].reverse()) {
    const layer = fillToBgLayer(fill, ctx);
    if (layer) layers.push(layer);
  }

  if (layers.length === 0) return '';

  return [
    decl.backgroundImage(layers.map((l) => l.image).join(', ')),
    decl.backgroundSize(layers.map((l) => l.size).join(', ')),
    decl.backgroundPosition(layers.map((l) => l.position).join(', ')),
    decl.backgroundRepeat(layers.map((l) => l.repeat).join(', ')),
  ].join(' ');
}
