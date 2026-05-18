# @penpot/html-converter

Vendored, in-tree snapshot of the [`penpot-tools`](https://github.com/juanfran/penpot-tools)
`@penpot-tools/converter` package, adapted for use by Penpot's HTML Mode feature
(`app.main.ui.viewer.html-mode`).

This package converts a Penpot file/page/shape tree into an HTML+CSS string.
It is consumed from ClojureScript through `app.main.data.html-mode.adapter`,
which translates Penpot's keyword-based shape maps into the JS shape that
this package expects.

## Why vendored

The upstream package is small, has no runtime dependencies after stripping
`oxfmt`, and we want a deterministic in-tree build that doesn't require
npm-registry coordination for what is effectively a Penpot subproject.

## Synchronizing with upstream

Use `scripts/sync-html-converter.sh` from the repo root. It re-copies the
relevant sources from a checkout of `penpot-tools`, re-applies the local
patches, and rebuilds `dist/`. The `VERSION` file records the source commit.

## What's stripped

- `oxfmt` (HTML pretty-printer) — Penpot always calls with `format: false`.
- `shape-code` (React/CSS code export) — not used by HTML Mode.
- `semantics-store` (tag-override rule editor) — separate feature, out of scope.
- All test files — upstream test suite lives in `penpot-tools`.
