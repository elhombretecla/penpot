---
title: 3.13. HTML Mode — Prototype Interactions
desc: How Penpot's edit-mode prototype interactions (click, hover, after-delay, navigate, open/close overlay, prev-screen, open-url) are translated into HTML/CSS/JS and executed inside the HTML Mode Prototype tab.
---

# HTML Mode — Prototype Interactions

The **Prototype** tab of HTML Mode is an interactive prototype runner.
Interactions that designers author in edit mode (the same data the SVG-
based *View Mode* consumes — clicks, hovers, navigation between
boards, overlays, animations) are translated at preview time into real
HTML / CSS / JS behavior inside the sandboxed iframe.

This document is a deep-dive for developers extending or maintaining
the feature. For an overview of HTML Mode itself, see
[`3.12. HTML Mode`](./html-mode.md).

## What this gives users

Open a Penpot file with prototype interactions wired in edit mode,
switch the viewer into **HTML Mode → Prototype**, and the prototype
runs natively in HTML:

- Hotspots have a `pointer` cursor.
- Clicks fire `:click` / `:mouse-press` interactions.
- `mouseover` / `mouseout` fire `:mouse-enter` / `:mouse-over` /
  `:mouse-leave`.
- `:after-delay` fires automatically after the configured delay.
- `:navigate` swaps the displayed board with the configured animation
  (`:dissolve` / `:slide` / `:push`).
- `:open-overlay` / `:toggle-overlay` / `:close-overlay` render
  positioned overlay iframes with the optional backdrop and "close on
  click outside" behaviors.
- `:prev-screen` rewinds the nav stack.
- `:open-url` opens the URL in a new tab.

**The edit-mode authoring flow and the underlying interaction data
model are untouched** — interactions ship to the frontend as part of
the regular file / page data, and the new code only translates them at
preview time.

## How interactions are stored in Penpot (recap)

Defined in `common/src/app/common/types/shape/interactions.cljc`.
Every shape can carry an `:interactions` vector. Each interaction has:

- **`:event-type`** — one of `:click`, `:mouse-press`, `:mouse-over`,
  `:mouse-enter`, `:mouse-leave`, `:after-delay`.
- **`:action-type`** — one of `:navigate`, `:open-overlay`,
  `:toggle-overlay`, `:close-overlay`, `:prev-screen`, `:open-url`.
- Action-specific fields: `:destination` (uuid), `:url`,
  `:overlay-position`, `:overlay-pos-type`, `:close-click-outside`,
  `:background-overlay`, `:position-relative-to`, `:preserve-scroll`,
  `:delay`.
- Optional `:animation` map for navigate / overlay actions, of type
  `:dissolve` / `:slide` / `:push` with `:duration`, `:easing`,
  `:way`, `:direction`, `:offset-effect` (only the fields each
  animation type uses).

The interactions vector is part of the standard shape serialization
returned by `backend/src/app/rpc/commands/files.clj`'s `get-page` RPC,
so the frontend already has the data — no backend changes were needed.

## Architecture

The implementation splits cleanly between a **parent CLJS controller**
and a **tiny inline JS runtime inside the iframe**:

```
┌─ Parent (CLJS, html_mode.cljs) ─────────────────────────────────┐
│  • State: nav stack, open overlays, current frame, transition   │
│  • Renders: <iframe srcDoc=...> per board / overlay              │
│  • Owns animation orchestration (mounts dest iframe, runs WAAPI, │
│    unmounts source iframe)                                       │
│  • Receives postMessage events from iframe                       │
│  • Emits URL `?index=` changes on navigate                       │
└──┬──────────────────────────────────────────────────────────────┘
   │  initial render emits HTML doc that includes:
   │    <script>window.__PENPOT_INTERACTIONS__ = { id → [ix…] }</script>
   │    <script>window.__PENPOT_ROOT_ID__ = "uuid";</script>
   │    <script>prototype-bridge-script</script>
   ▼
┌─ Iframe (static HTML + tiny JS runtime) ────────────────────────┐
│  • Single delegated listener for click / mouseover / mouseout    │
│  • Walks up DOM from event.target collecting [data-id]; checks   │
│    __PENPOT_INTERACTIONS__ for matching event types              │
│  • For each match, posts to parent: { type, sourceId, interaction│
│    } — except :open-url which is dispatched locally to keep the  │
│    user-gesture context for popup blockers.                      │
│  • Schedules setTimeout for :after-delay on the root frame.      │
│  • Marks interactive shapes with [data-prototype-interactive]    │
│    so a single CSS rule applies the pointer cursor.              │
└─────────────────────────────────────────────────────────────────┘
```

### Board vs stage isolation

In prototype mode the iframe is **board-sized**, not pane-sized. The
parent renders a fixed-dimension `.board-stack` (sized to the current
board's `selrect`) that hosts the iframe(s) and overlays; the
`.preview-stage` around it is a static "stage" with its own neutral
background that **never animates**. Visually:

```
.preview-stage          ← static, neutral background, scroll if needed
   ├── padding (var(--sp-xl)) ─────────────────────────────────┐
   │                                                            │
   │     ┌─ .board-stack (W×H = board's selrect) ───────┐      │
   │     │                                                │      │
   │     │  ┌─ .board-clip (overflow:hidden) ─────┐     │      │
   │     │  │  iframe / transition-from / transition-to │      │
   │     │  └─────────────────────────────────────┘     │      │
   │     │  .overlay-backdrop / .overlay-frame …        │      │
   │     │  (siblings of the clip — can extend past)    │      │
   │     └────────────────────────────────────────────┘      │
   │                                                            │
   └────────────────────────────────────────────────────────────┘
```

Why this matters:

- **No background flicker.** Earlier versions made the entire iframe
  pane-sized with the page background painted across the whole pane.
  During a slide / fade the surrounding background visibly repainted
  per frame. With the iframe sized to the board, only the board area
  ever animates.
- **Interactions are scoped to the board.** Clicking the surrounding
  stage area does nothing — `:close-click-outside` on overlays only
  fires when the user clicks the backdrop inside the board.
- **Animations are clipped.** `.board-clip` is `overflow:hidden`, so
  a slide that translates the iframe by 100% disappears cleanly at
  the board's edge instead of leaking onto the stage. Overlays sit
  OUTSIDE the clip (siblings of it) so they can still extend past
  the board edge, matching the SVG viewer's behavior.
- **Different-sized boards transition cleanly.** During a navigate
  the stack expands to `max(from-w, to-w) × max(from-h, to-h)` so
  neither board is clipped while sliding; once the transition
  commits the stack shrinks back to the destination's dimensions.

### Iframe document layout

Each prototype iframe is laid out so the board fills the iframe
exactly. The converter (`convertShape` in
`frontend/vendor/penpot-html-converter/src/converter/index.ts`) passes
`_forceRelative: true` for the root shape, which makes the converter
emit the board's outer element with `position: relative` + the
shape's own `width / height` — **no** `left / top` / canvas-coord
positioning. So the board lands at the body's origin naturally, with
no translate trick needed:

```html
<html>
<body>  <!-- 100% × 100%; page background; no padding -->
  {converter output}  <!-- position:relative; width:W; height:H -->
  <script>window.__PENPOT_INTERACTIONS__ = …;</script>
  <script>window.__PENPOT_ROOT_ID__ = "uuid";</script>
  <script>prototype-bridge-script</script>
</body>
</html>
```

This is intentionally different from workspace mode's
`build-document`, which DOES translate by `(-page.minX, -page.minY)`
— that one calls `convertPage` (not `convertShape`) and renders
shapes at their absolute canvas coordinates, so the translate is
needed to bring the off-origin content into view. The two modes
hit different converter entry points and need different layouts.

### Why this split

- **Single source of truth in CLJS.** Nav stack, open overlays, and
  in-flight transitions live in the parent. The iframe is "dumb" —
  it dispatches events; it doesn't manage state.
- **The iframe is opaque to React re-renders.** During a transition
  we mount two iframes side-by-side and animate them with WAAPI on
  the parent wrappers. The iframe contents don't have to coordinate
  with the animation.
- **No converter modifications.** The vendored
  `frontend/vendor/penpot-html-converter` already emits `data-id` and
  `data-type` on every shape's root element. The runtime hooks on
  these data attributes; nothing in the converter needs to change.
- **URL `?index=` stays in sync** at the end of a transition, so
  refreshes and link-sharing land on the same board.

## Data flow: a click that triggers `:navigate`

1. Designer authors `:click :navigate → BoardB` with animation
   `:slide :in :right` in edit mode. The interaction is persisted on
   the source shape.
2. User opens the file in viewer → HTML Mode → Prototype tab.
3. Parent calls `render-board-html`, which:
   - Calls `cv/convertShape` on the current board.
   - Calls `harvest-interactions` to project the page's interactions
     into a JSON-serialisable map.
4. Parent calls `build-prototype-document`, which wraps the converter
   output in an HTML document with:
   - `<script>window.__PENPOT_INTERACTIONS__ = {...};</script>`
   - `<script>window.__PENPOT_ROOT_ID__ = "uuid";</script>`
   - `<script>prototype-bridge-script</script>`
5. The iframe loads. The runtime annotates interactive shapes with
   `data-prototype-interactive=""`, so they get the pointer cursor.
6. User clicks the hotspot. The runtime's delegated `click` listener
   walks up to find the innermost `[data-id]` with a `:click`
   interaction, preventDefaults the event, and `parent.postMessage`s
   `{type: "penpot:prototype:trigger", sourceId, interaction}`.
7. Parent's `window.message` listener routes the payload through
   `read-prototype-trigger`, which uses `js-interaction->cljs` to
   rebuild a proper CLJS interaction map (keywordised, gpt/point, real
   uuids).
8. `dispatch-prototype-trigger`'s `:navigate` branch:
   - Resolves the destination frame via `find-frame-by-id-str`.
   - Calls `render-board-html` for the destination, builds the doc.
   - Sets `proto-state*`'s `:transition` to
     `{:from-id :from-doc :to-id :to-doc :animation}`.
9. React re-renders. The JSX detects `transition` is set and renders
   two iframes inside `.preview-stage` — `.transition-from` (current
   board) and `.transition-to` (destination).
10. The `(mf/with-effect [transition] …)` effect runs WAAPI on the
    parent wrappers using `slide-keyframes` / `push-from-keyframes`.
    For `:slide :out` it flips z-index inline so the source slides
    visibly off the top of the destination.
11. `Promise.all([from.finished, to.finished])` resolves. `finish!`:
    - Updates `proto-state*` — commits `:current-frame-id`, pushes
      `:from-id` onto `:nav-stack`, clears `:overlays` and
      `:transition`.
    - Sets `state*`'s `:html` to the destination doc to avoid a
      double-render flicker.
    - Emits `(rt/nav :viewer (assoc params :index dest-idx))` so the
      URL `?index=` reflects the new board.

## Key files

### Modified

#### `frontend/src/app/main/ui/viewer/html_mode.cljs`

All new logic lives here, grouped by concern:

| Section                            | Purpose                                                                                                                                                        |
|------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `->js-animation` / `->js-interaction` / `harvest-interactions` | Project Penpot's CLJS interaction data into a JSON map keyed by shape-id string. Camel-cases keys, stringifies uuids and keywords. |
| `render-board-html`                | Extended return value: now also includes `:interactions` — the harvested map for the page.                                                                     |
| `prototype-bridge-script`          | Inline JS injected into the prototype iframe. Single delegated click / mouseover / mouseout listener, after-delay scheduler, open-url local dispatch.          |
| `build-prototype-document`         | Rebuilt to inject `__PENPOT_INTERACTIONS__`, `__PENPOT_ROOT_ID__`, and the bridge script.                                                                      |
| `parse-uuid-safe`, `js-animation->cljs`, `js-interaction->cljs`, `read-prototype-trigger` | Parent-side helpers that re-hydrate the JS payload from the iframe into a CLJS interaction map ready to feed back into Penpot helpers. |
| `find-frame-by-id-str`             | Look up a top-level frame by uuid-string (uses `(:frames page)`).                                                                                              |
| `compute-overlay-rect`             | Delegates to `ctsi/calc-overlay-position` — the **same** positioning math the SVG viewer uses (`viewer.cljs` lines 141-212). Mirrors viewer behavior exactly. |
| `easing->css`                      | Maps `:linear` / `:ease` / `:ease-in` / … to the CSS timing-function string.                                                                                    |
| `slide-axis-percent` / `slide-keyframes` / `push-from-keyframes` | WAAPI keyframe builders for slide / push animations. Directions are 100% offsets along the right axis.                                |
| `html-mode-section*` (component)   | Extended with `proto-state*`, `dispatch-prototype-trigger`, two new effects (frame-prop sync, WAAPI animation), an extended postMessage listener, and JSX for the transition stage + overlays. |

#### `frontend/src/app/main/ui/viewer/html_mode.scss`

Classes used by prototype mode:

- `&[data-mode="prototype"] .preview-stage` — in prototype mode the
  stage is the static "around the board" area: neutral background,
  flex-centred, padded, scrollable when the board is bigger than the
  pane. Workspace mode keeps the original full-pane layout.
- `.board-stack` — fixed-dimension wrapper (sized inline by JSX to
  the current board's `selrect` width / height). Has a drop shadow
  to visually distinguish the board from the stage. During a
  navigate transition it temporarily expands to fit both boards.
- `.board-clip` — `overflow:hidden` layer inside the stack that
  wraps the iframe(s); clips slide / push animations so they can't
  leak onto the stage.
- `.transition-from` / `.transition-to` — absolutely-positioned
  wrappers around the source / destination iframes during a navigate
  animation. Z-indices `1` / `2` by default; the WAAPI effect flips
  them inline for `:slide :out`.
- `.overlay-backdrop` — semi-transparent backdrop behind overlays
  that opt in to `:background-overlay` or `:close-click-outside`.
  Sits inside `.board-stack` so it darkens only the board area, not
  the surrounding stage.
- `.overlay-frame` — absolutely-positioned wrapper for each open
  overlay iframe. Lives outside `.board-clip` (sibling of it) so an
  overlay positioned at the board's edge can extend past it.
- `.board-stack .preview-iframe` — selector that converts the
  default flex-filling iframe styling into absolute-positioned-inset
  for use inside the stack. Workspace mode's `.preview-iframe` (as a
  direct child of `.preview-stage`) keeps its original behaviour.

### Read-only references (intentionally not modified)

- `common/src/app/common/types/shape/interactions.cljc` — interaction
  / animation schemas, plus `calc-overlay-position` (lines 455-538)
  which computes overlay anchor coordinates for every
  `:overlay-pos-type` value. Shared verbatim between the SVG viewer
  and the HTML runtime via `compute-overlay-rect`.
- `frontend/src/app/main/ui/viewer.cljs` (lines 141-212) — overlay
  rendering / backdrop pattern in the SVG viewer; the HTML version
  is structurally the same (`viewer-overlay` ↔ `.overlay-frame` +
  `.overlay-backdrop`).
- `frontend/src/app/main/ui/viewer/interactions.cljs` (lines 319-618)
  — the WAAPI animation patterns the SVG viewer uses; the HTML
  version mirrors slide direction math, dissolve, and push.
- `frontend/src/app/main/ui/viewer/shapes.cljs` (lines 227-280) —
  event filter conventions in the SVG viewer (which event types fire
  on which DOM event). The bridge script uses the same conventions.
- `frontend/vendor/penpot-html-converter/src/converter/shapes/*.ts`
  — emit `data-id` and `data-type` on every shape. These are the
  attributes the runtime hooks on.
- `frontend/src/app/main/data/html_mode/adapter.cljs` (lines 7-21,
  144-167) — the keyword→string / kebab→camel projection rules that
  `->js-interaction` follows for consistency.

**No backend changes.** Interactions ship with the regular file /
page data.

## State management

Inside `html-mode-section*`, prototype mode keeps its own state in a
ref next to `state*`:

```clojure
(mf/use-state {:current-frame-id nil
               :nav-stack        []
               :overlays         []
               :transition       nil})
```

- **`:current-frame-id`** — the uuid-string of the board currently
  displayed. Initially synced from the `frame` prop (which the
  viewer header pagination drives via the URL `?index=`); during an
  in-flight transition the controller takes ownership so the
  animation outlives a would-be URL-driven re-render.
- **`:nav-stack`** — vector of previous `:current-frame-id` values.
  Populated on `:navigate`, popped on `:prev-screen`.
- **`:overlays`** — vector of currently open overlays. Each entry:
  `{:id, :source-id, :rect, :options, :doc}`. The `:doc` is the
  pre-rendered HTML document for the overlay's destination frame.
- **`:transition`** — `nil` when idle, otherwise
  `{:from-id, :from-doc, :to-id, :to-doc, :animation}`. The presence
  of this map causes the JSX to render the dual-iframe transition
  stage and triggers the WAAPI effect.

Two effects drive the controller:

1. **Sync effect** `(mf/with-effect [frame mode] …)` — watches the
   `frame` prop. When it changes externally (header pagination,
   first mount) and no transition is in flight, resets
   `proto-state*` to a clean state for the new board.
2. **Animation effect** `(mf/with-effect [transition] …)` — runs
   WAAPI on the `.transition-from` / `.transition-to` wrappers when
   `:transition` lands. On `finished`, commits the transition,
   updates `state*`, and emits the URL nav.

The render effect's deps were changed from
`[file page mode frame]` to `[file page mode current-frame-id]` for
prototype mode, so it re-renders when the controller-owned id
advances (rather than waiting for the URL → prop round trip).

## Animation translation table

| Penpot                | Implementation                                                                                                                  |
|-----------------------|--------------------------------------------------------------------------------------------------------------------------------|
| `:dissolve`           | `from.animate([{opacity:1},{opacity:0}])` + `to.animate([{opacity:0},{opacity:1}])`, both with `{duration, easing}`            |
| `:slide :way :in :direction X`  | `to.animate([{transform: slide-axis-percent(X)},{transform:"translate(0,0)"}])`; from stays put                                  |
| `:slide :way :out :direction X` | `from.animate([{transform:"translate(0,0)"},{transform: slide-axis-percent(X)}])`; to stays put. Z-index flipped inline so from is on top. |
| `:push :direction X`  | Both: from = `[{translate(0,0)},{slide-axis-percent(opposite X)}]`, to = `[{slide-axis-percent(X)},{translate(0,0)}]`           |

Easings map 1:1 (`:linear → "linear"`, `:ease-in → "ease-in"`, …).
`Promise.all([from.finished, to.finished])` gates the commit.

## Overlay positioning

Overlays reuse the SVG viewer's positioning math by calling
`ctsi/calc-overlay-position` from inside `compute-overlay-rect`. This
guarantees identical behavior for the seven preset positions
(`:center`, `:top-left`, `:top-right`, `:top-center`, `:bottom-left`,
`:bottom-right`, `:bottom-center`) and `:manual` placement, including
`:position-relative-to` anchoring to a specific shape.

The result is `{:x :y :width :height}` in canvas units, applied as
inline `style="left:Xpx; top:Ypx; width:Wpx; height:Hpx"` on the
overlay wrapper.

## Iframe runtime (`prototype-bridge-script`)

Lives inside `html_mode.cljs` as a single ClojureScript string
constant. The whole script is a single IIFE. Key points:

- **`IX = window.__PENPOT_INTERACTIONS__`** — the shape-id-keyed
  map. Indexed lookups, never iterated except at annotation time.
- **`chainAt(target)`** — walks from `event.target` up to body,
  collecting elements that carry `data-id`. Returns the chain
  shallowest-first.
- **`findInteractive(target, eventTypes)`** — finds the **innermost**
  element in the chain with at least one interaction matching the
  requested event types. Innermost wins on nested hotspots — the same
  semantic the SVG viewer uses.
- **`dispatch(interaction, sourceId)`** — `:open-url` runs locally
  with `window.open(url, "_blank", "noopener,noreferrer")`; every
  other action is posted to the parent.
- **`scheduleDelays()`** — on DOMContentLoaded walks `IX[ROOT_ID]`
  and registers a `setTimeout` per `:after-delay`. Per Penpot's data
  model, `:after-delay` is only meaningful on the root frame, and
  the runtime narrows accordingly.
- **`annotate()`** — single pass at load that sets
  `data-prototype-interactive=""` on every shape with at least one
  user-triggered interaction. Combined with the injected CSS rule
  `[data-prototype-interactive] { cursor: pointer; }`, this gives
  hotspots the pointer cursor without any per-event work.

The script is intentionally small (~3 KB un-minified) and dependency-
free. It never reads or writes the parent's DOM — only posts messages.

## Security

The prototype iframe carries the same sandbox attributes as the
workspace iframe: `sandbox="allow-scripts allow-same-origin"`. The
full threat model is in the namespace docstring of
`app.main.ui.viewer.html-mode` (and in
[`3.12. HTML Mode`](./html-mode.md#sandbox-and-security)). Summary:

- The combination is effectively no sandbox at all (scripts can
  unwrap the parent via `parent.document`). We accept this because
  the only scripts that run inside are ones we ship
  (`select-bridge-script` in workspace mode, `prototype-bridge-script`
  in prototype mode).
- The converter never emits `<script>` tags. The data we inject
  (`__PENPOT_INTERACTIONS__`) is a `JSON.stringify`'d structure, so
  user-authored values cannot break out of the literal.
- `:open-url` uses `window.open(url, "_blank", "noopener,noreferrer")`
  so opened pages can't tamper with the originating context.

If the converter ever starts emitting third-party scripts, or if
user-controlled URLs ever land in unescaped HTML, this calculus must
be revisited.

## Differences vs the SVG viewer

| Concern           | SVG viewer                                  | HTML Mode prototype                                  |
|-------------------|---------------------------------------------|------------------------------------------------------|
| Rendering         | SVG via `interactions/viewport`             | HTML via `cv/convertShape` inside sandboxed iframe   |
| Event source      | React handlers on shape wrappers            | Single delegated listener inside iframe; postMessage |
| Frame transitions | `dom/animate!` on viewport divs in-page     | WAAPI on dual-iframe wrappers in the parent          |
| Overlays          | DOM siblings inside `.viewer-wrapper`       | Sibling iframes inside `.preview-stage`              |
| State             | re-frame events to `dv/…` action store      | Component-local `proto-state*` (rumext ref)          |
| URL sync          | Via `dv/go-to-frame-by-index`               | Direct `rt/nav :viewer` call after animation ends    |
| Hover hotspot     | Translucent rect overlay on shapes          | `cursor: pointer` via `[data-prototype-interactive]` |

## Known limitations & possible follow-ups

- **No `:offset-effect` for slide animations.** The Penpot model
  supports a "source moves slightly with destination" effect on
  `:slide`; the HTML version currently ignores it (only the standard
  in / out behavior is implemented).
- **`:preserve-scroll` is ignored.** Each navigation re-renders the
  destination from scratch, so iframe scroll state resets. Adding
  preserve-scroll would require capturing the source iframe's scroll
  position and restoring it on the destination's iframe.
- **Brief :loading flicker after in-app navigate.** When the URL nav
  emitted at the end of a transition propagates back as a prop
  change, the render effect re-fires and resets `state*` to
  `:loading` for one tick before re-resolving to the same document.
  An LRU cache keyed by `(file-id, page-id, frame-id)` for board docs
  would close this gap.
- **`harvest-interactions` walks the whole page.** Boards not
  currently rendered still get their interactions in
  `__PENPOT_INTERACTIONS__`. Their `data-id`s aren't in the iframe
  DOM so they never fire, but the payload is larger than necessary.
  Pruning to descendants of the rendered frame would shrink it.
- **No hotspot reveal toggle.** The SVG viewer has a "show
  interactions" toggle that draws translucent rects over hotspots;
  the runtime is already wired with `data-prototype-interactive`
  attributes, so adding a parent toggle + a CSS rule would be cheap.

## Extending the implementation

### Adding a new action type

1. Add the type to `action-types` in
   `common/src/app/common/types/shape/interactions.cljc` plus a
   schema entry, and update edit-mode authoring UI.
2. Extend `->js-interaction` in `html_mode.cljs` to project the new
   fields onto the JS payload (camelCase).
3. Extend `js-interaction->cljs` to re-hydrate them.
4. Add a `case` arm in `dispatch-prototype-trigger` for the action.
5. If the new action needs runtime-only behavior (no parent round-
   trip), handle it in the `dispatch` function of
   `prototype-bridge-script` instead.

### Adding a new animation type

1. Add the type to `animation-types` in `interactions.cljc` plus its
   schema.
2. Extend `->js-animation` and `js-animation->cljs` to round-trip
   the new fields.
3. Add a `case` arm in the WAAPI animation effect (inside
   `(mf/with-effect [transition] …)`) that builds the keyframes for
   `from-el` and `to-el`. Use the existing helpers
   (`slide-axis-percent`, `slide-keyframes`, `push-from-keyframes`)
   as templates if the new animation has a similar shape.

### Adding a new event type

1. Add the type to `event-types` in `interactions.cljc`.
2. In `prototype-bridge-script`, either:
   - Add it to the array passed to `findInteractive(target, [...])`
     in an existing listener, or
   - Add a new `document.addEventListener` block with the same
     find-and-dispatch shape.
3. Make sure `annotate()`'s `userTriggered` check includes the new
   event type so hotspots get the pointer cursor.

## Testing

Manually, in a Penpot file with prototype interactions wired in edit
mode:

1. Open the file in viewer → HTML Mode → Prototype tab.
2. Smoke each interaction type: `:click :navigate`,
   `:click :open-overlay`, `:click :prev-screen`, hover-toggle,
   `:open-url`, `:after-delay`.
3. Check URL `?index=` reflects the current board, and that refresh
   lands on the same board.
4. Cross-check against the SVG viewer for the same file; semantics
   should match.

Unit tests for the pure helpers (`harvest-interactions`,
`->js-interaction`, `js-interaction->cljs`, animation projection
round-trip, `compute-overlay-rect`) belong in
`frontend/test/frontend_tests/data/` next to the existing
`html-mode-*-test` namespaces. Run them from `frontend/` inside the
devenv with `pnpm run test`.
