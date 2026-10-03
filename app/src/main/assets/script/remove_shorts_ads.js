(function (global) {
  // Both injection paths evaluate this file; the guard keeps the fetch
  // wrapper from stacking.
  if (global.__shortsAdsFetchPatched) return;

  const REEL_API_RE = /\/youtubei\/v1\/reel\/reel_watch_sequence/i;

  let patchedFetch = null;

  function isObject(value) {
    return value !== null && typeof value === 'object';
  }

  function safeJsonParse(text) {
    try {
      return JSON.parse(text);
    } catch {
      return null;
    }
  }

  function getUrl(input) {
    if (typeof input === 'string') return input;
    if (input && typeof input.url === 'string') return input.url;
    return '';
  }

  function hasAdField(node) {
    if (!isObject(node)) return false;
    if (Array.isArray(node)) return node.some(hasAdField);

    return !!(
      node.adClientParams ||
      node.adPlacements ||
      node.adSlots ||
      node.adBreakHeartbeatParams ||
      node.adSlotRenderer
    );
  }

  function isAdEntry(entry) {
    if (!isObject(entry)) return false;
    if (hasAdField(entry)) return true;

    const endpoint = entry.command || entry.navigationEndpoint || entry.reelWatchEndpoint;
    if (hasAdField(endpoint)) return true;
    if (hasAdField(entry.reelWatchEndpoint)) return true;
    if (hasAdField(entry.command?.reelWatchEndpoint)) return true;
    if (hasAdField(entry.ad)) return true;
    return false;
  }

  function filterShortsJson(data) {
    if (!isObject(data)) return data;

    if (Array.isArray(data.entries)) {
      data.entries = data.entries.filter((entry) => !isAdEntry(entry));
    }

    const player = data.playerResponse;
    if (isObject(player)) {
      delete player.adBreakHeartbeatParams;
      delete player.adSlots;
      delete player.adPlacements;
    }

    return data;
  }

  function shouldFilterUrl(url) {
    return !!url && REEL_API_RE.test(url);
  }

  function patchFetch() {
    if (typeof global.fetch !== 'function' || patchedFetch) return false;

    patchedFetch = global.fetch.bind(global);
    global.fetch = async function (...args) {
      const response = await patchedFetch(...args);
      const url = getUrl(args[0]);
      if (!shouldFilterUrl(url)) return response;

      // 204/205/304 carry no body; rebuilding them with one throws.
      if (response.status === 204 || response.status === 205 || response.status === 304) {
        return response;
      }

      const text = await response.clone().text();
      const json = safeJsonParse(text);
      if (!json) return response;

      const next = filterShortsJson(json);
      const body = JSON.stringify(next);
      if (body === text) return response;

      // The original Content-Length no longer matches the filtered body.
      const headers = new Headers(response.headers);
      headers.delete('content-length');
      return new Response(body, {
        status: response.status,
        statusText: response.statusText,
        headers,
      });
    };
    return true;
  }

  if (global.document) {
    // Flag only after a successful patch so a failed attempt can be
    // retried by a later injection.
    if (patchFetch()) global.__shortsAdsFetchPatched = true;
  }
})(typeof window !== 'undefined' ? window : globalThis);
