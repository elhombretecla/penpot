import { escapeHtml } from '../utils/html';
import { mergeStyles } from '../utils/style';
import { resolvePositionOutput } from '../visual/position';
import { baseStyles } from '../visual/base';
function serializeNode(node) {
    if (typeof node === 'string')
        return escapeHtml(node);
    const attrs = node.attrs
        ? Object.entries(node.attrs)
            .map(([k, v]) => ` ${k}="${escapeHtml(String(v))}"`)
            .join('')
        : '';
    const children = (node.content ?? []).map(serializeNode).join('');
    return children ? `<${node.tag}${attrs}>${children}</${node.tag}>` : `<${node.tag}${attrs} />`;
}
function sanitizeSvg(content) {
    return content
        .replace(/<script[\s\S]*?<\/script>/gi, '')
        .replace(/\s+on\w+="[^"]*"/gi, '')
        .replace(/\s+on\w+='[^']*'/gi, '');
}
export function renderSvgRaw(shape, ctx) {
    const base = baseStyles(shape, ctx);
    const markup = typeof shape.content === 'string' ? shape.content : serializeNode(shape.content);
    const safeContent = sanitizeSvg(markup);
    const posStyle = resolvePositionOutput(shape, ctx);
    const style = mergeStyles(posStyle, base);
    const attrs = [
        `data-id="${shape.id}"`,
        `data-type="${shape.type}"`,
        style ? `style="${style}"` : '',
    ]
        .filter(Boolean)
        .join(' ');
    return `<div ${attrs}>${safeContent}</div>`;
}
//# sourceMappingURL=svg-raw.js.map