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
// ---------------------------------------------------------------------------
// Affine helpers for nested-transform composition.
//
// Penpot stores every shape's transform in PAGE space (rotating a container
// also rewrites each descendant's selrect + transform), while CSS transforms
// NEST — a child rendered inside a CSS-transformed parent inherits the
// parent's transform on top of its own. Emitting each shape's page transform
// verbatim therefore double-applies every ancestor transform. The fix is to
// emit, for each shape, the RELATIVE transform `inv(T_parent) ∘ T_shape`
// (both about their respective selrect centers, in page space); for children
// that rotated together with their container this collapses to a small
// translation, and for the container itself it is exactly its own matrix.
export const IDENTITY_MATRIX = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 };
/** m1 ∘ m2 — apply m2 first, then m1 (SVG column convention). */
export function multiplyMatrices(m1, m2) {
    return {
        a: m1.a * m2.a + m1.c * m2.b,
        b: m1.b * m2.a + m1.d * m2.b,
        c: m1.a * m2.c + m1.c * m2.d,
        d: m1.b * m2.c + m1.d * m2.d,
        e: m1.a * m2.e + m1.c * m2.f + m1.e,
        f: m1.b * m2.e + m1.d * m2.f + m1.f,
    };
}
export function invertMatrix(m) {
    const det = m.a * m.d - m.b * m.c;
    if (Math.abs(det) < 1e-12)
        return IDENTITY_MATRIX;
    return {
        a: m.d / det,
        b: -m.b / det,
        c: -m.c / det,
        d: m.a / det,
        e: (m.c * m.f - m.d * m.e) / det,
        f: (m.b * m.e - m.a * m.f) / det,
    };
}
/** Selrect center in page coordinates. */
export function shapeCenter(shape) {
    const x = shape.x ?? shape.selrect?.x ?? 0;
    const y = shape.y ?? shape.selrect?.y ?? 0;
    const w = shape.width ?? shape.selrect?.width ?? 0;
    const h = shape.height ?? shape.selrect?.height ?? 0;
    return { x: x + w / 2, y: y + h / 2 };
}
/**
 * The shape's transform as a page-space affine map:
 * `translate(center) ∘ transform ∘ translate(-center)`.
 */
export function shapePageTransform(shape) {
    const m = shape.transform;
    if (!m || isIdentityMatrix(m))
        return IDENTITY_MATRIX;
    const c = shapeCenter(shape);
    return {
        a: m.a,
        b: m.b,
        c: m.c,
        d: m.d,
        e: c.x - (m.a * c.x + m.c * c.y) + m.e,
        f: c.y - (m.b * c.x + m.d * c.y) + m.f,
    };
}
/**
 * Rewrites a page-space affine map `R` as a CSS `matrix()` applied with the
 * default `transform-origin: 50% 50%` on an element whose (untransformed)
 * box is the shape's selrect: linear part unchanged, translation replaced by
 * `R(center) - center`. Translation-invariant, so it holds in any ancestor
 * frame that differs from page space by a pure translation.
 */
export function pageTransformToCssMatrix(R, shape) {
    const c = shapeCenter(shape);
    return {
        a: R.a,
        b: R.b,
        c: R.c,
        d: R.d,
        e: R.a * c.x + R.c * c.y + R.e - c.x,
        f: R.b * c.x + R.d * c.y + R.f - c.y,
    };
}
//# sourceMappingURL=transform.js.map