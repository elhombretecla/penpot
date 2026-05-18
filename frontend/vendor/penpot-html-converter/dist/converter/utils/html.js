/**
 * Escapes special HTML characters in a string to prevent XSS.
 *
 * Replaces: `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`, `"` → `&quot;`
 *
 * Single quotes are intentionally not escaped because all generated attributes
 * use double-quote delimiters, making single quotes safe inside attribute values.
 */
export function escapeHtml(text) {
    return String(text ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
/**
 * Builds an HTML tag string.
 *
 * Attributes with `undefined` values are omitted. Attribute values are
 * escaped to prevent XSS. If `children` is `undefined`, the tag is
 * self-closing (`<tag />`). Otherwise an open/close tag is emitted.
 *
 * @example
 * tag('div', { id: 'root' }, '<span>hello</span>')
 * // → '<div id="root"><span>hello</span></div>'
 *
 * tag('img', { src: 'image.png', alt: '' })
 * // → '<img src="image.png" alt="" />'
 */
export function tag(name, attrs, children) {
    const attrStr = Object.entries(attrs)
        .filter(([, v]) => v !== undefined)
        .map(([k, v]) => ` ${k}="${escapeHtml(v)}"`)
        .join('');
    if (children === undefined) {
        return `<${name}${attrStr} />`;
    }
    return `<${name}${attrStr}>${children}</${name}>`;
}
//# sourceMappingURL=html.js.map