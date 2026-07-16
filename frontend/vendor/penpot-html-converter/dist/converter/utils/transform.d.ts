import type { GeomMatrix } from '../../penpot.types';
/**
 * Converts a GeomMatrix to a CSS `matrix(a, b, c, d, e, f)` string.
 * Each component is rounded to 4 decimal places with trailing zeros stripped.
 */
export declare function matrixToCss(m: GeomMatrix): string;
/**
 * Returns `true` when `m` is the identity matrix within an epsilon of 1e-4.
 */
export declare function isIdentityMatrix(m: GeomMatrix): boolean;
export declare const IDENTITY_MATRIX: GeomMatrix;
/** m1 ∘ m2 — apply m2 first, then m1 (SVG column convention). */
export declare function multiplyMatrices(m1: GeomMatrix, m2: GeomMatrix): GeomMatrix;
export declare function invertMatrix(m: GeomMatrix): GeomMatrix;
interface TransformableShape {
    x?: number | null;
    y?: number | null;
    width?: number | null;
    height?: number | null;
    selrect?: {
        x: number;
        y: number;
        width: number;
        height: number;
    };
    transform?: GeomMatrix;
}
/** Selrect center in page coordinates. */
export declare function shapeCenter(shape: TransformableShape): {
    x: number;
    y: number;
};
/**
 * The shape's transform as a page-space affine map:
 * `translate(center) ∘ transform ∘ translate(-center)`.
 */
export declare function shapePageTransform(shape: TransformableShape): GeomMatrix;
/**
 * Rewrites a page-space affine map `R` as a CSS `matrix()` applied with the
 * default `transform-origin: 50% 50%` on an element whose (untransformed)
 * box is the shape's selrect: linear part unchanged, translation replaced by
 * `R(center) - center`. Translation-invariant, so it holds in any ancestor
 * frame that differs from page space by a pure translation.
 */
export declare function pageTransformToCssMatrix(R: GeomMatrix, shape: TransformableShape): GeomMatrix;
export {};
