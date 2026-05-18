import type { Page } from '../penpot.types';
import type { ConverterContext } from './types';
import { buildTree, getChildren } from './tree';
import { renderShape } from './render';

/**
 * Converts a full Penpot `Page` to an HTML string.
 *
 * Uses `buildTree` to find the root frame, then renders its children directly
 * without wrapping them in the root frame element.
 * If `page.options.background` is set, the background colour is passed via context.
 */
export function renderPage(page: Page, ctx: ConverterContext): string {
  const root = buildTree(page.objects);

  const bgCtx: ConverterContext = {
    ...(page.options?.background ? { ...ctx, _pageBackground: page.options.background } : ctx),
    _isCanvasTopLevel: true,
  };

  return getChildren(root, page.objects)
    .map((child) => renderShape(child, page.objects, bgCtx))
    .join('');
}
