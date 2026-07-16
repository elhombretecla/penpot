import { isIdentityMatrix, matrixToCss, multiplyMatrices, shapePageTransform, pageTransformToCssMatrix, IDENTITY_MATRIX, } from '../utils/transform';
import { mergeStyles } from '../utils/style';
import { px } from '../utils/css';
import { decl } from '../decl';
// Penpot's `transform` matrix already ENCODES the rotation (plus any flips),
// applied around the selrect center — which matches the CSS default
// `transform-origin: 50% 50%` because the element box is the untransformed
// selrect. Penpot's own CSS codegen emits the matrix alone; emitting
// `rotate()` alongside it double-applies the rotation (and since the old
// code negated the rotate, the two cancelled out — rotated shapes rendered
// unrotated). The `rotation` value is only a fallback for shapes with no
// matrix.
//
// Because Penpot stores transforms in PAGE space while CSS transforms NEST,
// a shape inside a transformed container must emit the RELATIVE transform
// `inv(T_container) ∘ T_shape` — the container passes `inv(T_container)`
// down via `ctx._invParentTransform` (see frame.ts / group.ts). For shapes
// whose visual content is already baked in page coordinates (paths, bools),
// pass `ownTransformBaked: true` so only the counter-transform is emitted.
export function combinedTransformStyle(shape, ctx, ownTransformBaked = false) {
    const hasRotation = !!shape.rotation;
    const hasMatrix = shape.transform !== undefined && !isIdentityMatrix(shape.transform);
    const invParent = ctx?._invParentTransform;
    if (!invParent && !ownTransformBaked && !hasMatrix) {
        if (!hasRotation)
            return '';
        return decl.transform(`rotate(${shape.rotation ?? 0}deg)`);
    }
    const own = ownTransformBaked ? IDENTITY_MATRIX : shapePageTransform(shape);
    const relative = invParent ? multiplyMatrices(invParent, own) : own;
    if (isIdentityMatrix(relative))
        return '';
    return decl.transform(matrixToCss(pageTransformToCssMatrix(relative, shape)));
}
export function absolutePositionStyle(shape, isChildOfRoot = false, offsetX = 0, offsetY = 0) {
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
function relativePositionStyle(shape) {
    return [
        decl.position('relative'),
        decl.width(shape.width ?? 0),
        decl.height(shape.height ?? 0),
    ].join(' ');
}
export function topLevelPositionStyle(shape, isChildOfRoot = false) {
    const position = isChildOfRoot && shape.fixedScroll ? 'fixed' : 'absolute';
    const x = shape.x ?? 0;
    const y = shape.y ?? 0;
    return mergeStyles(decl.position(position), decl.top(0), decl.left(0), decl.width(shape.width ?? 0), decl.height(shape.height ?? 0), decl.transform(`translate(${px(x)}, ${px(y)})`));
}
export function resolvePositionOutput(shape, ctx) {
    let positionStyle;
    if (ctx._parentIsLayout) {
        const itemStyles = ctx._parentLayoutItemStyles ?? '';
        const itemHasWidth = /(^|\s|;)\s*width\s*:/.test(itemStyles);
        const itemHasHeight = /(^|\s|;)\s*height\s*:/.test(itemStyles);
        const parts = [];
        if (!itemHasWidth) {
            parts.push(ctx._parentIsLayoutAutoW ? decl.width(shape.width ?? 0) : decl.width('100%'));
        }
        if (!itemHasHeight) {
            parts.push(ctx._parentIsLayoutAutoH ? decl.height(shape.height ?? 0) : decl.height('100%'));
        }
        positionStyle = parts.join(' ');
    }
    else if (ctx._forceRelative) {
        positionStyle = relativePositionStyle(shape);
    }
    else if (ctx._isCanvasTopLevel) {
        positionStyle = topLevelPositionStyle(shape, ctx._isChildOfRoot);
    }
    else {
        positionStyle = absolutePositionStyle(shape, ctx._isChildOfRoot, ctx._offsetX ?? 0, ctx._offsetY ?? 0);
    }
    if (ctx._parentLayoutItemStyles) {
        return mergeStyles(positionStyle, ctx._parentLayoutItemStyles);
    }
    return positionStyle;
}
//# sourceMappingURL=position.js.map