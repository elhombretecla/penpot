import type { Shape } from '../penpot.types';
/**
 * Scans all page objects for `appliedTokens` and resolves each token name to its CSS color value.
 * Returns a map of `tokenName → cssColorValue`.
 *
 * - `appliedTokens.fill` on a regular shape → resolved from `shape.fills[0].fillColor`
 * - `appliedTokens.fill` on a text shape → resolved from the first text leaf fill color
 * - `appliedTokens.strokeColor` → resolved from `shape.strokes[0].strokeColor`
 */
export declare function extractTokens(objects: Record<string, Shape>): Map<string, string>;
/**
 * Converts a Penpot token name to a valid CSS custom property name.
 * Dots in token names (e.g. "background.surface.base") are replaced with dashes (e.g. "background-surface-base") because CSS custom properties cannot contain dots.
 * because dots are not valid in CSS custom property names.
 */
export declare function tokenToCssVarName(tokenName: string): string;
/**
 * A resolved token value is interpolated verbatim into a CSS declaration
 * inside an inline `<style>`. Values come straight from the file (fill /
 * stroke colors, dimensions) and are attacker-controlled for a shared file.
 * Reject anything carrying characters that could terminate the declaration
 * (`;`), the rule (`{` `}`) or the `<style>` element itself (`<` `>`) — such
 * a value is hostile, not a real color/dimension. Returns `null` to drop the
 * declaration entirely.
 */
export declare function safeTokenCssValue(value: string): string | null;
/**
 * Returns a CSS `var(--token-name, fallback)` reference for a token. The fallback is the
 * resolved color value from the tokens map, which makes the output readable and provides
 * a safety net if the custom property is missing.
 */
export declare function tokenToCssVar(tokenName: string, tokens?: Map<string, string>): string;
/**
 * Converts a token map to a CSS `:root { ... }` block with custom properties.
 * Returns `''` when the map is empty.
 */
export declare function tokensToCss(tokens: Map<string, string>): string;
export type TokenCategory = 'color' | 'dimension' | 'spacing' | 'radius' | 'rotation' | 'typography' | 'stroke' | 'opacity' | 'shadow';
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
export declare function extractAllTokens(objects: Record<string, Shape>): TokenInfo[];
