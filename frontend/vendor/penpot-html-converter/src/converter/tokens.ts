import type { Shape, TextLeaf, TextShape } from '../penpot.types';
import { shadowToStyle } from './visual/shadows';

function getFirstTextLeafFill(shape: TextShape): string | undefined {
  if (!shape.content) return undefined;
  for (const set of shape.content.children) {
    for (const para of set.children) {
      for (const leaf of para.children) {
        if (leaf.fills?.[0]?.fillColor) return leaf.fills[0].fillColor;
      }
    }
  }
  return undefined;
}

function findFirstLeaf<K extends keyof TextLeaf>(
  shape: TextShape,
  key: K,
): TextLeaf[K] | undefined {
  if (!shape.content) return undefined;
  for (const set of shape.content.children) {
    for (const para of set.children) {
      const paraVal = (para as unknown as Partial<TextLeaf>)[key];
      for (const leaf of para.children) {
        const v = leaf[key] ?? paraVal;
        if (v !== undefined && v !== null && v !== '') return v;
      }
    }
  }
  return undefined;
}

/**
 * Scans all page objects for `appliedTokens` and resolves each token name to its CSS color value.
 * Returns a map of `tokenName → cssColorValue`.
 *
 * - `appliedTokens.fill` on a regular shape → resolved from `shape.fills[0].fillColor`
 * - `appliedTokens.fill` on a text shape → resolved from the first text leaf fill color
 * - `appliedTokens.strokeColor` → resolved from `shape.strokes[0].strokeColor`
 */
export function extractTokens(objects: Record<string, Shape>): Map<string, string> {
  const tokens = new Map<string, string>();

  for (const shape of Object.values(objects)) {
    const applied = shape.appliedTokens;
    if (!applied) continue;

    if (applied.fill) {
      if (!tokens.has(applied.fill)) {
        const color =
          shape.type === 'text'
            ? getFirstTextLeafFill(shape as TextShape)
            : shape.fills?.[0]?.fillColor;
        if (color) tokens.set(applied.fill, color);
      }
    }

    if (applied.strokeColor) {
      if (!tokens.has(applied.strokeColor)) {
        const color = shape.strokes?.[0]?.strokeColor;
        if (color) tokens.set(applied.strokeColor, color);
      }
    }
  }

  return tokens;
}

/**
 * Converts a Penpot token name to a valid CSS custom property name.
 * Dots in token names (e.g. "background.surface.base") are replaced with dashes (e.g. "background-surface-base") because CSS custom properties cannot contain dots.
 * because dots are not valid in CSS custom property names.
 */
export function tokenToCssVarName(tokenName: string): string {
  return tokenName.replace(/\./g, '-');
}

/**
 * Returns a CSS `var(--token-name, fallback)` reference for a token. The fallback is the
 * resolved color value from the tokens map, which makes the output readable and provides
 * a safety net if the custom property is missing.
 */
export function tokenToCssVar(tokenName: string, tokens?: Map<string, string>): string {
  const varName = tokenToCssVarName(tokenName);
  const fallback = tokens?.get(tokenName);
  return fallback ? `var(--${varName}, ${fallback})` : `var(--${varName})`;
}

/**
 * Converts a token map to a CSS `:root { ... }` block with custom properties.
 * Returns `''` when the map is empty.
 */
export function tokensToCss(tokens: Map<string, string>): string {
  if (tokens.size === 0) return '';
  const props = Array.from(tokens.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([name, value]) => `    --${tokenToCssVarName(name)}: ${value};`)
    .join('\n');
  return `:root {\n${props}\n  }`;
}

// ============================================================
// Rich token extraction — used by the tokens viewer UI
// ============================================================

export type TokenCategory =
  | 'color'
  | 'dimension'
  | 'spacing'
  | 'radius'
  | 'rotation'
  | 'typography'
  | 'stroke'
  | 'opacity'
  | 'shadow';

export interface TokenInfo {
  /** Raw token name (dots preserved — e.g. "colors.brand.500") */
  name: string;
  /** Top-level grouping for UI */
  category: TokenCategory;
  /** Attribute sub-group (e.g. "fill", "fontSize", "padding") */
  attribute: string;
  /** CSS-ready value preview (e.g. "#ff0000", "16px", "Inter", "1.5") */
  value: string;
  /** Whether value is numeric (px) for sorting within category */
  numericValue?: number;
  /** Shapes referencing this token (count only, to keep payload small) */
  usageCount: number;
}

type Acc = {
  category: TokenCategory;
  attribute: string;
  value: string;
  numericValue?: number;
  count: number;
};

function pxOrUndefined(n: number | undefined): string | undefined {
  return typeof n === 'number' ? `${n}px` : undefined;
}

function resolveTokenValue(
  attribute: string,
  shape: Shape,
): { category: TokenCategory; value: string; numericValue?: number } | undefined {
  switch (attribute) {
    case 'fill': {
      const color =
        shape.type === 'text'
          ? getFirstTextLeafFill(shape as TextShape)
          : shape.fills?.[0]?.fillColor;
      return color ? { category: 'color', value: color } : undefined;
    }
    case 'strokeColor': {
      const v = shape.strokes?.[0]?.strokeColor;
      return v ? { category: 'color', value: v } : undefined;
    }
    case 'strokeWidth': {
      const n = shape.strokes?.[0]?.strokeWidth;
      const v = pxOrUndefined(n);
      return v ? { category: 'stroke', value: v, numericValue: n } : undefined;
    }
    case 'r1':
    case 'r2':
    case 'r3':
    case 'r4': {
      const n = (shape as { r1?: number; r2?: number; r3?: number; r4?: number })[attribute];
      const v = pxOrUndefined(n);
      return v ? { category: 'radius', value: v, numericValue: n } : undefined;
    }
    case 'width':
    case 'height': {
      const n = (shape as { width?: number; height?: number })[attribute];
      const v = pxOrUndefined(n);
      return v ? { category: 'dimension', value: v, numericValue: n } : undefined;
    }
    case 'layoutItemMinW':
    case 'layoutItemMaxW':
    case 'layoutItemMinH':
    case 'layoutItemMaxH': {
      const n = (
        shape as {
          layoutItemMinW?: number;
          layoutItemMaxW?: number;
          layoutItemMinH?: number;
          layoutItemMaxH?: number;
        }
      )[attribute];
      const v = pxOrUndefined(n);
      return v ? { category: 'dimension', value: v, numericValue: n } : undefined;
    }
    case 'rowGap': {
      const n = (shape as { layoutRowGap?: number }).layoutRowGap;
      const v = pxOrUndefined(n);
      return v ? { category: 'spacing', value: v, numericValue: n } : undefined;
    }
    case 'columnGap': {
      const n = (shape as { layoutColumnGap?: number }).layoutColumnGap;
      const v = pxOrUndefined(n);
      return v ? { category: 'spacing', value: v, numericValue: n } : undefined;
    }
    case 'p1':
    case 'p2':
    case 'p3':
    case 'p4': {
      const pad = (shape as { layoutPadding?: Record<string, number> }).layoutPadding;
      const n = pad?.[attribute];
      const v = pxOrUndefined(n);
      return v ? { category: 'spacing', value: v, numericValue: n } : undefined;
    }
    case 'm1':
    case 'm2':
    case 'm3':
    case 'm4': {
      const mar = (shape as { layoutItemMargin?: Record<string, number> }).layoutItemMargin;
      const n = mar?.[attribute];
      const v = pxOrUndefined(n);
      return v ? { category: 'spacing', value: v, numericValue: n } : undefined;
    }
    case 'rotation': {
      const n = shape.rotation;
      return typeof n === 'number'
        ? { category: 'rotation', value: `${n}deg`, numericValue: n }
        : undefined;
    }
    case 'opacity': {
      const n = shape.opacity;
      return typeof n === 'number'
        ? { category: 'opacity', value: String(n), numericValue: n }
        : undefined;
    }
    case 'x':
    case 'y': {
      const n = (shape as { x?: number; y?: number })[attribute];
      const v = pxOrUndefined(n);
      return v ? { category: 'dimension', value: v, numericValue: n } : undefined;
    }
    case 'shadow': {
      const shadows = shape.shadow;
      if (!shadows || shadows.length === 0) return undefined;
      const v = shadows.map(shadowToStyle).filter(Boolean).join(', ');
      return v ? { category: 'shadow', value: v } : undefined;
    }
    case 'fontSize':
    case 'lineHeight':
    case 'letterSpacing':
    case 'fontFamily':
    case 'fontWeight':
    case 'textCase':
    case 'textDecoration': {
      if (shape.type !== 'text') return undefined;
      const text = shape as TextShape;
      const textLeafKey = attribute === 'textCase' ? 'textTransform' : attribute;
      const raw = findFirstLeaf(text, textLeafKey as keyof TextLeaf);
      if (raw === undefined || raw === null || raw === '') return undefined;
      const str = String(raw);
      const asNum = Number(str);
      if (attribute === 'fontSize' || attribute === 'letterSpacing') {
        const v = Number.isFinite(asNum) ? `${asNum}px` : str;
        return {
          category: 'typography',
          value: v,
          numericValue: Number.isFinite(asNum) ? asNum : undefined,
        };
      }
      return { category: 'typography', value: str };
    }
    case 'typography': {
      // Composite typography token (the usual way a whole type style is
      // applied). It has no single CSS value, so we build a readable
      // preview from the first text leaf — family + size — falling back
      // to a generic label so the token still surfaces in the inventory.
      if (shape.type !== 'text') return { category: 'typography', value: 'Typography' };
      const text = shape as TextShape;
      const family = findFirstLeaf(text, 'fontFamily');
      const size = findFirstLeaf(text, 'fontSize');
      const parts: string[] = [];
      if (family !== undefined && family !== null && family !== '') parts.push(String(family));
      if (size !== undefined && size !== null && size !== '') {
        const asNum = Number(String(size));
        parts.push(Number.isFinite(asNum) ? `${asNum}px` : String(size));
      }
      return { category: 'typography', value: parts.join(' · ') || 'Typography' };
    }
    default:
      return undefined;
  }
}

/**
 * Extract every token applied on the page — not just colors.
 *
 * One pass over `objects`. For each token reference we track the first resolved
 * value (so the UI has a preview) and the number of shapes that apply it.
 *
 * The return array is deliberately flat and compact (name / category / value /
 * numericValue? / usageCount) so it can be shipped to the client without the
 * full page payload.
 */
export function extractAllTokens(objects: Record<string, Shape>): TokenInfo[] {
  const acc = new Map<string, Acc>();

  for (const shape of Object.values(objects)) {
    const applied = shape.appliedTokens;
    if (!applied) continue;

    for (const attribute of Object.keys(applied) as (keyof typeof applied)[]) {
      const tokenName = applied[attribute];
      if (!tokenName) continue;

      const key = `${attribute}::${tokenName}`;
      const existing = acc.get(key);
      if (existing) {
        existing.count++;
        continue;
      }

      const resolved = resolveTokenValue(attribute, shape);
      if (!resolved) continue;

      acc.set(key, {
        category: resolved.category,
        attribute,
        value: resolved.value,
        numericValue: resolved.numericValue,
        count: 1,
      });
    }
  }

  const result: TokenInfo[] = [];
  for (const [key, v] of acc) {
    const idx = key.indexOf('::');
    const name = key.slice(idx + 2);
    result.push({
      name,
      category: v.category,
      attribute: v.attribute,
      value: v.value,
      numericValue: v.numericValue,
      usageCount: v.count,
    });
  }
  return result;
}
