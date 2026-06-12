import type { ShapeCommon, Uuid } from '../penpot.types';
/** Information about a font used in the converted output */
export interface FontInfo {
    fontId?: string;
    fontFamily: string;
    fontWeight?: string | number;
    fontStyle?: string;
}
/** Result of a convert operation */
export interface ConvertResult {
    html: string;
    fonts: FontInfo[];
}
export interface ConverterContext {
    /** Returns a URL or data-URI for a Penpot image asset by its ID */
    resolveImageUrl(id: Uuid): string;
    /** Optional prefix for image URLs */
    baseUrl?: string;
    /** @internal When true, shape renderers skip absolute positioning (parent is flex/grid) */
    _parentIsLayout?: boolean;
    /** @internal When true alongside _parentIsLayout, the child must emit its own explicit width instead of w-full (auto h-sizing) */
    _parentIsLayoutAutoW?: boolean;
    /** @internal When true alongside _parentIsLayout, the child must emit its own explicit height instead of h-full (auto v-sizing) */
    _parentIsLayoutAutoH?: boolean;
    /** @internal Layout-item / grid-cell styles to merge onto the child (in place of a wrapper div) */
    _parentLayoutItemStyles?: string;
    /** @internal Page background color applied to the root frame */
    _pageBackground?: string;
    /** @internal When true, the shape uses relative positioning instead of absolute */
    _forceRelative?: boolean;
    /** @internal When true, shapes with fixedScroll use `fixed` positioning instead of `absolute` */
    _isChildOfRoot?: boolean;
    /** @internal Page-absolute X offset of the nearest positioned ancestor (for relative positioning) */
    _offsetX?: number;
    /** @internal Page-absolute Y offset of the nearest positioned ancestor (for relative positioning) */
    _offsetY?: number;
    /** @internal When true, the shape is a direct child of the root canvas frame and must use translate-based positioning */
    _isCanvasTopLevel?: boolean;
    /** @internal Collects font info during rendering; populated by text renderers */
    _fontCollector?: Map<string, FontInfo>;
    /** Design token map (tokenName → cssColor). Used to emit `var(--token)` instead of raw hex values. */
    tokens?: Map<string, string>;
    /** Library typographies keyed by their id; leaves with `typographyRefId` inherit their styles from this map. */
    typographies?: Record<string, import('../penpot.types').Typography>;
    /**
     * Returns the HTML tag to use as the wrapper for a frame / rect / text shape.
     * Default behaviour (when omitted or returning `undefined`) is `'div'`.
     * Lets callers override semantics — e.g. a downstream tool resolves
     * "shape X is a button" rules and returns `'button'` for that shape.
     */
    tagOverride?: (shape: ShapeCommon) => string | undefined;
    /** When true (default), format the output HTML with oxfmt */
    format?: boolean;
}
