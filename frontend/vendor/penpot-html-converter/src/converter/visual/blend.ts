import type { BlendMode } from '../../penpot.types';
import { decl } from '../decl';

const BLEND_MODE_VALUE: Record<Exclude<BlendMode, 'normal'>, string> = {
  multiply: 'multiply',
  screen: 'screen',
  overlay: 'overlay',
  darken: 'darken',
  lighten: 'lighten',
  'color-dodge': 'color-dodge',
  'color-burn': 'color-burn',
  'hard-light': 'hard-light',
  'soft-light': 'soft-light',
  difference: 'difference',
  exclusion: 'exclusion',
  hue: 'hue',
  saturation: 'saturation',
  color: 'color',
  luminosity: 'luminosity',
};

export function blendModeToStyle(mode: BlendMode | undefined): string {
  if (!mode || mode === 'normal') return '';
  return decl.mixBlendMode(
    BLEND_MODE_VALUE[mode] as Parameters<typeof decl.mixBlendMode>[0],
  );
}

export function opacityToStyle(opacity: number | undefined): string {
  if (opacity === undefined || opacity === 1) return '';
  return decl.opacity(opacity);
}

export function hiddenToStyle(hidden: boolean | undefined): string {
  return hidden === true ? decl.display('none') : '';
}
