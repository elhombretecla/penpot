import type { ShapeCommon } from '../../penpot.types';
import { decl } from '../decl';

export function radiusToStyle(shape: ShapeCommon): string {
  const r1 = shape.r1 ?? 0;
  const r2 = shape.r2 ?? 0;
  const r3 = shape.r3 ?? 0;
  const r4 = shape.r4 ?? 0;

  if (
    shape.r1 === undefined &&
    shape.r2 === undefined &&
    shape.r3 === undefined &&
    shape.r4 === undefined
  ) {
    return '';
  }

  if (r1 === r2 && r2 === r3 && r3 === r4) {
    return decl.borderRadius(r1);
  }

  return decl.borderRadius([r1, r2, r3, r4]);
}
