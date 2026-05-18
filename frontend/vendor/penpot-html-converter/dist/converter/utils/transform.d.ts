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
