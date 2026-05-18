import type { TextLeaf, ParagraphNode, TextShape, Typography } from '../../penpot.types';
import type { ConverterContext } from '../types';
export declare function textLeafToStyles(leaf: TextLeaf, typographies?: Record<string, Typography>): string;
export declare function textLeafColorStyle(leaf: TextLeaf, fillTokenName?: string, tokens?: Map<string, string>, fallbackColor?: string): string;
export declare function renderParagraph(para: ParagraphNode, fillTokenName?: string, tokens?: Map<string, string>, fallbackColor?: string): string;
export declare function renderText(shape: TextShape, ctx: ConverterContext): string;
