import type { ShapeCommon } from '../../penpot.types';
import type { ConverterContext } from '../types';
export interface BaseStyleOptions {
    shadows?: 'box' | 'text' | 'filter';
    transform?: boolean;
}
export declare function baseStyles(shape: ShapeCommon, ctx: ConverterContext, opts?: BaseStyleOptions): string;
