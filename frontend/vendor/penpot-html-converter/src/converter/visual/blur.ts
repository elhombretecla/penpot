import type { Blur } from '../../penpot.types';
import { decl } from '../decl';

export function blurToStyle(blur: Blur | undefined): string {
  if (!blur || blur.hidden || blur.value === undefined) return '';

  const rounded = Math.round(blur.value * 10) / 10;
  const formatted = rounded % 1 === 0 ? String(rounded | 0) : rounded.toFixed(1);
  const value = `blur(${formatted}px)`;

  return blur.type === 'background-blur' ? decl.backdropFilter(value) : decl.filter(value);
}
