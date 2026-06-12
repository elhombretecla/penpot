import type { Page, Shape } from '../penpot.types';
import type { ConverterContext, ConvertResult, FontInfo } from './types';
export { buildPenpotFontsCss } from './utils/fonts';
export type { BuildPenpotFontsCssOptions } from './utils/fonts';
export { decl, SUPPORTED_PROPS } from './decl';
export type { Length, Box4 } from './decl';
export { extractTokens, tokensToCss, tokenToCssVar, tokenToCssVarName, extractAllTokens, } from './tokens';
export interface ShapeResult {
    id: string;
    html: string;
    x: number;
    y: number;
    width: number;
    height: number;
}
export interface PageShapesResult {
    shapes: ShapeResult[];
    fonts: FontInfo[];
}
/**
 * Converts a full Penpot page to an HTML string (body content only, no `<html>` wrapper).
 */
export declare function convertPage(page: Page, ctx: ConverterContext): Promise<ConvertResult>;
/**
 * Converts a single Penpot shape and all its descendants to a standalone HTML snippet.
 *
 * The root shape is forced to `relative` positioning so it can be embedded anywhere.
 * Returns the rendered div tree without any `<html>` or `<body>` wrapper.
 */
export declare function convertShape(shape: Shape, allObjects: Record<string, Shape>, ctx: ConverterContext): Promise<ConvertResult>;
/**
 * Converts a Penpot page into per-shape results, one entry per top-level canvas shape.
 * Useful for rendering only the shapes currently visible in the viewport.
 */
export declare function convertPageShapes(page: Page, ctx: ConverterContext): Promise<PageShapesResult>;
export type { ConverterContext, ConvertResult, FontInfo };
