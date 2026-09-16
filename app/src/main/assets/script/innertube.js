(() => {
  'use strict';
  if (window.__ytNet) return;
  window.__ytNet = true;

  const PLAYER_PATH = '/youtubei/v1/player';
  const CACHE_TTL_MS = 90000;
  const CACHE_MAX = 32;
  const ID_PATTERN = /^[a-zA-Z0-9_-]{11}$/;
  const cache = new Map();    // url + body -> { at, status, text }
  const inflight = new Map(); // url + body -> Promise<{ status, text }>

  function dbg(msg) {
    try {
      if (window.NetTrace && typeof window.NetTrace.dbg === 'function') window.NetTrace.dbg(msg);
    } catch {}
  }

  function reportVideo(videoId) {
    const bridge = window.Bridge;
    if (!bridge) return;
    if (typeof bridge.onPlayerRequest === 'function') bridge.onPlayerRequest(videoId);
    // onPlayerRequest(null) still clears the pending match; the change and
    // prefetch notification only makes sense for a real id.
    if (videoId && typeof bridge.onVideoChanged === 'function') bridge.onVideoChanged(videoId);
  }

  function idFromText(text) {
    try {
      const id = JSON.parse(text).videoId;
      return typeof id === 'string' && ID_PATTERN.test(id) ? id : null;
    } catch {
      return null;
    }
  }

  /** Extracts the video id from any supported fetch body representation. */
  function idFromBody(raw) {
    if (raw == null) return null;
    if (typeof raw === 'string') return idFromText(raw);
    if (raw instanceof Uint8Array || raw instanceof ArrayBuffer) {
      return idFromText(new TextDecoder().decode(raw));
    }
    if (typeof raw.get === 'function') {
      const id = raw.get('videoId');
      return typeof id === 'string' && ID_PATTERN.test(id) ? id : null;
    }
    if (typeof raw === 'object') {
      const id = raw.videoId;
      return typeof id === 'string' && ID_PATTERN.test(id) ? id : null;
    }
    return null;
  }

  /** Serializes a fetch body into a stable cache key. */
  async function bodyText(input, init) {
    try {
      const raw = init && init.body != null ? init.body : null;
      if (raw == null) {
        if (typeof input !== 'string' && input) return await input.clone().text();
        return '';
      }
      if (typeof raw === 'string') return raw;
      if (raw instanceof Uint8Array || raw instanceof ArrayBuffer) {
        return new TextDecoder().decode(raw);
      }
      // Exotic bodies (Blob etc.) have no stable string form — "[object Blob]"
      // would collide distinct requests on the same URL, so bypass the cache.
      return null;
    } catch {}
    return null;
  }

  function jsonResponse(status, text) {
    return new Response(text, { status, headers: { 'Content-Type': 'application/json' } });
  }

  // 204/205/304 carry no body: rebuilding them with `new Response('')` throws
  // TypeError per the fetch spec. remove_shorts_ads.js guards the same statuses
  // one layer OUT, but this wrapper runs first, so it must pass the original
  // response through untouched (and not cache it — there is no body to keep).
  const NULL_BODY_STATUSES = [204, 205, 304];

  function evict() {
    for (const key of cache.keys()) {
      if (cache.size <= CACHE_MAX) break;
      cache.delete(key);
    }
  }

  /** Reports the player request id to native before dispatch. */
  async function playerPost(input, init) {
    const raw = init && init.body != null ? init.body : null;
    let videoId = idFromBody(raw);
    if (videoId == null && typeof input !== 'string' && input) {
      try {
        videoId = idFromText(await input.clone().text());
      } catch {}
    }
    reportVideo(videoId);
    return _fetch(input, init);
  }

  /** Serves repeated identical POSTs from the reply cache and merges concurrent ones. */
  async function cachedPost(input, init, url) {
    const text = await bodyText(input, init);
    if (text == null) return _fetch(input, init);
    const key = url + '\n' + text;
    const hit = cache.get(key);
    if (hit) {
      if (Date.now() - hit.at < CACHE_TTL_MS) {
        dbg('cache hit ' + url);
        return jsonResponse(hit.status, hit.text);
      }
      cache.delete(key);
    }
    let pending = inflight.get(key);
    if (pending) {
      dbg('cache merged ' + url);
    } else {
      pending = (async () => {
        try {
          const response = await _fetch(input, init);
          if (NULL_BODY_STATUSES.indexOf(response.status) >= 0) {
            return { status: response.status, text: '', passthrough: response };
          }
          return { status: response.status, text: await response.text() };
        } finally {
          inflight.delete(key);
        }
      })();
      inflight.set(key, pending);
    }
    const outcome = await pending;
    if (outcome.passthrough) return outcome.passthrough;
    if (outcome.status === 200) {
      cache.set(key, { at: Date.now(), status: 200, text: outcome.text });
      evict();
    }
    return jsonResponse(outcome.status, outcome.text);
  }

  // Capture point is load-bearing: WebViewFactory.kt installs scripts in an
  // order that leaves this wrapper between net-tracer.js (inner) and
  // remove_shorts_ads.js (outer); reordering the install calls silently
  // changes which layer caches or filters a given request.
  const _fetch = window.fetch.bind(window);
  window.fetch = function (input, init) {
    try {
      const request = typeof input === 'string' ? null : input;
      const url = request ? request.url || '' : input;
      if (url.indexOf('/youtubei/v1/') < 0 || url.indexOf('log_event') >= 0) {
        return _fetch(input, init);
      }
      const method = String((init && init.method) || (request && request.method) || 'GET').toUpperCase();
      if (method !== 'POST') return _fetch(input, init);
      if (url.indexOf(PLAYER_PATH) >= 0) return playerPost(input, init);
      return cachedPost(input, init, url);
    } catch {
      return _fetch(input, init);
    }
  };
})();
