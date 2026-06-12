#!/usr/bin/env bash
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#
# Copyright (c) KALEIDOS INC

# Snapshot the upstream `penpot-tools` HTML converter into
# `frontend/vendor/penpot-html-converter/`, re-apply local patches,
# and rebuild `dist/`.
#
# Usage:
#   scripts/sync-html-converter.sh /path/to/penpot-tools [git-ref]
#
# `git-ref` defaults to `HEAD`.
#
# Reproducibility model — the vendored tree is, by construction:
#
#   src/  = upstream@<source-commit> (packages/converter/src, minus tests,
#           semantics-store and shape-code)
#         + patches/penpot-local.patch   (every local modification)
#         + src/converter/shape-code.ts  (wholly local port, preserved as-is)
#   dist/ = tsc(src)
#
# Local modifications to vendored files must be made by editing the file
# AND regenerating patches/penpot-local.patch, e.g.:
#
#   work=$(mktemp -d)
#   git -C /path/to/penpot-tools archive <source-commit> \
#       packages/converter/src/converter packages/converter/src/penpot.types.ts \
#     | tar -x -C "$work" --strip-components=3
#   mkdir "$work/a" && mv "$work/converter" "$work/penpot.types.ts" "$work/a" \
#     && mkdir "$work/a-src" && mv "$work/a" "$work/a-src/src"  # layout: a/src/...
#   # (drop tests/semantics-store/shape-code from a/, copy current src/ to b/src
#   #  minus shape-code.ts, then:)
#   diff -ruN a b | sed -e 's/^\(--- [^\t]*\)\t.*/\1/' -e 's/^\(+++ [^\t]*\)\t.*/\1/' \
#     > frontend/vendor/penpot-html-converter/patches/penpot-local.patch
#
# If the patch no longer applies after an upstream bump, this script fails
# loudly — resolve the conflict by hand and regenerate the patch. Never
# edit dist/ directly.

set -euo pipefail

if [ $# -lt 1 ]; then
  echo "usage: $0 /path/to/penpot-tools [git-ref]" >&2
  exit 64
fi

UPSTREAM=$1
REF=${2:-HEAD}

REPO_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
VENDOR="$REPO_ROOT/frontend/vendor/penpot-html-converter"
LOCAL_PATCH="$VENDOR/patches/penpot-local.patch"

if [ ! -d "$UPSTREAM/packages/converter/src" ]; then
  echo "error: $UPSTREAM does not look like a penpot-tools checkout" >&2
  exit 65
fi

if [ ! -f "$LOCAL_PATCH" ]; then
  echo "error: $LOCAL_PATCH not found — refusing to continue" >&2
  echo "       (it carries every local modification; restore it from git)" >&2
  exit 66
fi

echo "→ Resolving $REF in $UPSTREAM"
COMMIT=$(git -C "$UPSTREAM" rev-parse "$REF")

# `shape-code.ts` is NOT an upstream snapshot: it is a local TypeScript
# port maintained in-tree (no oxfmt, no pageToCode, hardened tag
# validation — see the file header and VENDOR/VERSION). Preserve it
# across the wipe; upstream's copy is discarded below. When updating
# the snapshot, diff upstream's shape-code.ts manually and port over
# anything relevant.
LOCAL_SHAPE_CODE="$VENDOR/src/converter/shape-code.ts"
if [ ! -f "$LOCAL_SHAPE_CODE" ]; then
  echo "error: $LOCAL_SHAPE_CODE not found — refusing to continue" >&2
  echo "       (it is a local port, not vendored from upstream; restore it from git)" >&2
  exit 66
fi
SHAPE_CODE_BACKUP=$(mktemp)
trap 'rm -f "$SHAPE_CODE_BACKUP"' EXIT
cp "$LOCAL_SHAPE_CODE" "$SHAPE_CODE_BACKUP"

echo "→ Wiping $VENDOR/src and $VENDOR/dist"
rm -rf "$VENDOR/src" "$VENDOR/dist"
# NOTE: only create src/ itself — pre-creating src/converter would make the
# `mv` below nest the snapshot one level too deep (src/converter/converter).
mkdir -p "$VENDOR/src"

echo "→ Copying converter sources from commit $COMMIT"
git -C "$UPSTREAM" archive "$COMMIT" packages/converter/src/converter packages/converter/src/penpot.types.ts \
  | tar -x -C "$VENDOR" --strip-components=3

# After --strip-components=3, the layout is:
#   $VENDOR/converter/    (was packages/converter/src/converter/)
#   $VENDOR/penpot.types.ts
# Move them under src/ to match our package layout.
mv "$VENDOR/converter" "$VENDOR/src/converter"
mv "$VENDOR/penpot.types.ts" "$VENDOR/src/penpot.types.ts"

echo "→ Removing files we do not vendor (tests, semantics-store, upstream shape-code)"
find "$VENDOR/src" \( -name '*.test.ts' -o -name '*.integration.test.ts' \) -delete
rm -f "$VENDOR/src/converter/shape-code.ts"
rm -f "$VENDOR/src/converter/semantics-store.ts"

echo "→ Applying local modifications (patches/penpot-local.patch)"
# The patch was generated against the previous pinned commit; if upstream
# changed any of the patched regions, `patch` fails here and leaves
# `.rej` files behind — fix the conflicts by hand and REGENERATE the
# patch (see the header of this script) before committing.
if ! patch -p1 -d "$VENDOR" --no-backup-if-mismatch < "$LOCAL_PATCH"; then
  echo "error: patches/penpot-local.patch did not apply cleanly against $COMMIT" >&2
  echo "       inspect the .rej files under $VENDOR/src, resolve by hand," >&2
  echo "       and regenerate the patch before committing." >&2
  exit 67
fi

echo "→ Restoring local shape-code.ts port"
cp "$SHAPE_CODE_BACKUP" "$LOCAL_SHAPE_CODE"

echo "→ Rewriting VERSION"
SNAPSHOT_DATE=$(date -u +%Y-%m-%d)
cat > "$VENDOR/VERSION" <<EOF
source-repo: $(git -C "$UPSTREAM" remote get-url origin 2>/dev/null || echo "<local>")
source-commit: $COMMIT
source-path: packages/converter/src
snapshot-date: $SNAPSHOT_DATE

modifications (the authoritative record is patches/penpot-local.patch):
- Applied patches/penpot-local.patch on top of the upstream snapshot. It
  carries every local modification to vendored files — oxfmt removal in
  index.ts, wrap-aware flex-basis fixes in layout/layout-item.ts,
  \`isolation: isolate\` on frames, typography threading and primary-
  typography surfacing in shapes/text.ts, extended \`extractAllTokens\` in
  tokens.ts, \`typographies\` in the converter context (types.ts), null-safe
  \`escapeHtml\` (utils/html.ts), z-index surfacing (visual/base.ts), and
  extra applied-token fields (penpot.types.ts).
- Replaced upstream src/converter/shape-code.ts with a LOCAL TypeScript port
  (maintained in-tree, preserved by scripts/sync-html-converter.sh across
  snapshots). It drops oxfmt and \`pageToCode\`, validates rule tags against
  SEMANTIC_TAGS, and adds a final class→className sweep for JSX. The
  \`dist/converter/shape-code.*\` artifacts are compiled from this file.
  When updating the snapshot, diff upstream's shape-code.ts manually and
  port over anything relevant.
- Dropped src/converter/semantics-store.ts (node:fs-based, unusable in the
  browser bundle; Penpot stores semantic rules in
  app.main.data.html-mode.semantics instead).
- Dropped all *.test.ts and *.integration.test.ts files (upstream test suite stays in penpot-tools).
- Wrote a self-contained tsconfig.json targeting ES2022 ESM with declarations.
- dist/ is compiled from src/ with the repo's pinned TypeScript
  (frontend/node_modules/.bin/tsc); src type-checks cleanly and the whole
  dist/ tree is regenerated in one pass (no partial re-emits).
EOF

echo "→ Building dist/"
cd "$VENDOR"
# Prefer the repo's pinned TypeScript so dist/ is reproducible across
# machines; fall back to PATH / upstream only when it's missing.
if [ -x "$REPO_ROOT/frontend/node_modules/.bin/tsc" ]; then
  "$REPO_ROOT/frontend/node_modules/.bin/tsc"
elif command -v tsc >/dev/null 2>&1; then
  tsc
elif [ -x "$UPSTREAM/node_modules/.bin/tsc" ]; then
  "$UPSTREAM/node_modules/.bin/tsc"
else
  echo "warning: no tsc found (frontend/node_modules, PATH, or upstream) — skipping build" >&2
  echo "         run \`pnpm --filter @penpot/html-converter build\` manually after install" >&2
fi

echo "✔ Done. Updated to $COMMIT"
