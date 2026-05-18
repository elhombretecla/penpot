import { decl } from '../decl';
const BLEND_MODE_VALUE = {
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
export function blendModeToStyle(mode) {
    if (!mode || mode === 'normal')
        return '';
    return decl.mixBlendMode(BLEND_MODE_VALUE[mode]);
}
export function opacityToStyle(opacity) {
    if (opacity === undefined || opacity === 1)
        return '';
    return decl.opacity(opacity);
}
export function hiddenToStyle(hidden) {
    return hidden === true ? decl.display('none') : '';
}
//# sourceMappingURL=blend.js.map