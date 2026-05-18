/**
 * Formats a numeric pixel value as a CSS pixel string.
 *
 * Rounds to 2 decimal places and omits the decimal portion when it is zero.
 * Example: `px(120)` → `'120px'`, `px(12.5)` → `'12.5px'`
 */
export declare function px(value: number): string;
