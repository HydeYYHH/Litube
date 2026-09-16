(() => {
  'use strict';
  if (window.__wid) return;
  window.__wid = true;

  const GAP_MS = 800;
  /** Suppress id flips within this window; the next tick re-emits if it sticks. */
  const FLAP_MS = 120;
  const BRIDGE = 'Bridge';
  let lastId = null;
  let lastAt = 0;

  // Mirrors player-hook.js watchIdOf() (display_dislikes.js getVideoId() is
  // a partial copy too); kept separate on purpose — merging would couple
  // the scripts' injection order.
  function videoId(url) {
    try {
      const u = new URL(url || location.href, location.href);
      const q = u.searchParams.get('v');
      if (q && /^[a-zA-Z0-9_-]{11}$/.test(q)) return q;
      const segs = u.pathname.split('/').filter(Boolean);
      if (u.hostname.includes('youtu.be') && segs[0] && /^[a-zA-Z0-9_-]{11}$/.test(segs[0])) {
        return segs[0];
      }
      const si = segs.indexOf('shorts');
      if (si >= 0 && segs[si + 1] && /^[a-zA-Z0-9_-]{11}$/.test(segs[si + 1])) {
        return segs[si + 1];
      }
      const ei = segs.indexOf('embed');
      if (ei >= 0 && segs[ei + 1] && /^[a-zA-Z0-9_-]{11}$/.test(segs[ei + 1])) {
        return segs[ei + 1];
      }
      const li = segs.indexOf('live');
      if (li >= 0 && segs[li + 1] && /^[a-zA-Z0-9_-]{11}$/.test(segs[li + 1])) {
        return segs[li + 1];
      }
    } catch {}
    return null;
  }

  function playerId() {
    try {
      const d = document.querySelector('#movie_player')?.getVideoData?.();
      if (d && d.video_id && /^[a-zA-Z0-9_-]{11}$/.test(d.video_id)) return d.video_id;
    } catch {}
    try {
      const r = document.querySelector('#movie_player')?.getPlayerResponse?.();
      const id = r?.videoDetails?.videoId;
      if (id && /^[a-zA-Z0-9_-]{11}$/.test(id)) return id;
    } catch {}
    return null;
  }

  function metaId() {
    try {
      const m =
        document.querySelector("meta[itemprop='videoId']")?.content ||
        document.querySelector("meta[itemprop='identifier']")?.content;
      if (m && /^[a-zA-Z0-9_-]{11}$/.test(m)) return m;
    } catch {}
    return null;
  }

  function readId() {
    return playerId() || metaId() || videoId(location.href);
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

  // One of four independent history.pushState/replaceState wrappers (nav.js
  // routes cross-tab, player-hook.js syncs the player, display_dislikes.js
  // rebinds vote buttons); kept separate on purpose.
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
