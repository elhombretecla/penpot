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
export function mergeStyles(...parts) {
    const boxShadowValues = [];
    const transformValues = [];
    const order = [];
    const valueByProp = new Map();
    for (const part of parts) {
        const decls = part
            .split(';')
            .map((d) => d.trim())
            .filter(Boolean);
        for (const decl of decls) {
            const colon = decl.indexOf(':');
            if (colon === -1)
                continue;
            const prop = decl.slice(0, colon).trim().toLowerCase();
            const value = decl.slice(colon + 1).trim();
            if (!prop)
                continue;
            if (prop === 'box-shadow') {
                boxShadowValues.push(value);
                continue;
            }
            if (prop === 'transform') {
                transformValues.push(value);
                continue;
            }
            if (valueByProp.has(prop)) {
                // Move the override to the end so the cascade is visible.
                const idx = order.indexOf(prop);
                if (idx !== -1)
                    order.splice(idx, 1);
            }
            order.push(prop);
            valueByProp.set(prop, value);
        }
    }
    const out = order.map((prop) => `${prop}: ${valueByProp.get(prop)};`);
    if (boxShadowValues.length > 0) {
        out.push(`box-shadow: ${boxShadowValues.join(', ')};`);
    }
    if (transformValues.length > 0) {
        out.push(`transform: ${transformValues.join(' ')};`);
    }
    return out.join(' ');
}
//# sourceMappingURL=style.js.map