import type { FrameShape, Shape } from '../../penpot.types';
import type { ConverterContext } from '../types';
export declare function invTransformForChildren(shape: {
    transform?: import('../../penpot.types').GeomMatrix;
    x?: number | null;
    y?: number | null;
    width?: number | null;
    height?: number | null;
    selrect?: {
        x: number;
        y: number;
        width: number;
        height: number;
    };
}): import('../../penpot.types').GeomMatrix | undefined;
export declare function renderFrame(shape: FrameShape, children: Shape[], objects: Record<string, Shape>, ctx: ConverterContext): string;
