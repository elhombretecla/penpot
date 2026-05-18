import type { FontInfo } from '../types';
export interface BuildPenpotFontsCssOptions {
    baseUrl?: string;
    fetch?: typeof globalThis.fetch;
}
/**
 * Builds a CSS string with `@font-face` rules whose `src` URLs are served by Penpot
 * (`/internal/gfonts/font/…woff2` for Google Fonts, `/fonts/…woff2` for bundled ones),
 * so the rendered HTML never reaches `fonts.gstatic.com` or `fonts.googleapis.com`.
 *
 * Inline the result into a `<style>` block — there's no separate stylesheet to link.
 */
export declare function buildPenpotFontsCss(fonts: readonly FontInfo[], options?: BuildPenpotFontsCssOptions): Promise<string>;
