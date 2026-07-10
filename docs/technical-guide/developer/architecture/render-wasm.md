---
title: Render engine (WASM)
desc: Architecture and performance model of Penpot's WASM render engine — Rust + Skia compiled to WebAssembly, driven from ClojureScript.
---

### Render engine (WASM)

The workspace and viewer canvas are rendered by a dedicated engine written in
Rust on top of [Skia](https://skia.org/) (via `skia-safe`), compiled to
WebAssembly with Emscripten and driven from the ClojureScript frontend. It
replaces the legacy SVG-DOM renderer to make render cost independent of the
browser's SVG pipeline and to give Penpot direct control over caching,
partial rendering and GPU usage.

Source lives in two places:

* `render-wasm/` — the Rust crate (`src/`), its build scripts (`build`,
  `watch`, `test`, `lint`) and deep-dive docs (`render-wasm/docs/`).
* `frontend/src/app/render_wasm/` — the ClojureScript bridge: serialization,
  render-loop driving, and integration with workspace data events.

#### Module map

Rust side (`render-wasm/src/`):

| Module | Role |
|---|---|
| `main.rs`, `wasm/*` | FFI surface: `#[no_mangle] extern "C"` exports and payload deserialization. |
| `state.rs`, `state/shapes_pool.rs` | Design model: a contiguous pool of `Shape`s indexed by UUID, plus transient per-frame state (transform modifiers, structure overrides, scale-content). |
| `shapes.rs`, `shapes/*` | Shape types, geometry (selrect/extrect/bounds), text content. |
| `render.rs`, `render/*` | The render engine: tile pipeline, surfaces, tile texture cache, document atlas, text/fills/strokes/shadows painters, font and image stores. |
| `tiles.rs` | Tile math and the tile index (`Tile ↔ shapes` two-way map), pending-tile priority queue. |
| `mem.rs` | The JS↔WASM byte-transfer slot (see FFI protocol). |

ClojureScript side (`frontend/src/app/render_wasm/`):

| Namespace | Role |
|---|---|
| `api.cljs` | Hub: module init, render loop, viewport sync, `set-object`/load pipeline, image/font fetching. |
| `api/shapes.cljs` | Batched base-props serialization (one 104-byte record + one call per shape). |
| `shape.cljs` | `ShapeProxy` type; records per-attribute changes into `*shape-changes*` and pushes them to WASM after each commit. |
| `mem.cljs`, `serializers.cljs` | Heap allocation and typed writes. |
| `text_editor.cljs` | WASM text editor session glue. |
| `app.main.data.changes` | The state→WASM bridge: attribute changes flow per-attribute; structural changes (`:add-obj`, `:mov-objects`) re-serialize whole shapes. |

#### FFI protocol

* Scalar arguments are passed directly on exported functions; variable-length
  payloads go through a **single global byte slot** (`mem.rs`). The caller
  allocates, writes, calls the export, and the export consumes the slot.
  **Never nest two buffer-consuming calls** — the slot holds one payload at a
  time. (A leaked payload no longer panics the renderer; it is dropped with a
  debug assertion.)
* Errors surface via an error code slot: `0x01` recoverable, `0x02` panic.
* Batched base props: `frontend/src/app/render_wasm/api/shapes.cljs` and
  `render-wasm/src/wasm/shapes/base_props.rs` share a `#[repr(C)]` 104-byte
  layout, offset-tested on the Rust side. This is the template for further
  batching: the designed next step is a multi-shape TLV stream
  (`u32 version | u32 count`, then per shape the base-props record plus
  `{u8 tag | u32 len | payload}` sections reusing today's per-attribute wire
  formats verbatim), so a whole load chunk becomes a single call.
* WASM→JS notifications use DOM `CustomEvent`s registered in
  `src/js/wapi.js`: `penpot:wasm:tiles-complete` (render finished) and
  `penpot:wasm:images-evicted` (image cache pressure; see Memory).
* Do not change an exported signature without updating the CLJS bridge in the
  same change. Prefer adding new exports.

#### Shape pool and per-frame state

`ShapesPool` stores every shape of the current page in one `Vec<Shape>` with
a `Uuid → index` map. Interactive transforms don't mutate shapes: matrices
arrive per pointer-move (`set_modifiers`) and `get()` lazily builds a
**modified clone** per shape per frame, cached in a `OnceCell` that is
cleared by `clean_all` at gesture end (and re-seeded on each modifier set).

* Deletions are soft (`mark_deleted`); the pool **compacts** automatically
  (`maybe_compact`) once garbage exceeds `max(1024, live/2)` slots, only on
  quiescent frames with no transient state. Undo re-creates shapes through
  the regular structural sync, so reclaiming their slots is safe.
* Geometry caches: each shape caches its `extrect` in a `RefCell`
  (translation-only transforms shift it instead of invalidating); the render
  state memoizes the moved-bounds union once per rAF (`begin_frame_memos`).

#### Tile pipeline

The canvas is rendered in 512-px tiles:

1. A **tile index** maps tiles to the top-level shapes overlapping them,
   clamped to the *interest area* (viewport plus a configurable ring). It is
   maintained incrementally by edits (`rebuild_touched_tiles` runs each rAF)
   and re-derived on viewport changes. A re-index is skipped entirely when
   the interest area and scale match the last completed pass
   (`indexed_tile_key`), which makes sub-tile pan pauses free.
2. **Pending tiles** are processed closest-to-center first, partitioned by
   (visible, cached) so visible uncached tiles get priority.
3. Rendering is **progressive**: the walker yields (`FrameType::Partial`)
   when the frame budget is exhausted and the CLJS loop re-requests the next
   slice on the following rAF. While a view gesture is live (fast mode) the
   pause-render also yields — a mid-pan pause must never block the main
   thread rendering every pending tile at once.
4. **Fast mode** (pan/zoom) and **interactive transform** (drag/resize) skip
   expensive effects (shadows, blur, antialiasing) and preserve the last
   presented frame, re-rendering only invalidated tiles.
5. Caching layers: a per-tile **texture cache** (slots derive from the
   provider's real capacity; allocation failure force-evicts instead of
   panicking), the **document atlas** (`DocAtlas`, doc-space snapshot used by
   `render_from_cache` for instant pan/zoom feedback; grows geometrically
   ≥1.5× on tile-quantized bounds, clamped to the document bounds so
   speculative growth never forces a premature downscale), and the
   **backbuffer crop cache** (per-shape crops used to blit stationary
   content during drags; one shared copy-on-write backbuffer snapshot per
   rebuild).

The gesture contract on the CLJS side: `view-interaction-start!` /
`finalize-view-interaction!` are driven by input events (pan, zoom and
scrollbar drags all set a `workspace-local` flag checked by
`view-gesture-active?`); the 100 ms debounced `render-finish` is only a
fallback for wheel/trackpad gestures, and a mid-gesture pause re-renders in
fast mode without ending the interaction.

#### Text pipeline

Text shaping (the expensive step — Skia shapes on the first
`Paragraph::layout`) is decoupled from painting:

* Each `TextContent` owns a **variant cache** (`Rc<RefCell<TextVariantCache>>`)
  holding built, laid-out `Paragraph`s per render variant: plain fill,
  alpha-stripped shadow fill, opaque mask (emoji/stroke masking), and one
  entry per visible stroke (normal and shadow). The `Rc` is shared with the
  per-frame drag clones, so **dragging a text does not re-shape it**.
* Cache key: content version, layout width, font-store generation, a
  snapshot of visible strokes, plus — only for gradient/image fills, which
  bake absolute coordinates into the paint shader — the text bounds and the
  image-store generation.
* A pure width change re-runs `layout(width)` on the cached paragraphs
  (line-breaking only, no re-shaping). Content/style edits bump the content
  version; font loads bump the font generation; `scale_content` detaches the
  clone's cache (its scale is invisible to the key).
* Painting computes placements (positions + decoration segments) from the
  cached paragraphs on each draw; placements are cheap relative to shaping.

The font store flushes Skia's shaping caches **lazily** (once, on the next
`font_collection()` access) instead of once per registered font, and answers
`has_family` from a hash set.

#### Memory management

* **Image store**: byte-budgeted LRU (512 MB default; raw size for encoded
  images, `w×h×4` for GPU textures). Entries are stamped per frame; when the
  budget is exceeded it first drops thumbnails superseded by full-resolution
  images, then evicts least-recently-used entries not touched in the last
  few frames. Evicted full images are reported through
  `penpot:wasm:images-evicted` and the frontend re-fetches them on demand
  (storing an image touches every shape that uses it, so the affected tiles
  repaint).
* **Shape pool**: compaction as described above.
* **Emscripten heap**: 256 MB initial, geometric growth (see `_build_env`).

#### Build, test, profiling

* `render-wasm/build` / `watch` — Emscripten build (`wasm32-unknown-emscripten`),
  prebuilt Skia binaries (`SKIA_BINARIES_URL`). Release links with `-O3`
  (Binaryen optimization included). WASM SIMD is enabled for the Rust side
  (`.cargo/config.toml` + `-msimd128`); rebuilding the Skia binaries with
  SIMD is the natural follow-up. Threads/SharedArrayBuffer are an explicit
  non-goal (COOP/COEP + Skia rebuild + single-threaded GL assumptions).
* `render-wasm/test` — native (`x86_64`) unit tests; `render-wasm/lint` —
  clippy against the wasm target.
* Cargo features `stats`, `profile`, `profile-marks`, `profile-raf` add
  counters and performance marks; the CLJS side mirrors them in
  `app.render-wasm.performance`.
* Visual regression: Playwright project `render-wasm`
  (`frontend/playwright/ui/render-wasm-specs/`); conventions in
  `render-wasm/docs/visual_regression_tests.md`.
* In the devenv, run container commands as UID 1000 (`-u 1000:1000`) so
  build artifacts stay owned by the workspace user.

#### Deep dives

The Rust-side details live next to the crate and are kept up to date there:

* `render-wasm/docs/rendering_architecture.md` — surfaces and render passes.
* `render-wasm/docs/tile_rendering.md` — tile pipeline internals.
* `render-wasm/docs/serialization.md` — wire formats.
* `render-wasm/docs/texts.md`, `render-wasm/docs/text_editor.md` — text
  subsystem.
