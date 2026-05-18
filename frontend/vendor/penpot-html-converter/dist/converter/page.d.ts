import type { Page } from '../penpot.types';
import type { ConverterContext } from './types';
/**
 * Converts a full Penpot `Page` to an HTML string.
 *
 * Uses `buildTree` to find the root frame, then renders its children directly
 * without wrapping them in the root frame element.
 * If `page.options.background` is set, the background colour is passed via context.
 */
export declare function renderPage(page: Page, ctx: ConverterContext): string;
