// Port of @penpot-tools/converter shape-code.ts — turns the converter output
// (raw HTML with inline `style="…"` attributes) into framework-ready code:
//
//   - HTML or JSX (swaps `class` ↔ `className`).
//   - CSS class extraction (one class per unique declaration block, named
//     after the layer when available, deduped across the shape tree) OR
//     Tailwind v4 utility classes.
//   - Optional removal of every `data-*` attribute the converter injects to
//     drive the inspector (`data-id`, `data-type`, `data-name`, etc).
//
// No oxfmt — Penpot's vendored copy drops that dep. Output is unformatted
// but valid; the consumer can prettify if desired.
import { convertShape } from './index';

/** Whitelisted semantic tags the converter can emit as wrapper overrides. */
export const SEMANTIC_TAGS = [
    'div', 'button', 'a',
    'nav', 'header', 'footer', 'main', 'section', 'article', 'aside',
    'ul', 'ol', 'li',
    'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'p', 'label', 'span',
];

const SEMANTIC_TAG_SET = new Set(SEMANTIC_TAGS);

/**
 * Walk every shape in the page and decide which HTML tag should wrap it.
 *
 * Precedence (most specific first):
 *   1. `shape-id`      — rule's value matches `shape.id` exactly.
 *   2. `name-equals`   — `shape.name` equals the rule's value (case-insensitive).
 *   3. `name-contains` — `shape.name` contains the rule's value (case-insensitive).
 *
 * Within the same tier the earlier rule wins. Disabled rules are skipped.
 * Returns a `Map<shapeId, tag>` ready to plug into the converter's
 * `tagOverride` callback.
 */
export function resolveTagOverrides(rules, objects) {
    const out = new Map();
    if (!rules || rules.length === 0) return out;
    const enabled = rules.filter((r) => r && r.enabled && SEMANTIC_TAG_SET.has(r.tag));
    if (enabled.length === 0) return out;

    const byId       = enabled.filter((r) => r.type === 'shape-id');
    const byEq       = enabled.filter((r) => r.type === 'name-equals');
    const byContains = enabled.filter((r) => r.type === 'name-contains');

    for (const id in objects) {
        const shape = objects[id];
        if (!shape) continue;
        const idMatch = byId.find((r) => r.value === shape.id);
        if (idMatch) { out.set(shape.id, idMatch.tag); continue; }
        const name = (shape.name ?? '').toLowerCase();
        const eqMatch = byEq.find((r) => r.value.toLowerCase() === name);
        if (eqMatch) { out.set(shape.id, eqMatch.tag); continue; }
        const containsMatch = byContains.find((r) =>
            name.includes(r.value.toLowerCase()));
        if (containsMatch) out.set(shape.id, containsMatch.tag);
    }
    return out;
}

/**
 * Run the full pipeline on a single shape:
 *   convertShape → extract classes → strip data-* → swap class/className.
 *
 * @param {object} shape       Penpot shape (the selected one).
 * @param {object} allObjects  Map of shape-id → shape for the whole page.
 * @param {object} ctx         Converter context (resolveImageUrl, tokens, …).
 * @param {object} options
 * @param {'html'|'jsx'}     options.format    Tag form for class attr.
 * @param {'css'|'tailwind'} options.styling   Class extraction strategy.
 * @param {boolean}          [options.includeDataAttrs=false]
 * @param {Array}            [options.rules]   SemanticRule[] driving tag
 *                                             overrides for the wrapper of
 *                                             every shape in the page.
 *
 * @returns {Promise<{code: string, css: string, fonts: object[]}>}
 */
export async function shapeToCode(shape, allObjects, ctx, options) {
    const innerCtx = { ...ctx, format: false };
    if (options && options.rules && options.rules.length > 0) {
        const overrides = resolveTagOverrides(options.rules, allObjects);
        if (overrides.size > 0) {
            innerCtx.tagOverride = (s) => overrides.get(s.id);
        }
    }
    const { html, fonts } = await convertShape(shape, allObjects, innerCtx);

    const classAttr = options.format === 'jsx' ? 'className' : 'class';
    const names = new Map();
    for (const id in allObjects) {
        const s = allObjects[id];
        if (s && s.name) names.set(id, s.name);
    }

    const extracted = options.styling === 'tailwind'
        ? { html: stylesToTailwind(html, classAttr), css: '' }
        : stylesToCssClasses(html, classAttr, names);

    // Class extraction depends on `data-id`; only strip after.
    const stripped = options.includeDataAttrs
        ? extracted.html
        : stripDataAttrs(extracted.html);

    // JSX: swap `class=` → `className=` on any attribute the extractor
    // didn't already write (the converter and the orphan-pass write the
    // requested attr name directly, but `convertShape`'s output may include
    // raw `class="…"` strings emitted by shape-specific renderers — keep
    // them in sync.)
    const code = options.format === 'jsx'
        ? stripped.replace(/\sclass="/g, ' className="')
        : stripped;

    return { code, css: extracted.css, fonts };
}

/**
 * Strip every `data-*="…"` attribute the converter emits to drive the
 * inspector — users pasting the snippet into their own project don't want
 * the noise.
 */
const DATA_ATTR_RE = /\s+data-[a-z][a-z0-9-]*="[^"]*"/gi;
export function stripDataAttrs(html) {
    return html.replace(DATA_ATTR_RE, '');
}

/**
 * Layer-name → CSS class slug. Lowercase, collapse non-alphanum to `-`,
 * trim leading/trailing dashes. Returns `null` on empty input. Identifiers
 * starting with a digit get an `_` prefix so they parse as a selector.
 */
export function slugifyName(name) {
    if (!name) return null;
    let slug = name
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, '-')
        .replace(/^-+|-+$/g, '');
    if (!slug) return null;
    if (/^\d/.test(slug)) slug = `_${slug}`;
    return slug;
}

const STYLE_WITH_ID_RE = /(\sdata-id="([^"]+)"[^>]*?)\sstyle="([^"]*)"/g;
const STYLE_ATTR_RE    = /\sstyle="([^"]*)"/g;
const CLASS_ATTR_RE    = /class(?:Name)?="([^"]+)"/g;

/**
 * Replace every `style="…"` attribute with a deduped class reference and
 * return the collected class definitions as a CSS string.
 *
 * Two passes:
 *   1. Match `<… data-id="…" … style="…">` — Penpot shape wrappers. The
 *      class name is derived from the layer's name via `slugifyName`.
 *   2. Anything left over (text leaves like `<p>` / `<span>` the converter
 *      emits inside paragraphs, which carry no `data-id`). The fallback
 *      derives a name from the nearest preceding `class="…"` — e.g. a
 *      `<p>` inside `<div class="cancel">` becomes `class="cancel-text"`.
 *      If there's no parent class either, fall back to `s-N`.
 */
export function stylesToCssClasses(html, classAttr, names) {
    const classByDecls = new Map();
    const usedSlugs    = new Map();
    const rules        = [];
    let fallbackCounter = 0;

    function take(decoded, preferredSlug) {
        const existing = classByDecls.get(decoded);
        if (existing) return existing;
        const base = preferredSlug ?? `s-${++fallbackCounter}`;
        const occurrences = usedSlugs.get(base) ?? 0;
        usedSlugs.set(base, occurrences + 1);
        const cls = occurrences === 0 ? base : `${base}-${occurrences + 1}`;
        classByDecls.set(decoded, cls);
        rules.push(`.${cls} { ${decoded.replace(/;\s*$/, '')}; }`);
        return cls;
    }

    let next = html.replace(STYLE_WITH_ID_RE, (_m, prefix, shapeId, declarations) => {
        const decoded = decodeHtmlEntities(declarations).trim();
        if (!decoded) return prefix;
        const cls = take(decoded, slugifyName(names.get(shapeId)));
        return `${prefix} ${classAttr}="${cls}"`;
    });

    next = next.replace(STYLE_ATTR_RE, (_m, declarations, offset) => {
        const decoded = decodeHtmlEntities(declarations).trim();
        if (!decoded) return '';
        const parent = findPrecedingClass(next, offset);
        const cls = take(decoded, parent ? `${parent}-text` : null);
        return ` ${classAttr}="${cls}"`;
    });

    return { html: next, css: rules.join('\n') };
}

function findPrecedingClass(html, offset) {
    let last = null;
    CLASS_ATTR_RE.lastIndex = 0;
    let m;
    while ((m = CLASS_ATTR_RE.exec(html)) !== null) {
        if (m.index >= offset) break;
        last = m[1] ?? null;
    }
    return last;
}

/**
 * Replace every `style="…"` attribute with a Tailwind utility class string.
 * Declarations not covered by an explicit mapping fall back to v4 arbitrary
 * properties `[prop:value]` so the visual round-trip is preserved.
 */
export function stylesToTailwind(html, classAttr) {
    return html.replace(STYLE_ATTR_RE, (_m, declarations) => {
        const decoded = decodeHtmlEntities(declarations);
        const utilities = [];
        for (const decl of decoded.split(';')) {
            const idx = decl.indexOf(':');
            if (idx < 0) continue;
            const prop  = decl.slice(0, idx).trim().toLowerCase();
            const value = decl.slice(idx + 1).trim();
            if (!prop || !value) continue;
            const util = declToTailwind(prop, value);
            if (util) utilities.push(util);
        }
        if (utilities.length === 0) return '';
        return ` ${classAttr}="${utilities.join(' ')}"`;
    });
}

/** Tailwind v4 arbitrary-property form. */
function arb(prop, value) { return `[${prop}:${tw(value)}]`; }

/** Tailwind arbitrary values cannot contain whitespace; convention is `_`. */
function tw(value) {
    return String(value).replace(/\s+/g, '_').replace(/"/g, '');
}

function decodeHtmlEntities(s) {
    return s
        .replace(/&quot;/g, '"')
        .replace(/&gt;/g, '>')
        .replace(/&lt;/g, '<')
        .replace(/&amp;/g, '&');
}

/**
 * Map a single CSS declaration to a Tailwind utility. Falls back to the v4
 * arbitrary-property form `[prop:value]` for anything not handled here.
 */
export function declToTailwind(prop, v) {
    switch (prop) {
        case 'display':
            if (v === 'flex') return 'flex';
            if (v === 'grid') return 'grid';
            if (v === 'block') return 'block';
            if (v === 'inline-block') return 'inline-block';
            if (v === 'inline') return 'inline';
            if (v === 'inline-flex') return 'inline-flex';
            if (v === 'none') return 'hidden';
            return arb(prop, v);
        case 'position':
            return ['static', 'relative', 'absolute', 'fixed', 'sticky'].includes(v)
                ? v
                : arb(prop, v);
        case 'top':    return `top-[${tw(v)}]`;
        case 'right':  return `right-[${tw(v)}]`;
        case 'bottom': return `bottom-[${tw(v)}]`;
        case 'left':   return `left-[${tw(v)}]`;
        case 'z-index': return `z-[${tw(v)}]`;
        case 'width':
            if (v === '100%') return 'w-full';
            if (v === 'auto') return 'w-auto';
            return `w-[${tw(v)}]`;
        case 'height':
            if (v === '100%') return 'h-full';
            if (v === 'auto') return 'h-auto';
            return `h-[${tw(v)}]`;
        case 'min-width':  return `min-w-[${tw(v)}]`;
        case 'min-height': return `min-h-[${tw(v)}]`;
        case 'max-width':  return `max-w-[${tw(v)}]`;
        case 'max-height': return `max-h-[${tw(v)}]`;
        case 'margin':  return `m-[${tw(v)}]`;
        case 'padding': return `p-[${tw(v)}]`;
        case 'flex-direction':
            if (v === 'row') return 'flex-row';
            if (v === 'column') return 'flex-col';
            if (v === 'row-reverse') return 'flex-row-reverse';
            if (v === 'column-reverse') return 'flex-col-reverse';
            return arb(prop, v);
        case 'flex-wrap':
            if (v === 'wrap') return 'flex-wrap';
            if (v === 'nowrap') return 'flex-nowrap';
            if (v === 'wrap-reverse') return 'flex-wrap-reverse';
            return arb(prop, v);
        case 'justify-content':
            if (v === 'flex-start') return 'justify-start';
            if (v === 'flex-end') return 'justify-end';
            if (v === 'center') return 'justify-center';
            if (v === 'space-between') return 'justify-between';
            if (v === 'space-around') return 'justify-around';
            if (v === 'space-evenly') return 'justify-evenly';
            return arb(prop, v);
        case 'align-items':
            if (v === 'flex-start') return 'items-start';
            if (v === 'flex-end') return 'items-end';
            if (v === 'center') return 'items-center';
            if (v === 'stretch') return 'items-stretch';
            if (v === 'baseline') return 'items-baseline';
            return arb(prop, v);
        case 'align-self':
            if (v === 'auto') return 'self-auto';
            if (v === 'flex-start') return 'self-start';
            if (v === 'flex-end') return 'self-end';
            if (v === 'center') return 'self-center';
            if (v === 'stretch') return 'self-stretch';
            if (v === 'baseline') return 'self-baseline';
            return arb(prop, v);
        case 'justify-self':
            if (v === 'auto') return 'justify-self-auto';
            if (v === 'start') return 'justify-self-start';
            if (v === 'end') return 'justify-self-end';
            if (v === 'center') return 'justify-self-center';
            if (v === 'stretch') return 'justify-self-stretch';
            return arb(prop, v);
        case 'gap':        return `gap-[${tw(v)}]`;
        case 'row-gap':    return `gap-y-[${tw(v)}]`;
        case 'column-gap': return `gap-x-[${tw(v)}]`;
        case 'flex':
            if (v === '1' || v === '1 1 0%') return 'flex-1';
            if (v === 'auto' || v === '1 1 auto') return 'flex-auto';
            if (v === 'none' || v === '0 0 auto') return 'flex-none';
            return arb(prop, v);
        case 'flex-shrink':
            if (v === '0') return 'shrink-0';
            if (v === '1') return 'shrink';
            return arb(prop, v);
        case 'grid-template-columns':
        case 'grid-template-rows':
            return arb(prop, v);
        case 'grid-row-start':    return `[grid-row-start:${tw(v)}]`;
        case 'grid-row-end':      return `[grid-row-end:${tw(v)}]`;
        case 'grid-column-start': return `[grid-column-start:${tw(v)}]`;
        case 'grid-column-end':   return `[grid-column-end:${tw(v)}]`;
        case 'background':
        case 'background-color':
            return `bg-[${tw(v)}]`;
        case 'background-image':    return `bg-[image:${tw(v)}]`;
        case 'background-position': return `bg-[position:${tw(v)}]`;
        case 'background-repeat':
            if (v === 'no-repeat') return 'bg-no-repeat';
            if (v === 'repeat') return 'bg-repeat';
            return arb(prop, v);
        case 'background-size':
            if (v === 'cover') return 'bg-cover';
            if (v === 'contain') return 'bg-contain';
            return `bg-[length:${tw(v)}]`;
        case 'color':         return `text-[${tw(v)}]`;
        case 'border':        return arb(prop, v);
        case 'border-radius':
            if (v === '50%') return 'rounded-full';
            return `rounded-[${tw(v)}]`;
        case 'box-shadow': return `shadow-[${tw(v)}]`;
        case 'opacity':    return `opacity-[${tw(v)}]`;
        case 'overflow':
            if (v === 'hidden')  return 'overflow-hidden';
            if (v === 'auto')    return 'overflow-auto';
            if (v === 'scroll')  return 'overflow-scroll';
            if (v === 'visible') return 'overflow-visible';
            return arb(prop, v);
        case 'font-family': return `font-[${tw(v)}]`;
        case 'font-size':   return `text-[${tw(v)}]`;
        case 'font-weight':
            if (v === '100') return 'font-thin';
            if (v === '200') return 'font-extralight';
            if (v === '300') return 'font-light';
            if (v === '400') return 'font-normal';
            if (v === '500') return 'font-medium';
            if (v === '600') return 'font-semibold';
            if (v === '700') return 'font-bold';
            if (v === '800') return 'font-extrabold';
            if (v === '900') return 'font-black';
            return arb(prop, v);
        case 'font-style':
            if (v === 'italic') return 'italic';
            if (v === 'normal') return 'not-italic';
            return arb(prop, v);
        case 'line-height':     return `leading-[${tw(v)}]`;
        case 'letter-spacing':  return `tracking-[${tw(v)}]`;
        case 'text-align':
            if (['left', 'center', 'right', 'justify', 'start', 'end'].includes(v)) {
                return `text-${v}`;
            }
            return arb(prop, v);
        case 'text-transform':
            if (v === 'uppercase')  return 'uppercase';
            if (v === 'lowercase')  return 'lowercase';
            if (v === 'capitalize') return 'capitalize';
            if (v === 'none')       return 'normal-case';
            return arb(prop, v);
        case 'text-decoration': return arb(prop, v);
        case 'white-space':
            if (v === 'nowrap')    return 'whitespace-nowrap';
            if (v === 'pre')       return 'whitespace-pre';
            if (v === 'pre-line')  return 'whitespace-pre-line';
            if (v === 'pre-wrap')  return 'whitespace-pre-wrap';
            if (v === 'normal')    return 'whitespace-normal';
            return arb(prop, v);
        case 'transform':       return arb(prop, v);
        case 'filter':          return arb(prop, v);
        case 'backdrop-filter': return arb(prop, v);
        case 'mix-blend-mode':
            if (v === 'multiply')   return 'mix-blend-multiply';
            if (v === 'screen')     return 'mix-blend-screen';
            if (v === 'overlay')    return 'mix-blend-overlay';
            if (v === 'darken')     return 'mix-blend-darken';
            if (v === 'lighten')    return 'mix-blend-lighten';
            if (v === 'difference') return 'mix-blend-difference';
            if (v === 'exclusion')  return 'mix-blend-exclusion';
            if (v === 'hue')        return 'mix-blend-hue';
            if (v === 'saturation') return 'mix-blend-saturation';
            if (v === 'color')      return 'mix-blend-color';
            if (v === 'luminosity') return 'mix-blend-luminosity';
            return arb(prop, v);
        default:
            return arb(prop, v);
    }
}
