import type { Fill, Gradient } from '../../penpot.types';
import type { ConverterContext } from '../types';
export declare function linearGradientToStyle(gradient: Gradient): string;
export declare function radialGradientToStyle(gradient: Gradient): string;
export declare function solidFillToStyle(fill: Fill): string;
export declare function imageFillToStyle(fill: Fill, ctx: ConverterContext): string;
export declare function fillsToOutput(fills: Fill[] | null | undefined, ctx: ConverterContext, fillTokenName?: string): string;
