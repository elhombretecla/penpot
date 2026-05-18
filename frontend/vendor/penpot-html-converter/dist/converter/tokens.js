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
    return tokenName.replace(/\./g, '-');
}
/**
 * Returns a CSS `var(--token-name, fallback)` reference for a token. The fallback is the
 * resolved color value from the tokens map, which makes the output readable and provides
 * a safety net if the custom property is missing.
 */
export function tokenToCssVar(tokenName, tokens) {
    const varName = tokenToCssVarName(tokenName);
    const fallback = tokens?.get(tokenName);
    return fallback ? `var(--${varName}, ${fallback})` : `var(--${varName})`;
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
        .map(([name, value]) => `    --${tokenToCssVarName(name)}: ${value};`)
        .join('\n');
    return `:root {\n${props}\n  }`;
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
        case 'fontSize':
        case 'lineHeight':
        case 'letterSpacing':
        case 'fontFamily':
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