import type { FontInfo } from '../types';

const DEFAULT_BASE_URL = 'https://design.penpot.app';
const GSTATIC_PREFIX = 'https://fonts.gstatic.com/s';

export interface BuildPenpotFontsCssOptions {
  baseUrl?: string;
  fetch?: typeof globalThis.fetch;
}

interface NormalizedFont {
  fontId: string;
  fontFamily: string;
  weight: number;
  italic: boolean;
}

function cleanFamily(family: string): string {
  return family
    .trim()
    .replace(/^['"]|['"]$/g, '')
    .trim()
    .replace(/\s+/g, ' ');
}

const BUNDLED_FONT_IDS = new Set(['sourcesanspro']);

const GENERIC_FAMILIES = new Set([
  'serif',
  'sans-serif',
  'monospace',
  'cursive',
  'fantasy',
  'system-ui',
  'ui-serif',
  'ui-sans-serif',
  'ui-monospace',
  'ui-rounded',
  'math',
  'emoji',
  'inherit',
  'initial',
  'unset',
]);

function familySlug(family: string): string {
  return family.toLowerCase().replace(/\s+/g, '');
}

function normalize(font: FontInfo): NormalizedFont | null {
  const fontFamily = cleanFamily(font.fontFamily);
  if (!fontFamily) return null;
  if (GENERIC_FAMILIES.has(fontFamily.toLowerCase())) return null;
  // Skip token-like references (e.g. "textos.texto1") that weren't resolved upstream.
  if (fontFamily.includes('.')) return null;
  const weight = Number(font.fontWeight ?? 400) || 400;
  const italic = font.fontStyle === 'italic';
  const slug = familySlug(fontFamily);
  const fontId = font.fontId ?? (BUNDLED_FONT_IDS.has(slug) ? slug : `gfont-${slug}`);
  return { fontId, fontFamily, weight, italic };
}

function isGoogleFont(fontId: string): boolean {
  return fontId.startsWith('gfont-');
}

const SOURCE_SANS_PRO_SUFFIXES: Record<number, string> = {
  200: 'extralight',
  300: 'light',
  400: 'regular',
  700: 'bold',
  900: 'black',
};

function sourceSansProSuffix(weight: number, italic: boolean): string | null {
  const base = SOURCE_SANS_PRO_SUFFIXES[weight];
  if (!base) return null;
  if (weight === 400) return italic ? 'italic' : 'regular';
  return italic ? `${base}italic` : base;
}

function bundledFontFace(font: NormalizedFont, baseUrl: string): string | null {
  if (font.fontId === 'sourcesanspro') {
    const suffix = sourceSansProSuffix(font.weight, font.italic);
    if (!suffix) return null;
    return [
      '@font-face {',
      `  font-family: '${font.fontFamily}';`,
      `  font-style: ${font.italic ? 'italic' : 'normal'};`,
      `  font-weight: ${font.weight};`,
      '  font-display: block;',
      `  src: url(${baseUrl}/fonts/sourcesanspro-${suffix}.woff2) format('woff2');`,
      '}',
    ].join('\n');
  }
  return null;
}

function gfontVariantId(weight: number, italic: boolean): string {
  if (weight === 400) return italic ? 'italic' : 'regular';
  return italic ? `${weight}italic` : String(weight);
}

async function fetchGfontsCss(
  family: string,
  variants: ReadonlySet<string>,
  baseUrl: string,
  fetchFn: typeof globalThis.fetch,
): Promise<string> {
  const familyParam = encodeURIComponent(family).replace(/%20/g, '+');
  const variantsParam = [...variants].sort().join(',');
  const url = `${baseUrl}/internal/gfonts/css?family=${familyParam}:${variantsParam}&display=block`;
  const res = await fetchFn(url);
  if (!res.ok) {
    throw new Error(`Failed to fetch Penpot fonts CSS for "${family}": ${res.status}`);
  }
  const css = await res.text();
  return css.replaceAll(GSTATIC_PREFIX, `${baseUrl}/internal/gfonts/font`);
}

/**
 * Builds a CSS string with `@font-face` rules whose `src` URLs are served by Penpot
 * (`/internal/gfonts/font/…woff2` for Google Fonts, `/fonts/…woff2` for bundled ones),
 * so the rendered HTML never reaches `fonts.gstatic.com` or `fonts.googleapis.com`.
 *
 * Inline the result into a `<style>` block — there's no separate stylesheet to link.
 */
export async function buildPenpotFontsCss(
  fonts: readonly FontInfo[],
  options: BuildPenpotFontsCssOptions = {},
): Promise<string> {
  const baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/$/, '');
  const fetchFn = options.fetch ?? globalThis.fetch;

  const cssParts: string[] = [];
  const gfontsByFamily = new Map<string, Set<string>>();

  for (const raw of fonts) {
    const font = normalize(raw);
    if (!font) continue;

    if (isGoogleFont(font.fontId)) {
      const variant = gfontVariantId(font.weight, font.italic);
      const set = gfontsByFamily.get(font.fontFamily) ?? new Set<string>();
      set.add(variant);
      gfontsByFamily.set(font.fontFamily, set);
      continue;
    }

    const face = bundledFontFace(font, baseUrl);
    if (face) cssParts.push(face);
  }

  for (const [family, variants] of gfontsByFamily) {
    try {
      cssParts.push(await fetchGfontsCss(family, variants, baseUrl, fetchFn));
    } catch (err) {
      console.warn(`[buildPenpotFontsCss] skipping "${family}":`, err);
    }
  }

  return cssParts.join('\n');
}
