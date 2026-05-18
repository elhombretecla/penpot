/**
 * Formats a numeric pixel value as a CSS pixel string.
 *
 * Rounds to 2 decimal places and omits the decimal portion when it is zero.
 * Example: `px(120)` → `'120px'`, `px(12.5)` → `'12.5px'`
 */
export function px(value: number): string {
  const rounded = Math.round(value * 100) / 100;
  const formatted =
    rounded % 1 === 0 ? String(rounded | 0) : rounded.toFixed(2).replace(/\.?0+$/, '');
  return `${formatted}px`;
}
