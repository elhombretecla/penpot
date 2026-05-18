import { buildTree, getChildren } from './tree';
import { renderShape } from './render';
/**
 * Converts a full Penpot `Page` to an HTML string.
 *
 * Uses `buildTree` to find the root frame, then renders its children directly
 * without wrapping them in the root frame element.
 * If `page.options.background` is set, the background colour is passed via context.
 */
export function renderPage(page, ctx) {
    const root = buildTree(page.objects);
    const bgCtx = {
        ...(page.options?.background ? { ...ctx, _pageBackground: page.options.background } : ctx),
        _isCanvasTopLevel: true,
    };
    return getChildren(root, page.objects)
        .map((child) => renderShape(child, page.objects, bgCtx))
        .join('');
}
//# sourceMappingURL=page.js.map