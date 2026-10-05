(() => {
  'use strict';
  if (window.__innertube) return;
  window.__innertube = true;
  const originalFetch = window.fetch.bind(window);
  const fields = ['INNERTUBE_CONTEXT', 'INNERTUBE_API_KEY', 'SESSION_INDEX', 'DATASYNC_ID',
    'DELEGATED_SESSION_ID', 'USER_SESSION_ID', 'VISITOR_DATA', 'LOGGED_IN', 'IS_PREMIUM',
    'PLAYER_JS_URL', 'WEB_PLAYER_CONTEXT_CONFIGS', 'EXPERIMENT_FLAGS', 'EXPERIMENTS_FORCED_FLAGS', 'EVENT_ID'];
  function configuration() {
    const source = window.ytcfg?.data_;
    if (!source) return null;
    const result = {};
    for (const name of fields) if (source[name] != null) result[name] = source[name];
    if (!result.PLAYER_JS_URL) {
      result.PLAYER_JS_URL = Object.values(result.WEB_PLAYER_CONTEXT_CONFIGS || {}).find(c => c?.jsUrl)?.jsUrl;
    }
    result._IS_LIVE = window.ytInitialPlayerResponse?.videoDetails?.isLiveContent === true;
    result._VIDEO_ID = window.ytInitialPlayerResponse?.videoDetails?.videoId;
    const logo = window.ytInitialData?.topbar?.desktopTopbarRenderer?.logo?.topbarLogoRenderer;
    if (logo?.iconImage?.iconType === 'YOUTUBE_PREMIUM_LOGO' || /premium/i.test(logo?.tooltip || '')) result.IS_PREMIUM = true;
    return result;
  }
  function publish(message) {
    try {
      const generation = window.Bridge?.currentDocumentGeneration?.();
      if (generation == null || !window.NativePlayerCapture) return;
      const value = JSON.stringify({ generation, session: window.Bridge?.currentExtractionSession?.(), ...message });
      if (value.length <= 2 * 1024 * 1024) window.NativePlayerCapture.postMessage(value);
    } catch {}
  }
  function captureConfiguration() { publish({ configuration: configuration() }); }
  captureConfiguration();
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', captureConfiguration, { once: true });
  window.fetch = function(input, init) {
    const url = typeof input === 'string' ? input : input?.url || '';
    const method = String(init?.method || input?.method || 'GET').toUpperCase();
    if (method !== 'POST' || !url.includes('/youtubei/v1/player')) return originalFetch(input, init);
    // Start the original immediately; observe clones without replacing browser responses.
    const generation = window.Bridge?.currentDocumentGeneration?.();
    const session = window.Bridge?.currentExtractionSession?.();
    const raw = init?.body;
    const requestText = typeof raw === 'string' ? Promise.resolve(raw)
      : raw instanceof Uint8Array || raw instanceof ArrayBuffer ? Promise.resolve(new TextDecoder().decode(raw))
      : input?.clone ? input.clone().text().catch(() => null) : Promise.resolve(null);
    captureConfiguration();
    return originalFetch(input, init).then(response => {
      requestText.then(text => {
        if (!text) return;
        if (response.status === 200) response.clone().text().then(body => {
          if (generation === window.Bridge?.currentDocumentGeneration?.()) {
            const player = JSON.parse(body);
            const config = configuration();
            if (config && player.videoDetails) {
              config._IS_LIVE = player.videoDetails.isLiveContent === true;
              config._VIDEO_ID = player.videoDetails.videoId;
            }
            publish({ session, request: text, response: body, configuration: config });
          }
        }).catch(() => {});
      }).catch(() => {});
      return response;
    });
  };
})();
