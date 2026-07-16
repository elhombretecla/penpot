import type { TextLeaf, ParagraphNode, TextShape, Typography } from '../../penpot.types';
import type { ConverterContext, FontInfo } from '../types';
import { tag, escapeHtml } from '../utils/html';
import { mergeStyles } from '../utils/style';
import { hexOpacityToCss } from '../utils/color';
import { tokenToCssVar } from '../tokens';
import { resolvePositionOutput } from '../visual/position';
import { baseStyles } from '../visual/base';
import { decl } from '../decl';

export function textLeafToStyles(
  leaf: TextLeaf,
  typographies?: Record<string, Typography>,
): string {
  const resolved: Partial<Typography & TextLeaf> = {};
  if (typographies && leaf.typographyRefId) {
    const typo = typographies[leaf.typographyRefId];
    if (typo) Object.assign(resolved, typo);
  }
  Object.assign(resolved, leaf);

  const parts: string[] = [];

  if (resolved.fontSize) parts.push(decl.fontSize(Number(resolved.fontSize)));
  if (resolved.fontWeight) parts.push(decl.fontWeight(resolved.fontWeight));
  if (resolved.fontFamily) {
    const fontFamily = resolved.fontFamily.replace(/^["']|["']$/g, '');
    parts.push(decl.fontFamily(`'${fontFamily}'`));
  }
  if (resolved.lineHeight) parts.push(decl.lineHeight(resolved.lineHeight));
  if (resolved.textAlign) {
    parts.push(decl.textAlign(resolved.textAlign as Parameters<typeof decl.textAlign>[0]));
  }
  if (resolved.fontStyle === 'italic') parts.push(decl.fontStyle('italic'));
  if (resolved.textDecoration === 'underline') parts.push(decl.textDecoration('underline'));
  if (resolved.textDecoration === 'line-through') parts.push(decl.textDecoration('line-through'));
  if (resolved.textTransform) {
    parts.push(
      decl.textTransform(resolved.textTransform as Parameters<typeof decl.textTransform>[0]),
    );
  }
  if (resolved.letterSpacing && resolved.letterSpacing !== '0') {
    parts.push(decl.letterSpacing(Number(resolved.letterSpacing)));
  }

  return parts.join(' ');
}

export function textLeafColorStyle(
  leaf: TextLeaf,
  fillTokenName?: string,
  tokens?: Map<string, string>,
  fallbackColor?: string,
): string {
  if (fillTokenName) return decl.color(tokenToCssVar(fillTokenName, tokens));
  const fills = leaf.fills;
  if (!fills || fills.length === 0) {
    return fallbackColor ? decl.color(fallbackColor) : '';
  }
  const first = fills[0];
  if (!first.fillColor) return fallbackColor ? decl.color(fallbackColor) : '';
  const cssColor = hexOpacityToCss(first.fillColor, first.fillOpacity);
  return decl.color(cssColor);
}

export function renderParagraph(
  para: ParagraphNode,
  fillTokenName?: string,
  tokens?: Map<string, string>,
  fallbackColor?: string,
  typographies?: Record<string, Typography>,
): string {
  const paraLeaf: TextLeaf = { text: '', ...para };
  const paraBaseStyle = textLeafToStyles(paraLeaf, typographies);
  const firstLeaf = para.children[0];
  const paraColorStyle = firstLeaf
    ? textLeafColorStyle(firstLeaf, fillTokenName, tokens, fallbackColor)
    : '';
  const paraStyle = mergeStyles(paraBaseStyle, paraColorStyle);

  const hasText = para.children.some((leaf) => leaf.text !== '');
  const inner = hasText
    ? para.children
        .map((leaf) => {
          const leafBaseStyle = textLeafToStyles(leaf, typographies);
          const leafColorStyle = textLeafColorStyle(leaf, fillTokenName, tokens, fallbackColor);
          const leafStyle = mergeStyles(leafBaseStyle, leafColorStyle);
          const escapedText = escapeHtml(leaf.text);

          if (leafStyle === paraStyle) return escapedText;

          return tag('span', { style: leafStyle || undefined }, escapedText);
        })
        .join('')
    : '<br />';

  return tag('p', { style: paraStyle || undefined }, inner);
}

function collectLeafFont(
  leaf: TextLeaf,
  collector: Map<string, FontInfo>,
  typographies?: Record<string, Typography>,
): void {
  const resolved: Partial<Typography & TextLeaf> = {};
  if (typographies && leaf.typographyRefId) {
    const typo = typographies[leaf.typographyRefId];
    if (typo) Object.assign(resolved, typo);
  }
  Object.assign(resolved, leaf);

  if (!resolved.fontFamily) return;
  const key = `${resolved.fontFamily}|${resolved.fontWeight ?? ''}|${resolved.fontStyle ?? ''}`;
  if (!collector.has(key)) {
    collector.set(key, {
      fontId: resolved.fontId,
      fontFamily: resolved.fontFamily,
      fontWeight: resolved.fontWeight,
      fontStyle: resolved.fontStyle,
    });
  }
}

function collectTextFonts(shape: TextShape, ctx: ConverterContext): void {
  if (!ctx._fontCollector || !shape.content) return;
  const typographies = ctx.typographies;
  for (const set of shape.content.children) {
    for (const para of set.children) {
      collectLeafFont({ text: '', ...para }, ctx._fontCollector, typographies);
      for (const leaf of para.children) {
        collectLeafFont(leaf, ctx._fontCollector, typographies);
      }
    }
  }
}

// Pick the first non-empty text leaf in a shape; used to surface the
// "primary" typography on the shape's outer element so devtools-style
// inspectors can read it from the container's inline `style` attribute.
function firstNonEmptyLeaf(
  shape: TextShape,
): { leaf: TextLeaf; para: ParagraphNode } | null {
  if (!shape.content) return null;
  for (const set of shape.content.children) {
    for (const para of set.children) {
      for (const leaf of para.children) {
        if (leaf.text !== undefined && leaf.text !== '') return { leaf, para };
      }
    }
  }
  const firstSet = shape.content.children[0];
  const firstPara = firstSet?.children?.[0];
  const firstLeaf = firstPara?.children?.[0];
  return firstLeaf && firstPara ? { leaf: firstLeaf, para: firstPara } : null;
}

export function renderText(shape: TextShape, ctx: ConverterContext): string {
  collectTextFonts(shape, ctx);
  const base = baseStyles(shape, ctx, { shadows: 'text' });
  const posStyle = resolvePositionOutput(shape, ctx);

  const sizeParts: string[] = [];
  if (shape.width !== undefined) sizeParts.push(decl.width(shape.width));
  if (shape.height !== undefined) sizeParts.push(decl.height(shape.height));
  const sizeStyle = sizeParts.join(' ');

  const noWrapStyle = shape.growType === 'auto-width' ? decl.whiteSpace('nowrap') : '';

  const verticalAlign = shape.content?.verticalAlign;
  const verticalAlignStyle =
    verticalAlign === 'center'
      ? [decl.display('flex'), decl.flexDirection('column'), decl.justifyContent('center')].join(
          ' ',
        )
      : verticalAlign === 'bottom'
        ? [decl.display('flex'), decl.flexDirection('column'), decl.justifyContent('flex-end')].join(
            ' ',
          )
        : '';

  // Surface the primary typography on the outer text container so
  // the HTML-Mode sidebar (which reads the clicked element's inline
  // `style` attribute) shows font-size / font-family / line-height
  // etc., and not just the layout container's `white-space` etc.
  // Browsers cascade these onto the inner paragraph / span, which
  // also carry their own styles, so the visual output is unchanged
  // for any leaf that defines its own values.
  const primary = firstNonEmptyLeaf(shape);
  const typographyStyle = primary
    ? mergeStyles(
        textLeafToStyles(
          { ...primary.para, ...primary.leaf, text: primary.leaf.text ?? '' },
          ctx.typographies,
        ),
        primary.para?.textAlign
          ? decl.textAlign(primary.para.textAlign as Parameters<typeof decl.textAlign>[0])
          : '',
      )
    : '';

  const fillTokenName = shape.appliedTokens?.fill;

  // Resolve a fallback colour for leaves that don't carry their own
  // fill: Penpot designs frequently set a single colour at the shape
  // level (especially headlines) and the leaves just inherit it.
  // Without this fallback those leaves render in the browser default
  // (black on a dark background → invisible). We prefer the shape's
  // own fill, then fall back to the stroke colour to preserve the
  // previous behaviour for outline-only text.
  const shapeFill = (shape.fills ?? []).find((f) => f.fillColor);
  const shapeFillColor = shapeFill
    ? hexOpacityToCss(shapeFill.fillColor!, shapeFill.fillOpacity)
    : undefined;
  const firstStroke = (shape.strokes ?? [])[0];
  const strokeFallbackColor = firstStroke?.strokeColor
    ? hexOpacityToCss(firstStroke.strokeColor, firstStroke.strokeOpacity)
    : undefined;
  const fallbackColor = shapeFillColor ?? strokeFallbackColor;

  // Text strokes (outlined text) approximate to -webkit-text-stroke: the
  // stroke follows the glyphs instead of the text box. Only emitted when
  // the shape also has a fill — for outline-only text the stroke colour is
  // already used as the glyph colour via `fallbackColor`, which reads far
  // closer to Penpot's output than hollow glyphs with a hairline.
  const textStrokeStyle =
    strokeFallbackColor && shapeFillColor
      ? `-webkit-text-stroke: ${firstStroke!.strokeWidth ?? 1}px ${strokeFallbackColor};`
      : '';

  const style = ctx._parentIsLayout
    ? mergeStyles(posStyle, noWrapStyle, verticalAlignStyle, typographyStyle, textStrokeStyle, base)
    : mergeStyles(
        posStyle,
        sizeStyle,
        noWrapStyle,
        verticalAlignStyle,
        typographyStyle,
        textStrokeStyle,
        base,
      );

  let inner = '';
  if (shape.content) {
    inner = shape.content.children
      .flatMap((set) => set.children)
      .map((para) => renderParagraph(para, fillTokenName, ctx.tokens, fallbackColor, ctx.typographies))
      .join('');
  }

  return tag(
    ctx.tagOverride?.(shape) ?? 'div',
    { 'data-id': shape.id, 'data-type': shape.type, style: style || undefined },
    inner,
  );
}
