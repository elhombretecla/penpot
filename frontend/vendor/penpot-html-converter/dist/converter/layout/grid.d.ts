import type { GridTrack, GridCell, FrameShape, Uuid } from '../../penpot.types';
export declare function gridTracksToStyle(tracks: GridTrack[], axis: 'columns' | 'rows'): string;
export declare function gridCellStyle(cell: GridCell): string;
export declare function findCellForShape(frame: FrameShape, shapeId: Uuid): GridCell | undefined;
