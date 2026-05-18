import type { GridTrack, GridCell, GridCellAlign, FrameShape, Uuid } from '../../penpot.types';
import { decl } from '../decl';

function trackValue(track: GridTrack): string {
  switch (track.type) {
    case 'fixed':
      return `${track.value ?? 0}px`;
    case 'percent':
      return `${track.value ?? 0}%`;
    case 'flex':
      return `${track.value ?? 1}fr`;
    case 'auto':
      return 'auto';
  }
}

export function gridTracksToStyle(tracks: GridTrack[], axis: 'columns' | 'rows'): string {
  if (tracks.length === 0) return '';
  const value = tracks.map(trackValue).join(' ');
  return axis === 'columns' ? decl.gridTemplateColumns(value) : decl.gridTemplateRows(value);
}

// Grid cells use the grid-spec values for align-self (`start` / `end`),
// not the flex-spec aliases — `align-self: start;` is the canonical CSS Grid
// keyword and is what the converter has emitted historically.
const ALIGN_MAP: Partial<Record<GridCellAlign, Parameters<typeof decl.alignSelf>[0]>> = {
  start: 'start',
  center: 'center',
  end: 'end',
  stretch: 'stretch',
};

const JUSTIFY_MAP: Partial<Record<GridCellAlign, Parameters<typeof decl.justifySelf>[0]>> = {
  start: 'start',
  center: 'center',
  end: 'end',
  stretch: 'stretch',
};

export function gridCellStyle(cell: GridCell): string {
  const parts: string[] = [];

  parts.push(decl.gridRowStart(cell.row));
  if (cell.rowSpan > 1) parts.push(decl.gridRowEnd(`span ${cell.rowSpan}`));
  parts.push(decl.gridColumnStart(cell.column));
  if (cell.columnSpan > 1) parts.push(decl.gridColumnEnd(`span ${cell.columnSpan}`));

  if (cell.alignSelf && ALIGN_MAP[cell.alignSelf]) {
    parts.push(decl.alignSelf(ALIGN_MAP[cell.alignSelf]!));
  }
  if (cell.justifySelf && JUSTIFY_MAP[cell.justifySelf]) {
    parts.push(decl.justifySelf(JUSTIFY_MAP[cell.justifySelf]!));
  }

  return parts.join(' ');
}

export function findCellForShape(frame: FrameShape, shapeId: Uuid): GridCell | undefined {
  if (!frame.layoutGridCells) return undefined;
  return Object.values(frame.layoutGridCells).find((cell) => cell.shapes.includes(shapeId));
}
