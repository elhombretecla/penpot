const EPSILON = 1e-4;
/**
 * Formats a number rounded to 4 decimal places, stripping trailing zeros.
 * Negative zero is normalized to zero.
 */
function fmt(n) {
    const rounded = parseFloat(n.toFixed(4));
    // Normalize -0 to 0
    return (rounded === 0 ? 0 : rounded).toString();
}
/**
 * Converts a GeomMatrix to a CSS `matrix(a, b, c, d, e, f)` string.
 * Each component is rounded to 4 decimal places with trailing zeros stripped.
 */
export function matrixToCss(m) {
    return `matrix(${fmt(m.a)}, ${fmt(m.b)}, ${fmt(m.c)}, ${fmt(m.d)}, ${fmt(m.e)}, ${fmt(m.f)})`;
}
/**
 * Returns `true` when `m` is the identity matrix within an epsilon of 1e-4.
 */
export function isIdentityMatrix(m) {
    return (Math.abs(m.a - 1) < EPSILON &&
        Math.abs(m.b) < EPSILON &&
        Math.abs(m.c) < EPSILON &&
        Math.abs(m.d - 1) < EPSILON &&
        Math.abs(m.e) < EPSILON &&
        Math.abs(m.f) < EPSILON);
}
//# sourceMappingURL=transform.js.map