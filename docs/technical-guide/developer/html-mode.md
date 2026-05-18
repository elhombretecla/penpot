---
title: 3.12. HTML Mode (experimental)
desc: HTML Mode renders the current Penpot page as semantic HTML+CSS inside a sandboxed iframe, with an inspector sidebar for design-to-code workflows.
---

# HTML Mode (experimental)

HTML Mode is a workspace add-on that renders the current page as plain
HTML + CSS inside a sandboxed iframe and opens an inspector sidebar
showing the generated style declarations, the design tokens applied to
the selected layer, and any image assets referenced by it.

The feature is **off by default**. It is gated behind the `:html-mode`
feature flag.

## What it does

Clicking the **HTML Mode** button in the workspace top-right toolbar
(immediately to the left of the View Mode play button) opens the
viewer in a new window with the `html` section active. The current
page is converted to a tree of `<div>` / `<p>` / `<img>` elements with
inline `style="..."` attributes that mirror the Penpot design — flex
and grid layouts become CSS flex/grid, fills become
`background-color` / `background-image`, design tokens become
`var(--token-name)` references, and so on.

Clicking any layer inside the iframe selects it; the right sidebar
then shows three panels:

- **Style** — the inline CSS declarations of that layer, grouped into
  *Position* / *Size* / *Layout* / *Visual* / *Typography* / *Other*,
  each section copyable, plus a "copy all" for the layer.
- **Tokens** — design tokens applied to the layer (when the file
  uses Penpot's token system).
- **Assets** — image fills referenced by the layer and its
  descendants.

A small toolbar above the iframe shows the last refresh timestamp and
a "Refresh" button. The preview also auto-refreshes when the HTML
Mode window regains visibility (throttled to once every 5 seconds), so
edits made in the workspace tab become visible without manual
intervention.

## Enabling the feature

Add `enable-html-mode` to `penpotFlags` in your config:

**Local development** — edit
`frontend/resources/public/js/config.js`:

```javascript
var penpotFlags = "enable-html-mode";
```

If other flags are already set, append the value separated by spaces:

```javascript
var penpotFlags = "enable-mcp enable-access-tokens enable-html-mode";
```

**Self-hosted** — set the environment variable when starting
the Penpot frontend container:

```
PENPOT_FLAGS=enable-html-mode
```

(Combine with other flags as needed; the order is irrelevant.)

After enabling the flag, reload the workspace tab; the HTML Mode
button should appear next to View Mode. The viewer header's mode
switcher also gains a third button (alongside *Interactions* and
*Inspect*) so users already in the viewer can switch into HTML Mode.

## Architecture overview

HTML Mode is built on three pillars:

1. **An in-tree converter.**
   `frontend/vendor/penpot-html-converter` is a vendored, ISC-licensed
   snapshot of the
   [`penpot-tools`](https://github.com/juanfran/penpot-tools)
   `@penpot-tools/converter` package, registered as a pnpm workspace
   dependency named `@penpot/html-converter`. The converter is pure
   TypeScript and exposes `convertPage(page, ctx)`, which returns the
   HTML body string plus the list of fonts the page uses. The
   `oxfmt` HTML pretty-printer and the `shape-code` codegen modules
   are stripped at vendoring time because HTML Mode does not need
   them.

2. **A thin CLJS adapter.**
   `app.main.data.html-mode.adapter` translates Penpot's in-memory
   shape data (kebab-case keyword maps, `cljs.core/UUID` instances,
   keyword enum values) into the camelCase JavaScript object shape
   the converter expects. The translation is recursive and
   structural — every map key is renamed
   `kebab-case` → `camelCase`, every keyword becomes a string, every
   UUID is stringified. The converter ignores fields it doesn't
   recognise, so the adapter does not need to track upstream type
   changes.

3. **Native Penpot UI on top.**
   `app.main.ui.viewer.html-mode` is a regular rumext component that
   sits next to the existing viewer sections (`interactions`,
   `inspect`, `comments`). It owns the sandboxed iframe, the
   click-to-select postMessage bridge, the LRU cache, and the live
   refresh logic. The sidebar
   (`app.main.ui.viewer.html-mode.sidebar`) is built from the
   standard Penpot design-system primitives.

## Caching and refresh

Converter output is cached in a module-level LRU
(`app.main.data.html-mode.cache`) keyed by
`(file-id, page-id, file-revn)`. When the file's `revn` advances
(e.g. after a workspace edit is persisted), the cache key changes
and the conversion is re-run.

There are three ways the iframe gets refreshed:

- **On page mount** — initial load runs the conversion.
- **Auto-refresh on visibility** — when the HTML Mode window regains
  visibility (e.g. the user switches back from the workspace tab),
  the file bundle is re-fetched if more than 5 s have passed since
  the last refresh.
- **Manual refresh** — the **Refresh** button in the toolbar always
  fetches.

## Sandbox and security

The iframe is rendered with `sandbox="allow-scripts"` and **no**
`allow-same-origin`. That combination gives the iframe a unique
opaque origin: scripts running inside cannot read parent cookies or
`localStorage`, cannot call Penpot APIs with ambient credentials, and
cannot navigate the parent tab. Only one script runs in the iframe —
a tiny click-bridge we inject ourselves that translates element
clicks into `penpot:html-mode:select` postMessage events.

The full threat model is documented in the namespace docstring of
`app.main.ui.viewer.html-mode`.

## Current limitations

- The converter's coverage of Penpot's shape model is good but not
  perfect: complex paths, masks, and some text styling features
  fall back to approximate renderings.
- The vendored converter is a snapshot, not a live npm dep. Use
  `scripts/sync-html-converter.sh /path/to/penpot-tools <git-ref>`
  to refresh it.
- HTML Mode opens in a new window (mirroring View Mode). There is
  no embedded mode inside the workspace itself.

## Tests

Unit tests for the pure helpers live under
`frontend/test/frontend_tests/data/`:

- `html-mode-adapter-test` — CLJS → JS shape translation.
- `html-mode-cache-test` — LRU cache behaviour.
- `html-mode-style-parse-test` — CSS string parsing and section
  grouping for the sidebar.

Run them with `pnpm run test` from `frontend/` inside the devenv.
