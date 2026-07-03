import { shadowToStyle } from './visual/shadows';
function getFirstTextLeafFill(shape) {
    if (!shape.content)
        return undefined;
    for (const set of shape.content.children) {
        for (const para of set.children) {
            for (const leaf of para.children) {
                if (leaf.fills?.[0]?.fillColor)
                    return leaf.fills[0].fillColor;
            }
        }
    }
    return undefined;
}
function findFirstLeaf(shape, key) {
    if (!shape.content)
        return undefined;
    for (const set of shape.content.children) {
        for (const para of set.children) {
            const paraVal = para[key];
            for (const leaf of para.children) {
                const v = leaf[key] ?? paraVal;
                if (v !== undefined && v !== null && v !== '')
                    return v;
            }
        }
    }
    return undefined;
}
/**
 * Scans all page objects for `appliedTokens` and resolves each token name to its CSS color value.
 * Returns a map of `tokenName → cssColorValue`.
 *
 * - `appliedTokens.fill` on a regular shape → resolved from `shape.fills[0].fillColor`
 * - `appliedTokens.fill` on a text shape → resolved from the first text leaf fill color
 * - `appliedTokens.strokeColor` → resolved from `shape.strokes[0].strokeColor`
 */
export function extractTokens(objects) {
    const tokens = new Map();
    for (const shape of Object.values(objects)) {
        const applied = shape.appliedTokens;
        if (!applied)
            continue;
        if (applied.fill) {
            if (!tokens.has(applied.fill)) {
                const color = shape.type === 'text'
                    ? getFirstTextLeafFill(shape)
                    : shape.fills?.[0]?.fillColor;
                if (color)
                    tokens.set(applied.fill, color);
            }
        }
        if (applied.strokeColor) {
            if (!tokens.has(applied.strokeColor)) {
                const color = shape.strokes?.[0]?.strokeColor;
                if (color)
                    tokens.set(applied.strokeColor, color);
            }
        }
    }
    return tokens;
}
/**
 * Converts a Penpot token name to a valid CSS custom property name.
 * Dots in token names (e.g. "background.surface.base") are replaced with dashes (e.g. "background-surface-base") because CSS custom properties cannot contain dots.
 * because dots are not valid in CSS custom property names.
 */
export function tokenToCssVarName(tokenName) {
    // Dots aren't valid in custom-property names; every other character
    // outside the CSS-identifier charset is stripped so a hostile token name
    // (names come verbatim from the file) cannot break out of the
    // `--name: value` declaration or the surrounding inline <style>.
    return tokenName.replace(/\./g, '-').replace(/[^a-zA-Z0-9_-]/g, '');
}
/**
 * A resolved token value is interpolated verbatim into a CSS declaration
 * inside an inline `<style>`. Values come straight from the file (fill /
 * stroke colors, dimensions) and are attacker-controlled for a shared file.
 * Reject anything carrying characters that could terminate the declaration
 * (`;`), the rule (`{` `}`) or the `<style>` element itself (`<` `>`) — such
 * a value is hostile, not a real color/dimension. Returns `null` to drop the
 * declaration entirely.
 */
export function safeTokenCssValue(value) {
    const v = String(value).trim();
    if (v === '' || /[<>{};]/.test(v))
        return null;
    return v;
}
/**
 * Returns a CSS `var(--token-name, fallback)` reference for a token. The fallback is the
 * resolved color value from the tokens map, which makes the output readable and provides
 * a safety net if the custom property is missing.
 */
export function tokenToCssVar(tokenName, tokens) {
    const varName = tokenToCssVarName(tokenName);
    const fallback = tokens?.get(tokenName);
    const safeFallback = fallback != null ? safeTokenCssValue(fallback) : null;
    return safeFallback ? `var(--${varName}, ${safeFallback})` : `var(--${varName})`;
}
/**
 * Converts a token map to a CSS `:root { ... }` block with custom properties.
 * Returns `''` when the map is empty.
 */
export function tokensToCss(tokens) {
    if (tokens.size === 0)
        return '';
    const props = Array.from(tokens.entries())
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([name, value]) => {
        const varName = tokenToCssVarName(name);
        const safeValue = safeTokenCssValue(value);
        if (!varName || safeValue === null)
            return null;
        return `    --${varName}: ${safeValue};`;
    })
        .filter((line) => line !== null)
        .join('\n');
    return props ? `:root {\n${props}\n  }` : '';
}
function pxOrUndefined(n) {
    return typeof n === 'number' ? `${n}px` : undefined;
}
function resolveTokenValue(attribute, shape) {
    switch (attribute) {
        case 'fill': {
            const color = shape.type === 'text'
                ? getFirstTextLeafFill(shape)
                : shape.fills?.[0]?.fillColor;
            return color ? { category: 'color', value: color } : undefined;
        }
        case 'strokeColor': {
            const v = shape.strokes?.[0]?.strokeColor;
            return v ? { category: 'color', value: v } : undefined;
        }
        case 'strokeWidth': {
            const n = shape.strokes?.[0]?.strokeWidth;
            const v = pxOrUndefined(n);
            return v ? { category: 'stroke', value: v, numericValue: n } : undefined;
        }
        case 'r1':
        case 'r2':
        case 'r3':
        case 'r4': {
            const n = shape[attribute];
            const v = pxOrUndefined(n);
            return v ? { category: 'radius', value: v, numericValue: n } : undefined;
        }
        case 'width':
        case 'height': {
            const n = shape[attribute];
            const v = pxOrUndefined(n);
            return v ? { category: 'dimension', value: v, numericValue: n } : undefined;
        }
        case 'layoutItemMinW':
        case 'layoutItemMaxW':
        case 'layoutItemMinH':
        case 'layoutItemMaxH': {
            const n = shape[attribute];
            const v = pxOrUndefined(n);
            return v ? { category: 'dimension', value: v, numericValue: n } : undefined;
        }
        case 'rowGap': {
            const n = shape.layoutRowGap;
            const v = pxOrUndefined(n);
            return v ? { category: 'spacing', value: v, numericValue: n } : undefined;
        }
        case 'columnGap': {
            const n = shape.layoutColumnGap;
            const v = pxOrUndefined(n);
            return v ? { category: 'spacing', value: v, numericValue: n } : undefined;
        }
        case 'p1':
        case 'p2':
        case 'p3':
        case 'p4': {
            const pad = shape.layoutPadding;
            const n = pad?.[attribute];
            const v = pxOrUndefined(n);
            return v ? { category: 'spacing', value: v, numericValue: n } : undefined;
        }
        case 'm1':
        case 'm2':
        case 'm3':
        case 'm4': {
            const mar = shape.layoutItemMargin;
            const n = mar?.[attribute];
            const v = pxOrUndefined(n);
            return v ? { category: 'spacing', value: v, numericValue: n } : undefined;
        }
        case 'rotation': {
            const n = shape.rotation;
            return typeof n === 'number'
                ? { category: 'rotation', value: `${n}deg`, numericValue: n }
                : undefined;
        }
        case 'opacity': {
            const n = shape.opacity;
            return typeof n === 'number'
                ? { category: 'opacity', value: String(n), numericValue: n }
                : undefined;
        }
        case 'x':
        case 'y': {
            const n = shape[attribute];
            const v = pxOrUndefined(n);
            return v ? { category: 'dimension', value: v, numericValue: n } : undefined;
        }
        case 'shadow': {
            const shadows = shape.shadow;
            if (!shadows || shadows.length === 0)
                return undefined;
            const v = shadows.map(shadowToStyle).filter(Boolean).join(', ');
            return v ? { category: 'shadow', value: v } : undefined;
        }
        case 'fontSize':
        case 'lineHeight':
        case 'letterSpacing':
        case 'fontFamily':
        case 'fontWeight':
        case 'textCase':
        case 'textDecoration': {
            if (shape.type !== 'text')
                return undefined;
            const text = shape;
            const textLeafKey = attribute === 'textCase' ? 'textTransform' : attribute;
            const raw = findFirstLeaf(text, textLeafKey);
            if (raw === undefined || raw === null || raw === '')
                return undefined;
            const str = String(raw);
            const asNum = Number(str);
            if (attribute === 'fontSize' || attribute === 'letterSpacing') {
                const v = Number.isFinite(asNum) ? `${asNum}px` : str;
                return {
                    category: 'typography',
                    value: v,
                    numericValue: Number.isFinite(asNum) ? asNum : undefined,
                };
            }
            return { category: 'typography', value: str };
        }
        case 'typography': {
            // Composite typography token (the usual way a whole type style is
            // applied). It has no single CSS value, so we build a readable
            // preview from the first text leaf — family + size — falling back
            // to a generic label so the token still surfaces in the inventory.
            if (shape.type !== 'text')
                return { category: 'typography', value: 'Typography' };
            const text = shape;
            const family = findFirstLeaf(text, 'fontFamily');
            const size = findFirstLeaf(text, 'fontSize');
            const parts = [];
            if (family !== undefined && family !== null && family !== '')
                parts.push(String(family));
            if (size !== undefined && size !== null && size !== '') {
                const asNum = Number(String(size));
                parts.push(Number.isFinite(asNum) ? `${asNum}px` : String(size));
            }
            return { category: 'typography', value: parts.join(' · ') || 'Typography' };
        }
        default:
            return undefined;
    }
}
/**
 * Extract every token applied on the page — not just colors.
 *
 * One pass over `objects`. For each token reference we track the first resolved
 * value (so the UI has a preview) and the number of shapes that apply it.
 *
 * The return array is deliberately flat and compact (name / category / value /
 * numericValue? / usageCount) so it can be shipped to the client without the
 * full page payload.
 */
export function extractAllTokens(objects) {
    const acc = new Map();
    for (const shape of Object.values(objects)) {
        const applied = shape.appliedTokens;
        if (!applied)
            continue;
        for (const attribute of Object.keys(applied)) {
            const tokenName = applied[attribute];
            if (!tokenName)
                continue;
            const key = `${attribute}::${tokenName}`;
            const existing = acc.get(key);
            if (existing) {
                existing.count++;
                continue;
            }
            const resolved = resolveTokenValue(attribute, shape);
            if (!resolved)
                continue;
            acc.set(key, {
                category: resolved.category,
                attribute,
                value: resolved.value,
                numericValue: resolved.numericValue,
                count: 1,
            });
        }
    }
    const result = [];
    for (const [key, v] of acc) {
        const idx = key.indexOf('::');
        const name = key.slice(idx + 2);
        result.push({
            name,
            category: v.category,
            attribute: v.attribute,
            value: v.value,
            numericValue: v.numericValue,
            usageCount: v.count,
        });
    }
    return result;
}
//# sourceMappingURL=tokens.js.map