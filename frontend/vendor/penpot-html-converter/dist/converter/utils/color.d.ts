import type { HexColor } from '../../penpot.types';
/**
 * Converts a hex color string and an optional opacity value to a CSS color
 * string. Returns the hex string as-is when opacity is `undefined` or `1`;
 * otherwise returns `rgba(r, g, b, opacity)`.
 *
 * Supports both 3-digit (#RGB) and 6-digit (#RRGGBB) hex strings.
 */
export declare function hexOpacityToCss(hex: HexColor, opacity?: number): string;
