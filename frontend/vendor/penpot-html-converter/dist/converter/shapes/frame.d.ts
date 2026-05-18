import type { FrameShape, Shape } from '../../penpot.types';
import type { ConverterContext } from '../types';
export declare function renderFrame(shape: FrameShape, children: Shape[], objects: Record<string, Shape>, ctx: ConverterContext): string;
