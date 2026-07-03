import { escapeHtml } from '../utils/html';
import { mergeStyles } from '../utils/style';
import { resolvePositionOutput } from '../visual/position';
import { baseStyles } from '../visual/base';
// An svg-raw shape originates from a user-imported SVG, so its tag names,
// attribute names and attribute values are all attacker-controlled. The
// converter output is inlined into a preview iframe that runs with
// `allow-same-origin` (effectively unsandboxed), so any element/handler we
// emit executes in the app origin. We therefore serialize from an allowlist
// instead of trusting the input: names that aren't plain (SVG) identifiers
// are dropped, event-handler attributes are dropped, and URL-bearing
// attributes are scheme-checked. The previous regex denylist (`sanitizeSvg`)
// was trivially bypassable — unquoted `onload=…` handlers, split
// `<scr<script>…</script>ipt>` reassembly, `javascript:` URLs — and has been
// removed.
const SAFE_NAME_RE = /^[a-zA-Z][a-zA-Z0-9-]*$/;
const SAFE_ATTR_RE = /^[a-zA-Z][a-zA-Z0-9-]*(?::[a-zA-Z][a-zA-Z0-9-]*)?$/;
// Tags that must never be emitted even though they are valid identifiers:
// they carry script / foreign-HTML execution semantics inside SVG.
const FORBIDDEN_TAGS = new Set(['script', 'foreignobject', 'iframe', 'use']);
// Attributes whose value is a URL. Only http(s), mailto, in-document
// fragments, root/relative paths and non-scripting image data URIs are
// allowed through; `javascript:`, `vbscript:`, `data:text/html`, … are
// dropped.
const URL_ATTRS = new Set(['href', 'xlink:href', 'src']);
const SAFE_URL_RE = /^(?:https?:|mailto:|#|\/|data:image\/(?!svg))/i;
function attrIsSafe(key, value) {
    if (!SAFE_ATTR_RE.test(key))
        return false;
    if (/^on/i.test(key))
        return false; // event handlers
    if (URL_ATTRS.has(key.toLowerCase()) && !SAFE_URL_RE.test(value.trim()))
        return false;
    return true;
}
function serializeNode(node) {
    // A string node is text content: always escaped, never parsed as markup.
    if (typeof node === 'string')
        return escapeHtml(node);
    if (!node.tag || !SAFE_NAME_RE.test(node.tag) || FORBIDDEN_TAGS.has(node.tag.toLowerCase())) {
        return '';
    }
    const attrs = node.attrs
        ? Object.entries(node.attrs)
            .filter(([k, v]) => attrIsSafe(k, String(v)))
            .map(([k, v]) => ` ${k}="${escapeHtml(String(v))}"`)
            .join('')
        : '';
    const children = (node.content ?? []).map(serializeNode).join('');
    return children ? `<${node.tag}${attrs}>${children}</${node.tag}>` : `<${node.tag}${attrs} />`;
}
export function renderSvgRaw(shape, ctx) {
    const base = baseStyles(shape, ctx);
    // Both branches go through the allowlist serializer: a structured content
    // tree is walked node-by-node (the normal Penpot shape); a raw string is
    // treated as inert escaped text rather than trusted markup.
    const safeContent = serializeNode(shape.content);
    const posStyle = resolvePositionOutput(shape, ctx);
    const style = mergeStyles(posStyle, base);
    const attrs = [
        `data-id="${escapeHtml(String(shape.id))}"`,
        `data-type="${escapeHtml(String(shape.type))}"`,
        style ? `style="${style}"` : '',
    ]
        .filter(Boolean)
        .join(' ');
    return `<div ${attrs}>${safeContent}</div>`;
}
//# sourceMappingURL=svg-raw.js.map