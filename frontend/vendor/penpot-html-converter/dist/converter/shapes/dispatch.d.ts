import type { Shape } from '../../penpot.types';
import type { ConverterContext } from '../types';
/**
 * Dispatches a shape to its type-specific renderer.
 * Unknown shape types fall back to an empty string.
 */
export declare function renderShape(shape: Shape, objects: Record<string, Shape>, ctx: ConverterContext): string;
