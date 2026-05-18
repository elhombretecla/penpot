/**
 * Escapes special HTML characters in a string to prevent XSS.
 *
 * Replaces: `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`, `"` → `&quot;`
 *
 * Single quotes are intentionally not escaped because all generated attributes
 * use double-quote delimiters, making single quotes safe inside attribute values.
 */
export declare function escapeHtml(text: string): string;
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
export declare function tag(name: string, attrs: Record<string, string | undefined>, children?: string): string;
