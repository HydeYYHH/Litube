(() => {
  'use strict';
  // core.js always injects first; the shared id parser comes from there.
  if (!window.Lite) return;
  if (window.__watchId) return;
  window.__watchId = true;

  const GAP_MS = 800;
  /** Suppress id flips within this window; the next tick re-emits if it sticks. */
  const FLAP_MS = 120;
  const BRIDGE = 'Bridge';
  let lastId = null;
  let lastAt = 0;

  function playerId() {
    try {
      const d = document.querySelector('#movie_player')?.getVideoData?.();
      if (d && Lite.isId(d.video_id)) return d.video_id;
    } catch {}
    try {
      const r = document.querySelector('#movie_player')?.getPlayerResponse?.();
      const id = r?.videoDetails?.videoId;
      if (Lite.isId(id)) return id;
    } catch {}
    return null;
  }

  function metaId() {
    try {
      const m =
        document.querySelector("meta[itemprop='videoId']")?.content ||
        document.querySelector("meta[itemprop='identifier']")?.content;
      if (Lite.isId(m)) return m;
    } catch {}
    return null;
  }

  function readId() {
    return playerId() || metaId() || Lite.id(location.href);
  }

  function emit(id) {
    if (!id || id === lastId) return;
    const now = Date.now();
    if (now - lastAt < FLAP_MS) return;
    lastId = id;
    lastAt = now;
    try {
      const b = window[BRIDGE];
      if (b && typeof b.onVideoChanged === 'function') b.onVideoChanged(id);
    } catch {}
  }

  function tick() {
    emit(readId());
  }

  // Not on the Lite scheduler: rAF never fires in a hidden tab, and
  // suspended tabs must keep reporting their video id. The pushState
  // wrappers stack with nav.js and display_dislikes.js on purpose —
  // merging would couple injection order.
  const _push = history.pushState;
  const _replace = history.replaceState;
  history.pushState = function () {
    const r = _push.apply(this, arguments);
    queueMicrotask(tick);
    return r;
  };
  history.replaceState = function () {
    const r = _replace.apply(this, arguments);
    queueMicrotask(tick);
    return r;
  };
  window.addEventListener('popstate', () => queueMicrotask(tick));
  window.addEventListener('yt-navigate-finish', tick, true);
  window.addEventListener('yt-page-data-updated', tick, true);

  setInterval(tick, GAP_MS);
  tick();
})();
