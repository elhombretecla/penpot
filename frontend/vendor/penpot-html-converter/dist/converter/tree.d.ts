import type { Shape } from '../penpot.types';
/** A shape node in the built tree, augmented with resolved children. */
type ShapeNode = Shape & {
    _children: ShapeNode[];
};
/**
 * Returns the direct children of `shape` looked up from the flat `objects`
 * map, in the z-order defined by the shape's `shapes` array.
 * Shapes whose ID is not present in `objects` are silently skipped.
 */
export declare function getChildren(shape: Shape, objects: Record<string, Shape>): Shape[];
/**
 * Builds an in-memory tree from the flat `objects` record of a Penpot page.
 *
 * The root shape is identified as the shape whose `parentId` equals its own
 * `id` (the self-referencing root that Penpot uses for the page frame).
 *
 * The original `objects` are never mutated; a fresh `ShapeNode` object is
 * created for every shape via spreading.
 *
 * @throws {Error} if no root shape can be found.
 */
export declare function buildTree(objects: Record<string, Shape>): ShapeNode;
export {};
