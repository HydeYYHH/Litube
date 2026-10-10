(() => {
  'use strict';
  if (window.__netTracer) return;
  window.__netTracer = true;
  const generation = window.Bridge?.currentDocumentGeneration?.();

  const SLOW = 500;
  const MAX_URL = 512;

  function clip(s) {
    s = String(s || '');
    return s.length > MAX_URL ? s.slice(0, MAX_URL) + '…' : s;
  }

  function noise(url, src) {
    const u = String(url || '').toLowerCase();
    const s = String(src || '');
    if (!u) return true;
    if (u.includes('doubleclick') || u.includes('googleads') || u.includes('/pagead/') ||
        u.includes('pcs/activeview') || u.includes('pagead/')) return true;
    if (u.includes('play.google.com/log')) return true;
    if (u.includes('/s/search/audio/') || u.includes('generate_204')) return true;
    if (u.includes('/api/stats/') || u.includes('youtubei/v1/log_event')) return true;
    if (u.includes('googlevideo.com') || u.includes('videoplayback')) return true;
    if (u.includes('accounts.google.com') || u.includes('/js/th/')) return true;
    if (u.includes('/s/_/ytmweb/_/js/')) return true;
    if (s.indexOf('res:') === 0) return true;
    return false;
  }

  function emit(record) {
    if (noise(record.url, record.src)) return;
    try {
      if (window.NetTrace && typeof window.NetTrace.onRequestLogged === 'function') {
        window.NetTrace.onRequestLogged(JSON.stringify({ ...record, generation }));
      }
    } catch {}
  }

  function now() {
    return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
  }

  const _fetch = window.fetch;
  if (typeof _fetch === 'function') {
    window.fetch = function (input, init) {
      const t0 = now();
      let url = '';
      let method = 'GET';
      try {
        if (typeof input === 'string') url = input;
        else if (input && input.url) url = input.url;
        method = String((init && init.method) || (input && input.method) || 'GET').toUpperCase();
      } catch {}

      return _fetch.apply(this, arguments).then((res) => {
        emit({
          src: 'fetch',
          method,
          url: clip(url),
          status: res ? res.status : 0,
          ms: Math.round(now() - t0),
        });
        return res;
      }, (err) => {
        emit({
          src: 'fetch',
          method,
          url: clip(url),
          status: 0,
          ms: Math.round(now() - t0),
          err: String(err && err.message || err),
        });
        throw err;
      });
    };
  }

  const XO = XMLHttpRequest.prototype;
  const _open = XO.open;
  const _send = XO.send;

  XO.open = function (method, url) {
    this.__netTracer = {
      method: String(method || 'GET').toUpperCase(),
      url: clip(url),
      t0: 0,
    };
    return _open.apply(this, arguments);
  };

  XO.send = function () {
    const meta = this.__netTracer || { method: 'GET', url: '', t0: 0 };
    meta.t0 = now();
    let once = false;
    const done = () => {
      if (once) return;
      once = true;
      emit({
        src: 'xhr',
        method: meta.method,
        url: meta.url,
        status: this.status || 0,
        ms: Math.round(now() - meta.t0),
      });
    };
    this.addEventListener('loadend', done);
    this.addEventListener('error', done);
    this.addEventListener('abort', done);
    return _send.apply(this, arguments);
  };
})();
