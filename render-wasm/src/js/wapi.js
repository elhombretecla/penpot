addToLibrary({
  wapi_notifyTilesRenderComplete: function wapi_notifyTilesRenderComplete() {
    // The corresponding listener lives on `document` (main thread), so in a
    // worker context we simply skip the dispatch instead of crashing.
    if (typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope) {
      return;
    }
    document.dispatchEvent(new CustomEvent('penpot:wasm:tiles-complete'));
  },

  wapi_notifyImagesEvicted: function wapi_notifyImagesEvicted(ptr, len) {
    if (typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope) {
      return;
    }
    // Payload: N × 16 bytes, each a UUID as 4 little-endian u32 words.
    // Copy them out of the heap NOW (the Rust buffer dies after this call).
    const words = new Uint32Array(HEAPU8.buffer, ptr, len >> 2);
    const ids = [];
    for (let i = 0; i + 3 < words.length; i += 4) {
      ids.push([words[i], words[i + 1], words[i + 2], words[i + 3]]);
    }
    document.dispatchEvent(new CustomEvent('penpot:wasm:images-evicted', { detail: { ids: ids } }));
  }
});
