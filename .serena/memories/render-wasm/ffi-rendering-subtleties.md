# render-wasm FFI and Rendering Subtleties

## FFI state and errors

- The renderer uses one unsafe global `STATE`; the `with_state*` macros currently panic on invalid state pointer. Treat state pointer validity as critical, not recoverable.
- `#[wasm_error]` clears the error code on entry. Recoverable errors set code `0x01`, critical errors/panics set `0x02`, free the byte buffer, then panic so the CLJS bridge can catch and inspect `_read_error_code`.
- The frontend bridge maps `0x01` to `:non-blocking` and `0x02` to `:panic` in ex-data (`:type :wasm-error`). Check actual bridge code if changing names; older comments/docs may use different labels.
- WASM byte transfer is a single global slot. A caller that receives a pointer result must read and free it before another byte payload is written; errors free the slot via `#[wasm_error]`.

## Shape pool and loading

- Shapes are UUID-indexed, and hierarchy/structure is tracked separately. `ShapesPool::get` may return a cached modified clone when modifiers, structure, scale-content, or bool handling apply; `get_raw` bypasses those derived values.
- Bulk loading uses a `loading` flag. `touch_current` / `touch_shape` avoid tile invalidation while loading; text layouts and final view setup must happen after loading ends.
- Many setters mutate only the current shape selected by `use_shape` / current-shape APIs. If no current shape is selected, some mutation blocks are skipped silently.
- `set_parent_for_current_shape` only sets parent metadata and invalidates parent geometry; children must be updated separately to avoid duplicate children.
- Child deletion marks descendants deleted and removes them from all indexed tiles, preserving undo/redo while avoiding stale pixels after panning.

## Tile/render behavior

- Interactive transforms are distinct from viewport fast mode. `set_modifiers_start` enables fast mode and interactive transform; interactive transform still flushes each animation frame.
- During interactive transform, modifier tile invalidation is deferred to `render()` once per rAF. Outside interactive transform, `set_modifiers` rebuilds modifier tiles immediately.
- `set_modifiers_end` disables fast/interactive state and cancels pending async render; the caller must request the final full-quality render.
- Plain viewport fast mode (`options.is_viewport_interaction()`) renders from cache and does not flush target output inside `process_animation_frame`; interactive transforms do flush.
- Zoom changes rebuild the tile index while preserving cached tile textures. Avoid replacing that path with shallow rebuilds if blur/shadow cache preservation matters.
- Pending tile priority is intentionally reversed by pop order; check the queue construction before changing tile scheduling.
## Performance invariants (render optimization pass, 2026-07)

- `rebuild_tile_index` early-returns when `(interest_rect, scale)` matches the last completed pass (`indexed_tile_key` on RenderState). It is reset whenever the tile index is invalidated wholesale (`rebuild_tiles_from`). New/edited shapes are indexed via `rebuild_touched_tiles` each rAF, never via the viewport re-index — do not reintroduce a dependency on pan-end re-indexing.
- `update_shape_tiles_incremental` has an alloc-free early-out comparing the new TileRect against the indexed set; the HashSet diff only runs when membership changed.
- The moved-bounds union of modified shapes is memoized per rAF (`moved_bounds_frame`, reset in `begin_frame_memos` called from `render()`); the walker runs once per tile and must not recompute it.
- Text: `TextContent` carries a shared `Rc<RefCell<TextVariantCache>>` of built+laid-out paragraphs per variant (fill / fill-shadow / opaque / per-stroke ± shadow). Drag clones share it; shaping happens once per content/style/font change. Key includes bounds + image generation ONLY for gradient/image fills (shaders bake absolute coords). `scale_content` must detach the cache (`detach_variant_cache`). Any new mutation path over paragraphs must go through `paragraphs_mut()` (bumps `content_version`) or the cache will serve stale paragraphs.
- FontStore: `clear_caches` is lazy (flag checked in `font_collection()`), `has_family` is a HashSet lookup, and a `generation` counter feeds the text cache key. ImageStore has a `generation` too.
- ImageStore is a byte-budgeted LRU (512MB): drops superseded thumbnails first, then old LRU entries; evicted full images notify JS (`penpot:wasm:images-evicted` CustomEvent from `src/js/wapi.js`) and `api.cljs` re-fetches them.
- ShapesPool soft deletes go through `mark_deleted`/`mark_undeleted` (they maintain `deleted_count`); `maybe_compact()` physically reclaims slots on quiescent frames (no modifiers/structure/scale state) once garbage > max(1024, live/2).
- Clip stacks in the walker are `Rc<ClipStack>` (`SharedClipStack`): pushing children clones a pointer; only `append_clip` copies the Vec.
- CLJS load path serializes fills/strokes/text-images ONCE per shape (`set-shape-fills-once` etc.), splitting only the pending image fetches by resolution. `apply-svg-derived` early-returns for shapes without `:svg-attrs`.
- Gesture contract: pan, zoom AND scrollbar drags set workspace-local flags (`:panning`/`:zooming`/`:scrolling`) consulted by `view-gesture-active?`; the 100ms `render-finish` debounce is a fallback only. Scrollbars emit `start/finish-scrollbar-panning` and capture the pointer.
- Build: WASM SIMD enabled via `render-wasm/.cargo/config.toml` (+simd128) and `-msimd128` in `_build_env`. Skia prebuilt binaries are still non-SIMD (rebuild is the follow-up).
