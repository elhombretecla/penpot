;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.html-mode.bridge-scripts
  "The two inline JS programs injected into HTML Mode's iframes.

   Both are static strings with zero runtime interpolation — they are
   kept as CLJS string literals (rather than separate .js assets) so the
   document builders in `app.main.ui.html-mode.preview-doc` can
   inline them synchronously into the iframe `srcDoc` without a fetch.

   - `select-bridge-script` powers the WORKSPACE tab: hover/selection
     overlays, measurement redlines, pan & zoom, and the
     `penpot:html-mode:select`/`:deselect`/`:select-by-id`/`:zoom`
     postMessage protocol with the parent.

   - `prototype-bridge-script` powers the PROTOTYPE tab: it reads the
     harvested interactions map (`window.__PENPOT_INTERACTIONS__`),
     wires click / hover / after-delay triggers to real DOM events,
     handles `open-url` locally, and forwards everything else to the
     parent as `penpot:prototype:trigger` messages.

   The CLJS side of these protocols (payload projection / parsing)
   lives in `app.main.data.html-mode.prototype` and is unit-tested;
   keep both in sync when the message shapes change.")

(def select-bridge-script
  ;; Inline JS executed inside the iframe. Mirrors the Inspect Mode of
  ;; the viewer: pink hover outline + purple selected outline + white
  ;; W×H label below each, plus pink distance pills (top/right/bottom/
  ;; left) between the hovered element and the selected parent — the
  ;; same colours and conventions as the SVG-based Inspect renderer in
  ;; `app.main.ui.inspect.selection-feedback` / `app.main.ui.measurements`.
  ;;
  ;; Runs in the iframe's opaque-origin sandbox; CSS variables from the
  ;; parent don't reach here so the Penpot DS accent values are hardcoded
  ;; (--color-accent-tertiary, --color-accent-quaternary).
  (str
   "(function(){"
   ;; ---- colours / sizes ----
   ;; The palette mirrors the inspector's box-model + the app accent
   ;; tokens so both speak the same language: selection = primary,
   ;; hover = quaternary, padding = success (green), spacing/distance =
   ;; warning (orange). Selection reads the live `--color-accent-primary`
   ;; from the parent (theme-aware via `allow-same-origin`), the rest are
   ;; the theme-stable token hexes.
   "var SEL=(function(){try{var v=(getComputedStyle(parent.document.body).getPropertyValue('--color-accent-primary')||'').trim();return v||'#6911d4';}catch(e){return '#6911d4';}})();"
   ;; Selection text colour: black on a light accent (e.g. dark-mode mint),
   ;; white on a dark one (e.g. light-mode purple) — so the name pill /
   ;; dimension badge stay legible whichever theme the primary comes from.
   "var SELFG=(function(c){c=(c||'').trim();if(c.charAt(0)!=='#')return '#fff';var h=c.slice(1);if(h.length===3){h=h.charAt(0)+h.charAt(0)+h.charAt(1)+h.charAt(1)+h.charAt(2)+h.charAt(2);}var r=parseInt(h.slice(0,2),16),g=parseInt(h.slice(2,4),16),b=parseInt(h.slice(4,6),16);var lum=(0.299*r+0.587*g+0.114*b)/255;return lum>0.6?'#000':'#fff';})(SEL);"
   "var HOV='#ff6fe0';"     ;; --color-accent-quaternary (hover)
   "var PAD='#2d9f8f';"     ;; --color-accent-success   (padding bands)
   "var DIST='#fe9c07';"    ;; --color-accent-warning   (spacing / distance bands)
   "var BAR=18;"            ;; name / dimension tag height in px
   ;; Penpot's UI font. The iframe is its own document so it doesn't get
   ;; the app's `@font-face`; we inline one (same-origin, served at
   ;; `/fonts`) so every overlay label renders in Work Sans.
   "var FONT='11px/1 worksans,\"Helvetica Neue\",Arial,sans-serif';"

   ;; ---- styles ----
   "var s=document.createElement('style');"
   "s.textContent='"
   "@font-face{font-family:worksans;src:url(/fonts/WorkSans-VariableFont.ttf);font-weight:100 900}"
   ".penpot-hm-overlay{position:absolute;pointer-events:none;box-sizing:border-box;z-index:2147483646;display:none}"
   ".penpot-hm-hover{outline:1.5px solid '+HOV+';outline-offset:-1px}"
   ".penpot-hm-selected{outline:1.5px solid '+SEL+';outline-offset:-1px}"
   ;; name pill — a tag at the top-left of the box.
   ".penpot-hm-name{position:absolute;left:-1.5px;top:0;transform:translateY(-100%);display:none;align-items:center;background:'+SEL+';color:'+SELFG+';font:'+FONT+';font-weight:600;height:'+BAR+'px;padding:0 6px;border-radius:3px 3px 3px 0;white-space:nowrap;max-inline-size:90vw;overflow:hidden;text-overflow:ellipsis;pointer-events:none}"
   ".penpot-hm-name.hov{background:'+HOV+';color:#fff}"
   ;; dimension badge — W / H below the box.
   ".penpot-hm-dim{position:absolute;left:50%;bottom:-5px;transform:translate(-50%,100%);display:none;align-items:center;gap:8px;background:'+SEL+';color:'+SELFG+';font:'+FONT+';height:'+BAR+'px;padding:0 7px;border-radius:3px;white-space:nowrap;pointer-events:none;box-shadow:0 1px 3px rgba(0,0,0,0.25)}"
   ".penpot-hm-dim.hov{background:'+HOV+';color:#fff}"
   ;; hatched spacing band + numeric badge (padding = green, distance = orange).
   ".penpot-hm-band{position:absolute;pointer-events:none;z-index:2147483645;display:none}"
   ".penpot-hm-pad{background-image:repeating-linear-gradient(-45deg,rgba(45,159,143,0.32) 0 5px,transparent 5px 10px);box-shadow:inset 0 0 0 1px rgba(45,159,143,0.5)}"
   ".penpot-hm-dist{background-image:repeating-linear-gradient(-45deg,rgba(254,156,7,0.34) 0 5px,transparent 5px 10px);box-shadow:inset 0 0 0 1px rgba(254,156,7,0.6)}"
   ".penpot-hm-badge{position:absolute;transform:translate(-50%,-50%);display:none;background:'+PAD+';color:#fff;font:'+FONT+';height:16px;line-height:16px;padding:0 5px;border-radius:3px;white-space:nowrap;pointer-events:none;z-index:2147483647;box-shadow:0 1px 2px rgba(0,0,0,0.28)}"
   ".penpot-hm-badge.dist{background:'+DIST+'}"
   "';"
   "document.head.appendChild(s);"

   ;; ---- outline overlays, each with a name pill + dimension badge ----
   "function mk(cls){var d=document.createElement('div');d.className='penpot-hm-overlay '+cls;document.body.appendChild(d);return d;}"
   "function child(parent,cls){var d=document.createElement('div');d.className=cls;parent.appendChild(d);return d;}"
   "var hoverEl=mk('penpot-hm-hover');"
   "var hoverName=child(hoverEl,'penpot-hm-name hov');"
   "var hoverDim=child(hoverEl,'penpot-hm-dim hov');"
   "var selEl=mk('penpot-hm-selected');"
   "var selName=child(selEl,'penpot-hm-name');"
   "var selDim=child(selEl,'penpot-hm-dim');"

   ;; ---- hatched spacing bands + numeric badges ----
   ;; Index order [top,right,bottom,left]. `pad*` show the selected
   ;; element's own padding (blue); `dist*` show the gap / insets between
   ;; the selected element and the hovered one (pink).
   "function band(cls){var d=document.createElement('div');d.className='penpot-hm-band '+cls;document.body.appendChild(d);return d;}"
   "function badge(cls){var d=document.createElement('div');d.className='penpot-hm-badge '+cls;document.body.appendChild(d);return d;}"
   "var padBands=[band('penpot-hm-pad'),band('penpot-hm-pad'),band('penpot-hm-pad'),band('penpot-hm-pad')];"
   "var padBadges=[badge(''),badge(''),badge(''),badge('')];"
   "var distBands=[band('penpot-hm-dist'),band('penpot-hm-dist'),band('penpot-hm-dist'),band('penpot-hm-dist')];"
   "var distBadges=[badge('dist'),badge('dist'),badge('dist'),badge('dist')];"

   "var currentSel=null;"
   ;; The shape the user has drilled INTO via double-click. Subsequent
   ;; single clicks pick its direct child on the cursor's ancestor
   ;; chain — the same "go down a level" semantics as the workspace canvas.
   ;; `null` means we're at the root (top-level shapes).
   "var drillParent=null;"
   ;; Tracks whether Control/Cmd is held; Ctrl+hover previews the
   ;; deepest shape ("select inside") instead of the
   ;; outer-most one. Updated by global key listeners further down.
   "var ctrlHeld=false;"

   ;; ---- helpers ----
   "function send(p){try{parent.postMessage(p,'*');}catch(e){}}"

   ;; Walk up from a DOM target collecting every ancestor element that
   ;; carries a `data-id` — i.e. every Penpot shape in the hit-test
   ;; path. The returned array is ordered SHALLOWEST → DEEPEST so the
   ;; selection helpers below can index into it intuitively
   ;; (index 0 = top-level, last index = leaf the cursor is actually
   ;; over).
   "function chainAt(t){"
   "  var chain=[], el=t;"
   "  while(el && el!==document.body){"
   "    if(el.hasAttribute && el.hasAttribute('data-id')){chain.unshift(el);}"
   "    el=el.parentElement;"
   "  }"
   "  return chain;"
   "}"

   ;; Top-of-tree shape under the cursor (used by plain hover / single
   ;; click). Returns null when the cursor isn't over any shape.
   "function topShapeAt(t){var c=chainAt(t);return c[0]||null;}"

   ;; Deepest shape under the cursor (used by Ctrl-hover / Ctrl-click).
   "function deepShapeAt(t){var c=chainAt(t);return c[c.length-1]||null;}"

   ;; Resolve the shape a plain click should select given the current
   ;; drill context:
   ;;   • drillParent is null → top of the cursor's chain (root level).
   ;;   • drillParent is on the cursor's chain → the direct child of
   ;;     drillParent on that chain (one level deeper than drillParent).
   ;;   • drillParent is NOT on the cursor's chain → the user clicked
   ;;     outside the drilled subtree. Pop back to the root and select
   ;;     the new top-level — clicking elsewhere breaks out of the
   ;;     previous frame.
   ;;
   ;; The chain index is read by reference equality on the element,
   ;; not on the data-id, so re-rendered iframes don't accidentally
   ;; preserve a stale drill context.
   "function selectAtDrill(target){"
   "  var c=chainAt(target);"
   "  if(!c.length) return {shape:null,reset:true};"
   "  if(!drillParent) return {shape:c[0],reset:false};"
   "  var idx=c.indexOf(drillParent);"
   "  if(idx===-1) return {shape:c[0],reset:true};"
   "  return {shape:c[idx+1]||c[idx],reset:false};"
   "}"

   "function fmt(n){var r=Math.round(n*100)/100;return (Math.round(r)===r)?String(Math.round(r)):r.toFixed(2);}"
   "function place(overlay,el){"
   "  var r=el.getBoundingClientRect();"
   "  overlay.style.left=(r.left+window.scrollX)+'px';"
   "  overlay.style.top=(r.top+window.scrollY)+'px';"
   "  overlay.style.width=r.width+'px';"
   "  overlay.style.height=r.height+'px';"
   "  overlay.style.display='block';"
   "}"
   "function hide(o){o.style.display='none';}"
   ;; Name pill: the shape's name (falls back to its tag).
   "function placeName(nameEl,el){"
   "  nameEl.textContent=el.getAttribute('data-name')||el.tagName.toLowerCase();"
   "  nameEl.style.display='inline-flex';"
   "}"
   ;; Dimension badge: `<width> x <height>` (rendered px).
   "function placeDim(dimEl,el){"
   "  var r=el.getBoundingClientRect();"
   "  dimEl.textContent=fmt(r.width)+' × '+fmt(r.height);"
   "  dimEl.style.display='inline-flex';"
   "}"
   ;; Position a hatched band + its centred numeric badge (page coords).
   ;; Hidden when the measured value rounds to ~0.
   "function setBand(bnd,bdg,x,y,w,h,val){"
   "  if(val<=0.5){hide(bnd);hide(bdg);return;}"
   "  bnd.style.left=(x+window.scrollX)+'px';bnd.style.top=(y+window.scrollY)+'px';"
   "  bnd.style.width=Math.max(0,w)+'px';bnd.style.height=Math.max(0,h)+'px';bnd.style.display='block';"
   "  bdg.textContent=fmt(val);"
   "  bdg.style.left=(x+w/2+window.scrollX)+'px';bdg.style.top=(y+h/2+window.scrollY)+'px';bdg.style.display='block';"
   "}"
   "function hidePadding(){for(var i=0;i<4;i++){hide(padBands[i]);hide(padBadges[i]);}}"
   "function hideDist(){for(var i=0;i<4;i++){hide(distBands[i]);hide(distBadges[i]);}}"

   ;; ---- the selected element's own padding (blue hatched bands) ----
   "function placePadding(){"
   "  hidePadding();"
   "  if(!currentSel)return;"
   "  var cs=getComputedStyle(currentSel);"
   "  var pt=parseFloat(cs.paddingTop)||0,pr=parseFloat(cs.paddingRight)||0,pb=parseFloat(cs.paddingBottom)||0,pl=parseFloat(cs.paddingLeft)||0;"
   "  var r=currentSel.getBoundingClientRect();"
   "  setBand(padBands[0],padBadges[0],r.left,r.top,r.width,pt,pt);"
   "  setBand(padBands[2],padBadges[2],r.left,r.bottom-pb,r.width,pb,pb);"
   "  setBand(padBands[3],padBadges[3],r.left,r.top+pt,pl,r.height-pt-pb,pl);"
   "  setBand(padBands[1],padBadges[1],r.right-pr,r.top+pt,pr,r.height-pt-pb,pr);"
   "}"

   ;; ---- distance between the selected element and the hovered one ----
   ;; Disjoint boxes → the gap on the separating axis (sibling spacing).
   ;; Hovered inside selected → all four insets (frame-padding redlines).
   "function placeDistances(){"
   "  hideDist();"
   "  if(!currentSel||!currentHover||currentHover===currentSel)return;"
   "  var H=currentHover.getBoundingClientRect(),S=currentSel.getBoundingClientRect();"
   "  if(H.top>=S.bottom-0.5){var x=Math.max(H.left,S.left),w=Math.min(H.right,S.right)-x;setBand(distBands[2],distBadges[2],x,S.bottom,(w>1?w:H.width),H.top-S.bottom,H.top-S.bottom);return;}"
   "  if(S.top>=H.bottom-0.5){var x2=Math.max(H.left,S.left),w2=Math.min(H.right,S.right)-x2;setBand(distBands[0],distBadges[0],x2,H.bottom,(w2>1?w2:H.width),S.top-H.bottom,S.top-H.bottom);return;}"
   "  if(H.left>=S.right-0.5){var y=Math.max(H.top,S.top),h=Math.min(H.bottom,S.bottom)-y;setBand(distBands[1],distBadges[1],S.right,y,H.left-S.right,(h>1?h:H.height),H.left-S.right);return;}"
   "  if(S.left>=H.right-0.5){var y2=Math.max(H.top,S.top),h2=Math.min(H.bottom,S.bottom)-y2;setBand(distBands[3],distBadges[3],H.right,y2,S.left-H.right,(h2>1?h2:H.height),S.left-H.right);return;}"
   "  setBand(distBands[0],distBadges[0],H.left,S.top,H.width,H.top-S.top,H.top-S.top);"
   "  setBand(distBands[2],distBadges[2],H.left,H.bottom,H.width,S.bottom-H.bottom,S.bottom-H.bottom);"
   "  setBand(distBands[3],distBadges[3],S.left,H.top,H.left-S.left,H.height,H.left-S.left);"
   "  setBand(distBands[1],distBadges[1],H.right,H.top,S.right-H.right,H.height,S.right-H.right);"
   "}"

   ;; ---- hover ----
   ;; Hover preview mirrors what the next click would select:
   ;;   • Ctrl-hover         → deepest shape (matches Ctrl+click).
   ;;   • Plain hover w/ drill → direct child of drillParent at cursor.
   ;;   • Plain hover, no drill → top-of-tree shape (matches single click).
   ;; A subsequent double-click drills one level further from there.
   ;;
   ;; `lastMoveTarget` is remembered so we can refresh the highlight
   ;; instantly when Ctrl is pressed/released or when the drill
   ;; context changes — without waiting for the user to nudge the
   ;; cursor.
   "var currentHover=null;"
   "var lastMoveTarget=null;"
   "function hoverAt(target){"
   "  if(ctrlHeld) return deepShapeAt(target);"
   "  return selectAtDrill(target).shape;"
   "}"
   "function refreshHover(target){"
   "  lastMoveTarget=target;"
   "  var el=target?hoverAt(target):null;"
   "  currentHover=el;"
   "  if(!el || el===currentSel){hide(hoverEl);hide(hoverName);hide(hoverDim);hideDist();placePadding();return;}"
   "  place(hoverEl,el);"
   "  placeName(hoverName,el);"
   ;; With a selection active, hovering another element shows the spacing
   ;; between the two (and hides the selected element's padding to keep
   ;; the redlines readable). Without a selection, just show the size.
   "  if(currentSel){hide(hoverDim);hidePadding();placeDistances();}"
   "  else{hideDist();placeDim(hoverDim,el);}"
   "}"
   "document.addEventListener('mousemove',function(e){refreshHover(e.target);},{capture:true});"
   "document.addEventListener('mouseleave',function(){hide(hoverEl);hide(hoverName);hide(hoverDim);hideDist();placePadding();currentHover=null;lastMoveTarget=null;});"

   ;; ---- click ----
   ;; Selection rules:
   ;;   • Plain single click → child of `drillParent` at cursor (or
   ;;     top-of-tree when no drill context).
   ;;   • Double click       → drill INTO whatever the first click of
   ;;     the dbl-click just selected, then re-resolve the click — so
   ;;     the next-deeper shape ends up selected and subsequent SINGLE
   ;;     clicks operate at that new level.
   ;;   • Triple/Nth click   → keep drilling, one level per click.
   ;;   • Ctrl / Cmd + click → deepest shape, drill context cleared.
   ;; `MouseEvent.detail` carries the click count (1, 2, 3, …) so we
   ;; don't need a separate dblclick listener.
   "document.addEventListener('click',function(e){"
   "  var chain=chainAt(e.target);"
   "  var el;"
   "  if(e.ctrlKey || e.metaKey){"
   "    drillParent=null;"
   "    el=chain[chain.length-1]||null;"
   "  } else if(e.detail>=2){"
   ;; The detail=1 click ran just before us and set currentSel based
   ;; on the OLD drillParent. Promote currentSel to be the new
   ;; drillParent (drilling INTO it), then re-resolve.
   "    if(currentSel) drillParent=currentSel;"
   "    var pick=selectAtDrill(e.target);"
   "    if(pick.reset) drillParent=null;"
   "    el=pick.shape;"
   "  } else {"
   "    var pick1=selectAtDrill(e.target);"
   "    if(pick1.reset) drillParent=null;"
   "    el=pick1.shape;"
   "  }"
   "  if(!el){currentSel=null;drillParent=null;hide(selEl);hide(selName);hide(selDim);hidePadding();hide(hoverEl);hide(hoverName);hide(hoverDim);hideDist();send({type:'penpot:html-mode:deselect'});return;}"
   "  e.preventDefault();e.stopPropagation();"
   "  currentSel=el;"
   "  hide(hoverEl);hide(hoverName);hide(hoverDim);hideDist();"
   "  place(selEl,el);placeName(selName,el);placeDim(selDim,el);placePadding();"
   "  send({"
   "    type:'penpot:html-mode:select',"
   "    id:el.getAttribute('data-id'),"
   "    shapeType:el.getAttribute('data-type'),"
   "    shapeName:el.getAttribute('data-name'),"
   "    style:el.getAttribute('style')||'',"
   "    tag:el.tagName.toLowerCase()"
   "  });"
   "},{capture:true});"

   ;; ---- keep overlays aligned on scroll/resize ----
   "function reposition(){"
   "  if(currentSel){place(selEl,currentSel);placeName(selName,currentSel);placeDim(selDim,currentSel);}"
   "  if(currentHover && currentHover!==currentSel){place(hoverEl,currentHover);placeName(hoverName,currentHover);}"
   "  if(currentHover && currentHover!==currentSel && currentSel){placeDistances();}else{placePadding();}"
   "}"
   "window.addEventListener('scroll',reposition,true);"
   "window.addEventListener('resize',reposition);"
   ;; ---- parent -> iframe: select-by-id ----
   ;; Triggered by the layers tree clicks. Looks up the element with
   ;; the matching `data-id` and runs the same selection flow as a
   ;; native iframe click.
   "window.addEventListener('message',function(e){"
   "  var d=e.data;"
   "  if(!d || typeof d!=='object'){return;}"
   "  if(d.type==='penpot:html-mode:select-by-id'){"
   ;; Layers-tree → iframe selection. `d.ids` may be either a single
   ;; id (legacy / single click) carried on `d.id`, or an array of
   ;; ids (multi-selection). `d.fit`: when truthy the viewport zooms
   ;; so the framed shape(s) fill the visible area with breathing
   ;; room — driven by Ctrl-click on a tree row.
   "    var ids=d.ids;"
   "    if(!ids){ ids = d.id ? [d.id] : []; }"
   "    var els=ids.map(function(id){return document.querySelector('[data-id=\"'+id+'\"]');})"
   "      .filter(function(el){return !!el;});"
   "    if(!els.length){return;}"
   ;; Track the FIRST element as the primary selection so the
   ;; overlay & sidebar reflect a single shape. (Multi-selection
   ;; overlays aren't part of HTML Mode yet — we centre on the
   ;; union but still emit one selection message.)
   "    var el=els[0];"
   "    currentSel=el;"
   ;; Selecting via the layers tree bypasses the iframe's drill
   ;; context — the tree can jump to any depth, so we reset the drill
   ;; parent so subsequent in-iframe single clicks behave predictably
   ;; (start from the root again).
   "    drillParent=null;"
   "    hide(hoverEl);hide(hoverName);hide(hoverDim);hideDist();"
   "    place(selEl,el);placeName(selName,el);placeDim(selDim,el);placePadding();"
   "    centerOnElements(els, !!d.fit, d.padding);"
   ;; Re-place after the transform changed so the outline + spacing land
   ;; on the now-shifted element.
   "    place(selEl,el);placeName(selName,el);placeDim(selDim,el);placePadding();"
   "    send({"
   "      type:'penpot:html-mode:select',"
   "      id:el.getAttribute('data-id'),"
   "      shapeType:el.getAttribute('data-type'),"
   "      shapeName:el.getAttribute('data-name'),"
   "      style:el.getAttribute('style')||'',"
   "      tag:el.tagName.toLowerCase()"
   "    });"
   "    return;"
   "  }"
   ;; Zoom commands forwarded by the parent's keydown handler. Done at
   ;; this layer (rather than relying on `Ctrl + +/-` inside the iframe)
   ;; so the host browser never sees the shortcut and never fires its
   ;; native page-zoom — preventDefault inside the iframe is unreliable
   ;; for those keys when focus is not inside the sandboxed document.
   "  if(d.type==='penpot:html-mode:zoom'){"
   "    if(d.action==='in'){zoomBy(1.1);}"
   "    else if(d.action==='out'){zoomBy(1/1.1);}"
   "    else if(d.action==='reset'){resetView();}"
   "    return;"
   "  }"
   "});"

   ;; ---- pan & zoom -------------------------------------------------
   ;; Mirrors Penpot's workspace canvas controls:
   ;;   • middle-mouse drag          → pan
   ;;   • space + left-mouse drag    → pan (Penpot convention)
   ;;   • ctrl/cmd + '+' or '='      → zoom in
   ;;   • ctrl/cmd + '-'             → zoom out
   ;;   • ctrl/cmd + '0'             → reset (1× zoom, centred)
   ;; The transform is applied to `.penpot-hm-canvas`; overlays use
   ;; `getBoundingClientRect()` which already reflects the post-transform
   ;; layout, so they stay anchored automatically.
   "var hmCanvas=null;"
   "var panX=0,panY=0,zoom=1;"
   "var spaceDown=false,dragging=false;"
   "var dStartX=0,dStartY=0,pStartX=0,pStartY=0;"
   "function ensureCanvas(){if(!hmCanvas){hmCanvas=document.querySelector('.penpot-hm-canvas');}return hmCanvas;}"
   "function applyT(){var c=ensureCanvas();if(c){c.style.transform='translate('+panX+'px,'+panY+'px) scale('+zoom+')';}}"
   "function zoomBy(f){zoom=Math.max(0.1,Math.min(8,zoom*f));applyT();reposition();}"
   "function resetView(){panX=0;panY=0;zoom=1;applyT();reposition();}"

   ;; Pan so that the union bounding rect of `rects` lands at the
   ;; centre of the viewport, without changing the zoom level. `rects`
   ;; are viewport-space rects (i.e. what `getBoundingClientRect()`
   ;; returns AFTER the current transform). One shape → single rect;
   ;; multi-selection → caller passes the union.
   "function panToCenter(unionRect){"
   "  if(!unionRect) return;"
   "  var cx=unionRect.left+unionRect.width/2;"
   "  var cy=unionRect.top+unionRect.height/2;"
   "  panX+=window.innerWidth/2-cx;"
   "  panY+=window.innerHeight/2-cy;"
   "  applyT();reposition();"
   "}"

   ;; Compute the union of N viewport-space rects. Returns null when
   ;; the input is empty so callers can no-op cleanly.
   "function unionRects(rects){"
   "  if(!rects || !rects.length) return null;"
   "  var r0=rects[0];"
   "  var l=r0.left,t=r0.top,ri=r0.right,b=r0.bottom;"
   "  for(var i=1;i<rects.length;i++){"
   "    var r=rects[i];"
   "    if(r.left<l) l=r.left;"
   "    if(r.top<t) t=r.top;"
   "    if(r.right>ri) ri=r.right;"
   "    if(r.bottom>b) b=r.bottom;"
   "  }"
   "  return {left:l,top:t,right:ri,bottom:b,width:ri-l,height:b-t};"
   "}"

   ;; Centre the iframe viewport on a set of elements, optionally
   ;; zooming so the union of their rects (plus a padding margin)
   ;; fits inside the visible area. `padding` is given in viewport
   ;; pixels — 48 leaves comfortable breathing room around the
   ;; framed shapes. The zoom is clamped to the same 0.1–8 range
   ;; as the manual controls.
   "function centerOnElements(els,fit,padding){"
   "  if(!els || !els.length) return;"
   "  padding=(padding==null?48:padding);"
   "  var rects=els.map(function(el){return el.getBoundingClientRect();});"
   "  var u=unionRects(rects);"
   "  if(!u) return;"
   "  if(fit && u.width>0 && u.height>0){"
   "    var fitW=(window.innerWidth-padding*2)/u.width;"
   "    var fitH=(window.innerHeight-padding*2)/u.height;"
   "    var f=Math.min(fitW,fitH);"
   "    zoom=Math.max(0.1,Math.min(8,zoom*f));"
   "    applyT();"
   ;; Rects above were taken at the previous zoom; re-read so the
   ;; pan-to-centre step works against the new geometry.
   "    rects=els.map(function(el){return el.getBoundingClientRect();});"
   "    u=unionRects(rects);"
   "  }"
   "  panToCenter(u);"
   "}"

   ;; Toggle helper used by the keydown/keyup handlers — pressing or
   ;; releasing Ctrl/Cmd refreshes the hover preview immediately so the
   ;; user can see the new highlight (deepest vs top-of-tree) without
   ;; having to move the mouse first.
   "function setCtrlHeld(v){"
   "  if(ctrlHeld===v) return;"
   "  ctrlHeld=v;"
   "  if(lastMoveTarget) refreshHover(lastMoveTarget);"
   "}"

   "document.addEventListener('keydown',function(e){"
   "  if(e.code==='Space'){"
   ;; Always preventDefault so autorepeat doesn't scroll the page;
   ;; only flip state on the leading keydown.
   "    e.preventDefault();"
   "    if(!spaceDown){spaceDown=true;document.body.style.cursor='grab';}"
   "    return;"
   "  }"
   "  if(e.ctrlKey || e.metaKey){"
   "    setCtrlHeld(true);"
   "    if(e.key==='+' || e.key==='='){e.preventDefault();zoomBy(1.1);}"
   "    else if(e.key==='-'){e.preventDefault();zoomBy(1/1.1);}"
   "    else if(e.key==='0'){e.preventDefault();resetView();}"
   "  }"
   "},{capture:true});"
   "document.addEventListener('keyup',function(e){"
   "  if(e.code==='Space'){spaceDown=false;if(!dragging){document.body.style.cursor='';}}"
   "  if(e.key==='Control' || e.key==='Meta' || (!e.ctrlKey && !e.metaKey)){setCtrlHeld(false);}"
   "},{capture:true});"
   ;; If the iframe loses focus the keyup may never reach us — drop the
   ;; held flag so the next hover/click doesn't behave as if Ctrl were
   ;; still pressed.
   "window.addEventListener('blur',function(){setCtrlHeld(false);});"

   ;; Suppress browser middle-click autoscroll inside the preview.
   "document.addEventListener('auxclick',function(e){if(e.button===1){e.preventDefault();}},{capture:true});"

   ;; Ctrl/Cmd + wheel → zoom. `passive:false` is mandatory: chromium
   ;; treats wheel listeners as passive by default and `preventDefault`
   ;; is a no-op there, which would let the browser run its native
   ;; pinch-zoom on the iframe document.
   "document.addEventListener('wheel',function(e){"
   "  if(!(e.ctrlKey || e.metaKey)){return;}"
   "  e.preventDefault();"
   ;; Normalise the delta to a step-per-notch factor. Trackpad pinch
   ;; events arrive in small fractional deltas; clamp the per-event
   ;; factor so a hard scroll doesn't jump several zoom levels.
   "  var step=Math.min(0.25,Math.max(-0.25,-e.deltaY/200));"
   "  zoomBy(1+step);"
   "},{passive:false,capture:true});"

   "document.addEventListener('mousedown',function(e){"
   "  var middleBtn=e.button===1;"
   "  var spacePan=e.button===0 && spaceDown;"
   "  if(!middleBtn && !spacePan){return;}"
   "  e.preventDefault();e.stopPropagation();"
   "  dragging=true;"
   "  dStartX=e.clientX;dStartY=e.clientY;"
   "  pStartX=panX;pStartY=panY;"
   "  document.body.style.cursor='grabbing';"
   "},{capture:true});"

   "document.addEventListener('mousemove',function(e){"
   "  if(!dragging){return;}"
   "  panX=pStartX+(e.clientX-dStartX);"
   "  panY=pStartY+(e.clientY-dStartY);"
   "  applyT();reposition();"
   "},{capture:true});"

   "document.addEventListener('mouseup',function(e){"
   "  if(!dragging){return;}"
   "  dragging=false;"
   "  document.body.style.cursor=spaceDown?'grab':'';"
   ;; Always swallow the trailing click for a pan gesture (middle-click
   ;; or space+left-click), even when the user didn't actually drag —
   ;; matches Penpot's workspace behaviour, where pan mode never doubles
   ;; as a shape selection.
   "  var killer=function(ev){ev.preventDefault();ev.stopPropagation();document.removeEventListener('click',killer,true);};"
   "  document.addEventListener('click',killer,true);"
   "},{capture:true});"

   ;; ---- initial transform on next frame --------------------------
   ;; The canvas is added to the DOM before this script runs, but
   ;; querying it inside ensureCanvas() is lazy. Trigger one apply so
   ;; the transform-origin attribute is already in effect on first
   ;; zoom keystroke.
   "requestAnimationFrame(applyT);"

   "})();"))

;; ---------------------------------------------------------------------------
;; Prototype mode iframe bridge
;;
;; The prototype bridge wires Penpot's `:interactions` data (harvested
;; in CLJS and injected as `window.__PENPOT_INTERACTIONS__`) into real
;; DOM behaviour. A single delegated listener handles click /
;; mouseover / mouseout; `:after-delay` fires from a `setTimeout` set
;; on load; `:open-url` runs locally with `window.open`. Every other
;; action (`:navigate`, `:open-overlay`, `:toggle-overlay`,
;; `:close-overlay`, `:prev-screen`) posts back to the parent which
;; owns the navigation stack and animation orchestration.

(def prototype-bridge-script
  ;; Inline JS executed inside the prototype iframe. Reads the
  ;; interactions map injected as `window.__PENPOT_INTERACTIONS__`
  ;; (a `{shape-id → [interaction …]}` map) and dispatches per the
  ;; event-type of each interaction. The script intentionally has no
  ;; dependency on the parent's runtime — even if the parent never
  ;; replies, click hotspots still feel responsive (pointer cursor,
  ;; default-prevented).
  (str
   "(function(){"
   "var IX = (window.__PENPOT_INTERACTIONS__) || {};"
   "var ROOT_ID = window.__PENPOT_ROOT_ID__ || null;"

   ;; ---- styles: pointer cursor on interactive shapes ----
   "var s=document.createElement('style');"
   "s.textContent='[data-prototype-interactive]{cursor:pointer}"
   "[data-prototype-interactive] *{cursor:pointer}';"
   "document.head.appendChild(s);"

   ;; ---- helpers ----
   "function send(p){try{parent.postMessage(p,'*');}catch(e){}}"

   ;; Walk from event.target up collecting [data-id] elements,
   ;; SHALLOWEST → DEEPEST. Used to find which shapes are under the
   ;; pointer (innermost wins when multiple have interactions).
   "function chainAt(t){"
   "  var chain=[], el=t;"
   "  while(el && el!==document.body){"
   "    if(el.nodeType===1 && el.hasAttribute && el.hasAttribute('data-id')){chain.unshift(el);}"
   "    el=el.parentElement;"
   "  }"
   "  return chain;"
   "}"

   ;; Returns the innermost [data-id] element in the chain that has at
   ;; least one interaction matching one of the given event types.
   ;; Returns `{el, interactions}` or null.
   "function findInteractive(target, eventTypes){"
   "  var c=chainAt(target);"
   "  for(var i=c.length-1;i>=0;i--){"
   "    var id=c[i].getAttribute('data-id');"
   "    var xs=IX[id];"
   "    if(!xs) continue;"
   "    var matched=[];"
   "    for(var j=0;j<xs.length;j++){"
   "      if(eventTypes.indexOf(xs[j].eventType)>=0) matched.push(xs[j]);"
   "    }"
   "    if(matched.length) return {el:c[i], id:id, interactions:matched};"
   "  }"
   "  return null;"
   "}"

   ;; ---- dispatch one interaction ----
   ;; Some actions (open-url) run locally; everything else is
   ;; forwarded to the parent which owns navigation + overlay state.
   "function dispatch(interaction, sourceId){"
   "  var a=interaction.actionType;"
   "  if(a==='open-url' && interaction.url){"
   "    try{window.open(interaction.url,'_blank','noopener,noreferrer');}catch(e){}"
   "    return;"
   "  }"
   "  send({type:'penpot:prototype:trigger', sourceId:sourceId, interaction:interaction});"
   "}"

   ;; ---- one-pass DOM annotation: mark interactive shapes ----
   ;; Done once at load so CSS can apply pointer cursor without
   ;; per-event work. Only marks shapes with at least one trigger
   ;; that the runtime supports (click / mouse-* / after-delay is
   ;; not user-triggered so it doesn't count for cursor purposes).
   "function annotate(){"
   "  var ids=Object.keys(IX);"
   "  for(var i=0;i<ids.length;i++){"
   "    var xs=IX[ids[i]];"
   "    var userTriggered=false;"
   "    for(var j=0;j<xs.length;j++){"
   "      var et=xs[j].eventType;"
   "      if(et==='click' || et==='mouse-press' || et==='mouse-over' || et==='mouse-enter' || et==='mouse-leave'){"
   "        userTriggered=true; break;"
   "      }"
   "    }"
   "    if(!userTriggered) continue;"
   "    var el=document.querySelector('[data-id=\"'+ids[i]+'\"]');"
   "    if(el) el.setAttribute('data-prototype-interactive','');"
   "  }"
   "}"

   ;; ---- click ----
   ;; Both :click and :mouse-press fire on a primary click. We treat
   ;; them as synonyms here — Penpot's data model preserves the
   ;; distinction so editors can author either, but at runtime in the
   ;; iframe there's no separable mousedown-vs-click semantic worth
   ;; differentiating.
   "document.addEventListener('click',function(e){"
   "  var hit=findInteractive(e.target, ['click','mouse-press']);"
   "  if(!hit) return;"
   "  e.preventDefault(); e.stopPropagation();"
   "  for(var i=0;i<hit.interactions.length;i++) dispatch(hit.interactions[i], hit.id);"
   "},{capture:true});"

   ;; ---- hover (mouseover / mouseout) ----
   ;; We use bubbling mouseover/mouseout instead of mouseenter/leave
   ;; so a single document-level listener captures everything. The
   ;; `relatedTarget` check ensures we only fire enter/leave when the
   ;; pointer actually crosses the interactive boundary.
   ;;
   ;; mouse-over fires once on enter (same as mouse-enter for these
   ;; purposes — Penpot's data model lists it separately but the
   ;; viewer treats them together). mouse-leave is its inverse and
   ;; fires on the way out.
   "function containsRelated(el, related){"
   "  if(!related) return false;"
   "  return el.contains(related);"
   "}"
   "document.addEventListener('mouseover',function(e){"
   "  var hit=findInteractive(e.target, ['mouse-enter','mouse-over']);"
   "  if(!hit) return;"
   "  if(containsRelated(hit.el, e.relatedTarget)) return;"
   "  for(var i=0;i<hit.interactions.length;i++) dispatch(hit.interactions[i], hit.id);"
   "},{capture:true});"
   "document.addEventListener('mouseout',function(e){"
   "  var hit=findInteractive(e.target, ['mouse-leave']);"
   "  if(!hit) return;"
   "  if(containsRelated(hit.el, e.relatedTarget)) return;"
   "  for(var i=0;i<hit.interactions.length;i++) dispatch(hit.interactions[i], hit.id);"
   "},{capture:true});"

   ;; ---- after-delay ----
   ;; Per Penpot's data model `:after-delay` is only meaningful on
   ;; frame shapes, and at runtime only on the CURRENTLY DISPLAYED
   ;; board. We schedule one timer per matching interaction on the
   ;; root board id; the parent unmounts the iframe (clearing the
   ;; timer naturally) whenever the board changes, so stale delays
   ;; never fire against the wrong board.
   "function scheduleDelays(){"
   "  if(!ROOT_ID) return;"
   "  var xs=IX[ROOT_ID];"
   "  if(!xs) return;"
   "  for(var i=0;i<xs.length;i++){"
   "    var ix=xs[i];"
   "    if(ix.eventType!=='after-delay') continue;"
   "    var d=Math.max(0, +ix.delay || 0);"
   "    setTimeout((function(payload){return function(){dispatch(payload, ROOT_ID);};})(ix), d);"
   "  }"
   "}"

   "if(document.readyState==='loading'){"
   "  document.addEventListener('DOMContentLoaded',function(){annotate();scheduleDelays();});"
   "} else {"
   "  annotate(); scheduleDelays();"
   "}"

   ;; ---- touch tap ripple ----
   ;; Always registered; only paints when the parent has flagged this
   ;; document with `body.penpot-touch-mode` (Touch interaction type).
   ;; The ripple lives in the iframe where the pointer events are, so no
   ;; cross-frame wiring is needed — the parent just toggles the class.
   "document.addEventListener('pointerdown',function(e){"
   "  if(!document.body.classList.contains('penpot-touch-mode')) return;"
   "  var r=document.createElement('div');"
   "  r.className='penpot-tap-ripple';"
   "  r.style.left=e.clientX+'px';"
   "  r.style.top=e.clientY+'px';"
   "  document.body.appendChild(r);"
   "  r.addEventListener('animationend',function(){ if(r.parentNode) r.parentNode.removeChild(r); });"
   "},{capture:true, passive:true});"

   "})();"))
