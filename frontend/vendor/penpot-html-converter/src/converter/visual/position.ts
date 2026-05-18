import type { ShapeCommon } from '../../penpot.types';
import type { ConverterContext } from '../types';
import { isIdentityMatrix, matrixToCss } from '../utils/transform';
import { mergeStyles } from '../utils/style';
import { px } from '../utils/css';
import { decl } from '../decl';

export function combinedTransformStyle(shape: ShapeCommon): string {
  const hasRotation = !!shape.rotation;
  const hasMatrix = shape.transform !== undefined && !isIdentityMatrix(shape.transform);

  if (!hasRotation && !hasMatrix) return '';

  const parts: string[] = [];
  if (hasRotation) parts.push(`rotate(${-(shape.rotation ?? 0)}deg)`);
  if (hasMatrix) parts.push(matrixToCss(shape.transform!));

  return decl.transform(parts.join(' '));
}

export function absolutePositionStyle(
  shape: ShapeCommon,
  isChildOfRoot = false,
  offsetX = 0,
  offsetY = 0,
): string {
  const position = isChildOfRoot && shape.fixedScroll ? 'fixed' : 'absolute';
  const x = (shape.x ?? 0) - offsetX;
  const y = (shape.y ?? 0) - offsetY;
  return [
    decl.position(position),
    decl.left(x),
    decl.top(y),
    decl.width(shape.width ?? 0),
    decl.height(shape.height ?? 0),
  ].join(' ');
}

function relativePositionStyle(shape: ShapeCommon): string {
  return [
    decl.position('relative'),
    decl.width(shape.width ?? 0),
    decl.height(shape.height ?? 0),
  ].join(' ');
}

export function topLevelPositionStyle(shape: ShapeCommon, isChildOfRoot = false): string {
  const position = isChildOfRoot && shape.fixedScroll ? 'fixed' : 'absolute';
  const x = shape.x ?? 0;
  const y = shape.y ?? 0;
  return mergeStyles(
    decl.position(position),
    decl.top(0),
    decl.left(0),
    decl.width(shape.width ?? 0),
    decl.height(shape.height ?? 0),
    decl.transform(`translate(${px(x)}, ${px(y)})`),
  );
}

export function resolvePositionOutput(shape: ShapeCommon, ctx: ConverterContext): string {
  let positionStyle: string;
  if (ctx._parentIsLayout) {
    const itemStyles = ctx._parentLayoutItemStyles ?? '';
    const itemHasWidth = /(^|\s|;)\s*width\s*:/.test(itemStyles);
    const itemHasHeight = /(^|\s|;)\s*height\s*:/.test(itemStyles);

    const parts: string[] = [];
    if (!itemHasWidth) {
      parts.push(ctx._parentIsLayoutAutoW ? decl.width(shape.width ?? 0) : decl.width('100%'));
    }
    if (!itemHasHeight) {
      parts.push(ctx._parentIsLayoutAutoH ? decl.height(shape.height ?? 0) : decl.height('100%'));
    }
    positionStyle = parts.join(' ');
  } else if (ctx._forceRelative) {
    positionStyle = relativePositionStyle(shape);
  } else if (ctx._isCanvasTopLevel) {
    positionStyle = topLevelPositionStyle(shape, ctx._isChildOfRoot);
  } else {
    positionStyle = absolutePositionStyle(
      shape,
      ctx._isChildOfRoot,
      ctx._offsetX ?? 0,
      ctx._offsetY ?? 0,
    );
  }

  if (ctx._parentLayoutItemStyles) {
    return mergeStyles(positionStyle, ctx._parentLayoutItemStyles);
  }
  return positionStyle;
}
