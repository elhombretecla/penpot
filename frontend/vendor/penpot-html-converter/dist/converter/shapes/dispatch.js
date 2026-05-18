import { getChildren } from '../tree';
import { escapeHtml } from '../utils/html';
import { renderRect } from './rect';
import { renderCircle } from './circle';
import { renderImage } from './image';
import { renderPath } from './path';
import { renderBool } from './bool';
import { renderSvgRaw } from './svg-raw';
import { renderGroup } from './group';
import { renderFrame } from './frame';
import { renderText } from './text';
/**
 * Injects attributes (`data-name`, editor-only locked/blocked flags) into the
 * first opening tag of a rendered shape. Lives here so it applies to EVERY
 * call to `renderShape`, including the recursive ones from `frame.ts` and
 * `group.ts` — the public `../render.ts:renderShape` wrapper used to do it
 * but only for the entry call, so child layers lost their `data-name` on
 * read-back. (See `roundtrip.integration.test.ts` for the regression.)
 */
function injectShapeAttrs(html, shape) {
    if (!html)
        return html;
    const attrs = [];
    const name = (shape.name ?? '').trim();
    if (name)
        attrs.push(['data-name', name]);
    const extended = shape;
    if (extended.locked)
        attrs.push(['data-penpot-locked', 'true']);
    if (extended.blocked)
        attrs.push(['data-penpot-blocked', 'true']);
    if (attrs.length === 0)
        return html;
    const attrStr = attrs.map(([k, v]) => `${k}="${escapeHtml(v)}"`).join(' ');
    return html.replace(/^(<\w[\w-]*)/, `$1 ${attrStr}`);
}
/**
 * Dispatches a shape to its type-specific renderer.
 * Unknown shape types fall back to an empty string.
 */
export function renderShape(shape, objects, ctx) {
    let html;
    switch (shape.type) {
        case 'rect':
            html = renderRect(shape, null, ctx);
            break;
        case 'circle':
            html = renderCircle(shape, null, ctx);
            break;
        case 'image':
            html = renderImage(shape, ctx);
            break;
        case 'path':
            html = renderPath(shape, ctx);
            break;
        case 'bool':
            html = renderBool(shape, ctx);
            break;
        case 'svg-raw':
            html = renderSvgRaw(shape, ctx);
            break;
        case 'group': {
            const children = getChildren(shape, objects);
            html = renderGroup(shape, children, objects, ctx);
            break;
        }
        case 'frame': {
            const children = getChildren(shape, objects);
            html = renderFrame(shape, children, objects, ctx);
            break;
        }
        case 'text':
            html = renderText(shape, ctx);
            break;
        default:
            return '';
    }
    return injectShapeAttrs(html, shape);
}
//# sourceMappingURL=dispatch.js.map