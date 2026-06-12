import type { Shape } from '../penpot.types';
import type { ConverterContext, FontInfo } from './types';
/** Whitelisted semantic tags the converter can emit as wrapper overrides. */
export declare const SEMANTIC_TAGS: readonly ["div", "button", "a", "nav", "header", "footer", "main", "section", "article", "aside", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "p", "label", "span"];
export type SemanticTag = (typeof SEMANTIC_TAGS)[number];
/** Per-file rule — the user teaches "this layer is a button / link / list / …". */
export interface SemanticRule {
    id: string;
    /**
     * `shape-id`      — `value` is a Penpot UUID, matches that exact shape.
     * `name-equals`   — `value` matches the layer name verbatim (case-insensitive).
     * `name-contains` — `value` appears anywhere in the layer name (case-insensitive).
     */
    type: 'shape-id' | 'name-equals' | 'name-contains';
    value: string;
    tag: SemanticTag;
    enabled: boolean;
}
export type ShapeCodeFormat = 'html' | 'jsx';
export type ShapeCodeStyling = 'css' | 'tailwind';
export interface ShapeCodeOptions {
    format: ShapeCodeFormat;
    styling: ShapeCodeStyling;
    /** Keep `data-id` / `data-type` / `data-name` / `data-penpot-*`. Default false. */
    includeDataAttrs?: boolean;
    /** Per-file rules driving semantic-tag overrides. Empty / undefined = all `<div>`. */
    rules?: SemanticRule[];
}
export interface ShapeCodeResult {
    /** HTML or JSX with `class` / `className` references (no inline styles). */
    code: string;
    /** Class definitions when `styling === 'css'`. Empty string for tailwind. */
    css: string;
    /** Fonts referenced by the rendered shape — pass to `buildPenpotFontsCss` if you need the CSS. */
    fonts: FontInfo[];
}
/**
 * Walk every shape in the page and decide which HTML tag should wrap it.
 *
 * Precedence (most specific first):
 *   1. `shape-id`      — rule's value matches `shape.id` exactly.
 *   2. `name-equals`   — `shape.name` equals the rule's value (case-insensitive).
 *   3. `name-contains` — `shape.name` contains the rule's value (case-insensitive).
 *
 * Within the same tier the earlier rule wins. Disabled rules are skipped.
 * Returns a `Map<shapeId, tag>` ready to plug into the converter's
 * `tagOverride` callback.
 */
export declare function resolveTagOverrides(rules: SemanticRule[] | undefined, objects: Record<string, Shape>): Map<string, string>;
/**
 * Run the full pipeline on a single shape:
 *   convertShape → extract classes → strip data-* → swap class/className.
 *
 * Caller supplies `ctx` exactly as it would for `convertShape` (image url
 * resolver, tokens, …) — `tagOverride` and `format: false` are injected by
 * this orchestrator and any caller-supplied values for them are ignored.
 */
export declare function shapeToCode(shape: Shape, allObjects: Record<string, Shape>, ctx: ConverterContext, options: ShapeCodeOptions): Promise<ShapeCodeResult>;
export declare function stripDataAttrs(html: string): string;
/**
 * Layer-name → CSS class slug. Lowercase, collapse non-alphanum to `-`,
 * trim leading/trailing dashes. Returns `null` on empty input. Identifiers
 * starting with a digit get an `_` prefix so they parse as a selector.
 */
export declare function slugifyName(name: string | undefined): string | null;
/**
 * Replace every `style="…"` attribute with a deduped class reference and
 * return the collected class definitions as a CSS string.
 *
 * Two passes:
 *   1. Match `<… data-id="…" … style="…">` — Penpot shape wrappers. The
 *      class name is derived from the layer's name via `slugifyName`.
 *   2. Anything left over (text leaves like `<p>` / `<span>` the converter
 *      emits inside paragraphs, which carry no `data-id`). The fallback
 *      derives a name from the nearest preceding `class="…"` — e.g. a
 *      `<p>` inside `<div class="cancel">` becomes `class="cancel-text"`.
 *      If there's no parent class either, fall back to `s-N`.
 */
export declare function stylesToCssClasses(html: string, classAttr: 'class' | 'className', names: Map<string, string>): {
    html: string;
    css: string;
};
/**
 * Replace every `style="…"` attribute with a Tailwind utility class string.
 * Declarations not covered by an explicit mapping fall back to v4 arbitrary
 * properties `[prop:value]` so the visual round-trip is preserved.
 */
export declare function stylesToTailwind(html: string, classAttr: 'class' | 'className'): string;
/**
 * Map a single CSS declaration to a Tailwind utility. Falls back to the v4
 * arbitrary-property form `[prop:value]` for anything not handled here.
 */
export declare function declToTailwind(prop: string, v: string): string;
