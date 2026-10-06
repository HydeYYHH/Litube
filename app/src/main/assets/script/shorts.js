(() => {
  'use strict';
  if (window.__shorts) return;
  window.__shorts = true;
  let active = true, key = '', video = null, readyAt = 0, attempts = 0;
  let userMuted = false, reported = false, hiddenVideo = null;
  const isShorts = () => /^\/shorts(?:\/|$)/.test(location.pathname);
  const currentVideo = () => Array.from(document.querySelectorAll('video')).find(v => {
    const r = v.getBoundingClientRect();
    return r.width > 40 && r.height > 40 && r.top < innerHeight && r.bottom > 0;
  });
  const api = () => document.querySelector('#movie_player') || document.querySelector('#player')?.getPlayer?.();
  function stop() {
    if (video && !video.paused) hiddenVideo = video;
    document.querySelectorAll('video').forEach(v => v.pause());
    try { api()?.pauseVideo?.(); } catch (_) {}
  }
  function ensure() {
    if (!isShorts()) { key = ''; video = null; hiddenVideo = null; return true; }
    const id = location.pathname;
    const next = currentVideo();
    if (id !== key || next !== video) {
      key = id; video = next; hiddenVideo = null; readyAt = 0; attempts = 0; reported = false;
    }
    if (!active || document.hidden) { stop(); return true; }
    if (!video || video.readyState < 2) return true;
    if (hiddenVideo === video) {
      hiddenVideo = null;
      try { api()?.playVideo?.(); video.play()?.catch(() => {}); } catch (_) {}
    }
    if (userMuted) return true;
    if (!readyAt) readyAt = Date.now();
    if (!reported && attempts < 3 && (video.muted || video.paused || video.volume === 0)) {
      attempts++;
      try { api()?.unMute?.(); api()?.playVideo?.(); } catch (_) {}
      video.muted = false; video.volume = 1;
      const pending = video.play();
      pending?.catch(error => { video.dataset.playError = error.name; });
    }
    if (!reported && Date.now() - readyAt >= 2500) {
      reported = true;
      if (video.muted || video.paused || video.volume === 0) {
        window.Bridge?.shortsAutoplayBlocked?.(location.href,
          video.dataset.playError || (video.muted ? 'muted_after_ready' : 'paused_after_ready'));
      } else {
        window.Bridge?.shortsAudioReady?.(location.href);
      }
    }
    return true;
  }
  window.addEventListener('tabVisibilityChanged', e => {
    active = e.detail?.active === true;
    if (!active && isShorts()) stop();
    else ensure();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && isShorts()) stop(); else ensure();
  });
  document.addEventListener('click', e => {
    if (!isShorts()) return;
    const control = e.target?.closest?.('button,[role="button"]');
    const clickedVideo = control && currentVideo();
    if (clickedVideo) {
      const wasMuted = clickedVideo.muted;
      setTimeout(() => {
        if (currentVideo() === clickedVideo && clickedVideo.muted !== wasMuted) userMuted = clickedVideo.muted;
      }, 100);
    }
  }, true);
  window.Lite?.module('shorts', ensure);
})();
