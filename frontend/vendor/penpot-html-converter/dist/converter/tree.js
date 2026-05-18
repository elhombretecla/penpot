/** Returns true if the shape has an ordered list of child IDs. */
function hasShapes(shape) {
    return 'shapes' in shape && Array.isArray(shape.shapes);
}
/**
 * Returns the direct children of `shape` looked up from the flat `objects`
 * map, in the z-order defined by the shape's `shapes` array.
 * Shapes whose ID is not present in `objects` are silently skipped.
 */
export function getChildren(shape, objects) {
    if (!hasShapes(shape))
        return [];
    return shape.shapes.reduce((acc, id) => {
        const child = objects[id];
        if (child !== undefined)
            acc.push(child);
        return acc;
    }, []);
}
/**
 * Recursively builds a `ShapeNode` for the given `id`, memoising results
 * in `memo` to avoid redundant work and handle shared-reference shapes.
 */
function buildNode(id, objects, memo) {
    const cached = memo.get(id);
    if (cached !== undefined)
        return cached;
    const shape = objects[id];
    const childIds = hasShapes(shape) ? shape.shapes : [];
    const children = childIds.reduce((acc, childId) => {
        if (objects[childId] !== undefined) {
            acc.push(buildNode(childId, objects, memo));
        }
        return acc;
    }, []);
    const node = { ...shape, _children: children };
    memo.set(id, node);
    return node;
}
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
export function buildTree(objects) {
    const root = Object.values(objects).find((s) => s.parentId === s.id);
    if (root === undefined)
        throw new Error('No root shape found');
    const memo = new Map();
    return buildNode(root.id, objects, memo);
}
//# sourceMappingURL=tree.js.map