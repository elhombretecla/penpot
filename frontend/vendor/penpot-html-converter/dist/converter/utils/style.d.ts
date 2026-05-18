/**
 * Joins multiple CSS style strings into a single string.
 *
 * Each part may contain one or more semicolon-separated declarations.
 * Empty parts (or parts that split to nothing) are skipped.
 *
 * Repeated single-value properties (e.g. `width`, `height`, `color`) follow
 * CSS cascade semantics: the last declaration wins. The deduped declaration
 * is moved to the position of the LAST occurrence so the override is visible
 * at the end of the inline style — `width: 10px; height: 5px; width: 20px`
 * collapses to `height: 5px; width: 20px;`.
 *
 * Two properties keep all values:
 * - `box-shadow`: multiple shadows are merged into one comma-separated value.
 * - `transform`: multiple transforms are merged into one space-separated value.
 *
 * @example
 * mergeStyles('left: 10px;', '', 'top: 20px;')
 * // → 'left: 10px; top: 20px;'
 *
 * mergeStyles('width: 10px; height: 5px;', 'width: 20px;')
 * // → 'height: 5px; width: 20px;'
 *
 * mergeStyles('box-shadow: 0 2px 4px #000;', 'box-shadow: inset 0 0 0 2px red;')
 * // → 'box-shadow: 0 2px 4px #000, inset 0 0 0 2px red;'
 *
 * mergeStyles('transform: translate(10px, 20px);', 'transform: rotate(45deg);')
 * // → 'transform: translate(10px, 20px) rotate(45deg);'
 */
export declare function mergeStyles(...parts: string[]): string;
