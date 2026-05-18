import { renderShape } from './render';
import { renderPage } from './page';
import { buildTree, getChildren } from './tree';
export { buildPenpotFontsCss } from './utils/fonts';
export { decl, SUPPORTED_PROPS } from './decl';
export { extractTokens, tokensToCss, tokenToCssVar, tokenToCssVarName, extractAllTokens } from './tokens';
// NOTE: Upstream uses `oxfmt` to pretty-print the HTML when `ctx.format !== false`.
// The Penpot integration always passes `format: false`, so this helper is a no-op
// and the `oxfmt` dependency has been removed from the vendored copy.
async function formatHtml(html) {
    return html;
}
function extractFonts(collector) {
    return Array.from(collector.values());
}
/**
 * Converts a full Penpot page to an HTML string (body content only, no `<html>` wrapper).
 */
export async function convertPage(page, ctx) {
    const fontCollector = new Map();
    const html = renderPage(page, { ...ctx, _fontCollector: fontCollector });
    const shouldFormat = ctx.format !== false;
    return {
        html: shouldFormat ? await formatHtml(html) : html,
        fonts: extractFonts(fontCollector),
    };
}
/**
 * Converts a single Penpot shape and all its descendants to a standalone HTML snippet.
 *
 * The root shape is forced to `relative` positioning so it can be embedded anywhere.
 * Returns the rendered div tree without any `<html>` or `<body>` wrapper.
 */
export async function convertShape(shape, allObjects, ctx) {
    const fontCollector = new Map();
    const isRootFrame = shape.parentId === shape.id;
    let html;
    if (isRootFrame) {
        // Root frame is never rendered — render its children directly
        const childIds = shape.shapes ?? [];
        const canvasCtx = {
            ...ctx,
            _isCanvasTopLevel: true,
            _fontCollector: fontCollector,
        };
        html = childIds
            .map((id) => {
            const child = allObjects[id];
            return child ? renderShape(child, allObjects, canvasCtx) : '';
        })
            .join('');
    }
    else {
        html = renderShape(shape, allObjects, {
            ...ctx,
            _forceRelative: true,
            _fontCollector: fontCollector,
        });
    }
    const shouldFormat = ctx.format !== false;
    return {
        html: shouldFormat ? await formatHtml(html) : html,
        fonts: extractFonts(fontCollector),
    };
}
/**
 * Converts a Penpot page into per-shape results, one entry per top-level canvas shape.
 * Useful for rendering only the shapes currently visible in the viewport.
 */
export async function convertPageShapes(page, ctx) {
    const fontCollector = new Map();
    const shapeCtx = {
        ...ctx,
        ...(page.options?.background ? { _pageBackground: page.options.background } : {}),
        _isCanvasTopLevel: true,
        _fontCollector: fontCollector,
    };
    const root = buildTree(page.objects);
    const shouldFormat = ctx.format !== false;
    const shapes = await Promise.all(getChildren(root, page.objects).map(async (child) => {
        const raw = renderShape(child, page.objects, shapeCtx);
        const html = shouldFormat ? await formatHtml(raw) : raw;
        return {
            id: child.id,
            html,
            x: child.selrect.x,
            y: child.selrect.y,
            width: child.selrect.width,
            height: child.selrect.height,
        };
    }));
    return { shapes, fonts: extractFonts(fontCollector) };
}
//# sourceMappingURL=index.js.map