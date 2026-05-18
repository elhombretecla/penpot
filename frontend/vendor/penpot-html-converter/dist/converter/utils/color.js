/**
 * Converts a hex color string and an optional opacity value to a CSS color
 * string. Returns the hex string as-is when opacity is `undefined` or `1`;
 * otherwise returns `rgba(r, g, b, opacity)`.
 *
 * Supports both 3-digit (#RGB) and 6-digit (#RRGGBB) hex strings.
 */
export function hexOpacityToCss(hex, opacity) {
    if (opacity === undefined || opacity === 1) {
        return hex;
    }
    const normalized = hex.startsWith('#') ? hex.slice(1) : hex;
    let r;
    let g;
    let b;
    if (normalized.length === 3) {
        r = parseInt(normalized[0] + normalized[0], 16);
        g = parseInt(normalized[1] + normalized[1], 16);
        b = parseInt(normalized[2] + normalized[2], 16);
    }
    else {
        r = parseInt(normalized.slice(0, 2), 16);
        g = parseInt(normalized.slice(2, 4), 16);
        b = parseInt(normalized.slice(4, 6), 16);
    }
    return `rgba(${r}, ${g}, ${b}, ${opacity})`;
}
//# sourceMappingURL=color.js.map