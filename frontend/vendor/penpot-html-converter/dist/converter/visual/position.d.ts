import type { ShapeCommon } from '../../penpot.types';
import type { ConverterContext } from '../types';
export declare function combinedTransformStyle(shape: ShapeCommon, ctx?: Pick<ConverterContext, '_invParentTransform'>, ownTransformBaked?: boolean): string;
export declare function absolutePositionStyle(shape: ShapeCommon, isChildOfRoot?: boolean, offsetX?: number, offsetY?: number): string;
export declare function topLevelPositionStyle(shape: ShapeCommon, isChildOfRoot?: boolean): string;
export declare function resolvePositionOutput(shape: ShapeCommon, ctx: ConverterContext): string;
