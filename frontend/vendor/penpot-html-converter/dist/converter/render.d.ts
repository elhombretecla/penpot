import type { Shape } from '../penpot.types';
import type { ConverterContext } from './types';
/**
 * Public entry point for converting a Penpot shape to HTML — delegates to
 * `dispatch.renderShape`, which handles type-specific rendering AND injects
 * the shared `data-name` / `data-penpot-locked` / `data-penpot-blocked`
 * attributes on every shape (including the recursive ones from
 * `shapes/frame.ts` and `shapes/group.ts`).
 *
 * Component instances (shapes with `componentId`) are rendered identically to
 * their underlying type — live component sync is not supported; instances are
 * treated as static HTML.
 *
 * Unknown shape types return an empty string.
 */
export declare function renderShape(shape: Shape, objects: Record<string, Shape>, ctx: ConverterContext): string;
