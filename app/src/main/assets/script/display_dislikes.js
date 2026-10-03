(function () {
  if (window.returnDislike?.syncPreferences) {
    window.returnDislike.syncPreferences();
    return;
  }

  const STATE_LIKED = "LIKED_STATE";
  const STATE_DISLIKED = "DISLIKED_STATE";
  const STATE_NEUTRAL = "NEUTRAL_STATE";
  const ID_RE = /^[a-zA-Z0-9_-]{11}$/;

  const isMobile = location.hostname === "m.youtube.com";
  let dislikesEnabled = false;
  let showLikes = false;
  let likesValue = 0;
  let dislikesValue = 0;
  let previousState = STATE_NEUTRAL;
  let activeVideoId = null;
  let initToken = 0;
  let lastLikeButton = null;
  let lastDislikeButton = null;
  let lastLikeHandler = null;
  let lastDislikeHandler = null;
  let activeDislikeObserver = null;
  let applyFrameId = 0;
  // Per-video vote cache (the RYD API is IP-rate-limited); a failed fetch
  // leaves the count at zero rather than showing a stale number.
  const votesCache = new Map();

  function readPrefs() {
    try {
      return JSON.parse((window.lite || window.Bridge).getPreferences() || "{}");
    } catch {
      return {};
    }
  }

  function readEnabled() {
    return !!readPrefs().enable_display_dislikes;
  }

  function readShowLikes() {
    return !!readPrefs().enable_show_likes;
  }

  function isShorts() {
    return location.pathname.startsWith("/shorts");
  }

  // Independent switches, one RYD request: either flag initializes and
  // fetches; each render path gates on its own flag.
  function anyEnabled() {
    return dislikesEnabled || showLikes;
  }

  // Desktop ytd-* branches are inherited from the RYD extension and kept
  // as a fallback for desktop-served pages, not dead code.
  function getButtons() {
    if (isShorts()) {
      const elements = document.querySelectorAll(
        isMobile ? "ytm-like-button-renderer" : "#like-button > ytd-like-button-renderer",
      );
      for (const element of elements) {
        const rect = element.getBoundingClientRect();
        if (rect.width > 0 && rect.height > 0) {
          return element;
        }
      }
      return null;
    }

    if (isMobile) {
      return (
        document.querySelector(".slim-video-action-bar-actions .segmented-buttons") ??
        document.querySelector(".slim-video-action-bar-actions")
      );
    }

    if (document.getElementById("menu-container")?.offsetParent === null) {
      return (
        document.querySelector("ytd-menu-renderer.ytd-watch-metadata > div") ??
        document.querySelector("ytd-menu-renderer.ytd-video-primary-info-renderer > div")
      );
    }

    return document.getElementById("menu-container")?.querySelector("#top-level-buttons-computed") ?? null;
  }

  function getLikeButton() {
    const buttons = getButtons();
    if (!buttons || !buttons.children || buttons.children.length === 0) return null;
    const firstChild = buttons.children[0];
    if (firstChild.tagName === "YTD-SEGMENTED-LIKE-DISLIKE-BUTTON-RENDERER") {
      return document.querySelector("#segmented-like-button") ?? firstChild.children[0] ?? null;
    }
    return buttons.querySelector("like-button-view-model") ?? firstChild;
  }

  function getDislikeButton() {
    try {
      const buttons = getButtons();
      if (!buttons || !buttons.children || buttons.children.length === 0) return null;
      const firstChild = buttons.children[0];
      if (firstChild.tagName === "YTD-SEGMENTED-LIKE-DISLIKE-BUTTON-RENDERER") {
        return document.querySelector("#segmented-dislike-button") ?? firstChild.children[1] ?? null;
      }
      return buttons.querySelector("dislike-button-view-model") ?? buttons.children[1] ?? null;
    } catch {
      return null;
    }
  }

  // Find-or-create the count text node inside a like/dislike button.
  function getCountTextContainer(button) {
    if (!button) return null;
    const existing =
      button.querySelector(".button-renderer-text") ??
      button.querySelector("#text") ??
      button.getElementsByTagName("yt-formatted-string")[0] ??
      button.querySelector("span[role='text']");
    if (existing) return existing;
    // Desktop segmented renderer hides the text; create it inside the button.
    const inner = button.querySelector("button");
    if (!inner) return null;
    const textSpan = document.createElement("span");
    textSpan.id = "text";
    textSpan.style.marginLeft = "6px";
    inner.appendChild(textSpan);
    inner.style.width = "auto";
    return textSpan;
  }

  function getDislikeTextContainer() {
    return getCountTextContainer(getDislikeButton());
  }

  function clearDislikeCount() {
    const container = getDislikeTextContainer();
    if (container && dislikeOriginalText !== null) {
      container.textContent = dislikeOriginalText;
    }
    dislikeOriginalText = null;
  }

  // Own copy on purpose: /clip pages resolve through meta tags, which the
  // shared Lite.id() does not handle (see the history-hook note below).
  function getVideoId() {
    const url = new URL(window.location.href);
    let id = null;
    if (url.pathname.startsWith("/clip")) {
      id =
        document.querySelector("meta[itemprop='videoId']")?.content ??
        document.querySelector("meta[itemprop='identifier']")?.content ??
        null;
    } else if (
      url.pathname.startsWith("/shorts/") ||
      url.pathname.startsWith("/live/") ||
      url.pathname.startsWith("/embed/")
    ) {
      id = url.pathname.split("/")[2] || null;
    } else if (url.hostname === "youtu.be" || url.hostname === "www.youtu.be") {
      id = url.pathname.split("/")[1] || null;
    } else {
      id = url.searchParams.get("v");
    }
    return id && ID_RE.test(id) ? id : null;
  }

  function isVideoReady() {
    const videoId = getVideoId();
    if (!videoId) return false;
    // Mobile watch (this app) has no ytd-watch-* hosts; the action bar is
    // enough. The ytd-watch-* probes below are RYD desktop fallbacks.
    if (isShorts() || isMobile) return !!getButtons();
    return (
      document.querySelector(`ytd-watch-grid[video-id='${videoId}']`) !== null ||
      document.querySelector(`ytd-watch-flexy[video-id='${videoId}']`) !== null ||
      document.querySelector('#player[loading="false"]:not([hidden])') !== null ||
      !!getButtons()
    );
  }

  let numberFormatter = null;
  let numberFormatterLocale = null;
  function getNumberFormatter() {
    const locale =
      document.documentElement.lang ||
      navigator.language ||
      "en";
    // Intl.NumberFormat construction is expensive and this runs on every
    // count render; rebuild only when the page locale actually changes.
    if (!numberFormatter || numberFormatterLocale !== locale) {
      numberFormatterLocale = locale;
      numberFormatter = Intl.NumberFormat(locale, {
        notation: "compact",
        compactDisplay: "short",
      });
    }
    return numberFormatter;
  }

  function roundDown(value) {
    if (value < 1000) return value;
    const int = Math.floor(Math.log10(value) - 2);
    const decimal = int + (int % 3 ? 1 : 0);
    return Math.floor(value / 10 ** decimal) * 10 ** decimal;
  }

  function formatCount(value) {
    return getNumberFormatter().format(roundDown(Math.max(0, value)));
  }

  // -- exact like count (enable_show_likes) --

  let exactFormatter = null;
  let exactFormatterLocale = null;
  function getExactFormatter() {
    const locale =
      document.documentElement.lang ||
      navigator.language ||
      "en";
    // Unlike the compact dislike count, this pref's whole point is the exact
    // number (YouTube only ever rounds); group digits in the page locale.
    if (!exactFormatter || exactFormatterLocale !== locale) {
      exactFormatterLocale = locale;
      exactFormatter = new Intl.NumberFormat(locale, { useGrouping: true });
    }
    return exactFormatter;
  }

  function getLikeTextContainer() {
    return getCountTextContainer(getLikeButton());
  }

  function applyLikeCount() {
    if (!showLikes) return;
    const container = getLikeTextContainer();
    if (!container) return;
    if (likeOriginalText === null) likeOriginalText = container.textContent || "";
    const nextText = getExactFormatter().format(Math.max(0, likesValue));
    likeTextTouched = true;
    if (container.textContent !== nextText) {
      container.textContent = nextText;
    }
  }

  let likeTextTouched = false;
  let likeOriginalText = null;
  let dislikeOriginalText = null;

  function clearLikeCount() {
    if (!likeTextTouched) return;
    likeTextTouched = false;
    const container = getLikeTextContainer();
    if (container && likeOriginalText !== null) {
      container.textContent = likeOriginalText;
    }
    likeOriginalText = null;
  }

  function applyDislikeCount() {
    if (!dislikesEnabled) return;
    const container = getDislikeTextContainer();
    if (!container) return;
    if (dislikeOriginalText === null) dislikeOriginalText = container.textContent || "";
    const nextText = formatCount(dislikesValue);
    if (container.textContent !== nextText) {
      container.textContent = nextText;
    }
    applyLikeCount();
  }

  function scheduleApplyDislikeCount() {
    if (!dislikesEnabled || applyFrameId) return;
    applyFrameId = requestAnimationFrame(() => {
      applyFrameId = 0;
      applyDislikeCount();
    });
  }

  function isPressed(button) {
    const target = button?.querySelector("button, tp-yt-paper-button#button") ?? button;
    if (!target) return false;
    const ariaPressed = target.getAttribute("aria-pressed");
    if (ariaPressed != null) return ariaPressed === "true";
    // RYD-legacy fallback, still needed: some renderers expose the
    // pressed state only through aria-label.
    const ariaLabel = target.getAttribute("aria-label");
    return ariaLabel === "true";
  }

  function readVoteState() {
    const likeButton = getLikeButton();
    const dislikeButton = getDislikeButton();
    if (!likeButton || !dislikeButton) return STATE_NEUTRAL;

    if (isMobile) {
      if (isPressed(likeButton)) return STATE_LIKED;
      if (isPressed(dislikeButton)) return STATE_DISLIKED;
      return STATE_NEUTRAL;
    }

    // RYD desktop fallback (class-based pressed state).
    if (likeButton.classList.contains("style-default-active")) return STATE_LIKED;
    if (dislikeButton.classList.contains("style-default-active")) return STATE_DISLIKED;
    return STATE_NEUTRAL;
  }

  function canOptimisticallyUpdate() {
    // #avatar-btn is the RYD desktop signal; this app is mobile-only.
    return isMobile || !!document.querySelector("#avatar-btn");
  }

  let lastVoteFire = 0;

  function applyAction(action) {
    // A touch tap fires touchstart and then click on the same control; without
    // a guard the optimistic update applies twice and self-cancels.
    const now = Date.now();
    if (now - lastVoteFire < 350) return;
    lastVoteFire = now;
    if (!anyEnabled() || !canOptimisticallyUpdate()) return;

    if (action === "like") {
      if (previousState === STATE_LIKED) {
        likesValue -= 1;
        previousState = STATE_NEUTRAL;
      } else if (previousState === STATE_DISLIKED) {
        likesValue += 1;
        dislikesValue -= 1;
        previousState = STATE_LIKED;
      } else {
        likesValue += 1;
        previousState = STATE_LIKED;
      }
    } else if (action === "dislike") {
      if (previousState === STATE_DISLIKED) {
        dislikesValue -= 1;
        previousState = STATE_NEUTRAL;
      } else if (previousState === STATE_LIKED) {
        likesValue -= 1;
        dislikesValue += 1;
        previousState = STATE_DISLIKED;
      } else {
        dislikesValue += 1;
        previousState = STATE_DISLIKED;
      }
    }

    scheduleApplyDislikeCount();
    const token = initToken;
    setTimeout(() => {
      if (!anyEnabled() || token !== initToken) return;
      previousState = readVoteState();
      scheduleApplyDislikeCount();
      applyLikeCount();
    }, 120);
    applyLikeCount();
    const videoId = getVideoId();
    if (videoId) votesCache.delete(videoId);
  }

  function bindButtons() {
    if (!anyEnabled()) return false;
    const likeButton = getLikeButton();
    const dislikeButton = getDislikeButton();
    if (!likeButton || !dislikeButton) return false;

    if (likeButton !== lastLikeButton) {
      if (lastLikeButton && lastLikeHandler) {
        lastLikeButton.removeEventListener("click", lastLikeHandler);
      }
      lastLikeHandler = () => applyAction("like");
      likeButton.addEventListener("click", lastLikeHandler);
      lastLikeButton = likeButton;
    }

    if (dislikeButton !== lastDislikeButton) {
      if (lastDislikeButton && lastDislikeHandler) {
        lastDislikeButton.removeEventListener("click", lastDislikeHandler);
      }
      lastDislikeHandler = () => applyAction("dislike");
      dislikeButton.addEventListener("click", lastDislikeHandler);
      lastDislikeButton = dislikeButton;
      observeDislikeButton(dislikeButton);
    }

    return true;
  }

  function observeDislikeButton(dislikeButton) {
    if (activeDislikeObserver) {
      activeDislikeObserver.disconnect();
      activeDislikeObserver = null;
    }

    activeDislikeObserver = new MutationObserver(() => {
      if (!dislikesEnabled) return;
      scheduleApplyDislikeCount();
    });
    activeDislikeObserver.observe(dislikeButton, {
      childList: true,
      subtree: true,
      characterData: true,
    });
  }

  function fetchVotes(videoId, token) {
    if (!anyEnabled()) return;
    const cached = votesCache.get(videoId);
    if (cached) {
      likesValue = cached.likes;
      dislikesValue = cached.dislikes;
      previousState = readVoteState();
      scheduleApplyDislikeCount();
      applyLikeCount();
      return;
    }
    // No stale counts next to the new video while the fetch is in flight.
    likesValue = 0;
    dislikesValue = 0;
    clearDislikeCount();
    clearLikeCount();
    // Bounded so a hung RYD request cannot leave stale zeros forever.
    const options = typeof AbortSignal !== "undefined" &&
      typeof AbortSignal.timeout === "function"
      ? { signal: AbortSignal.timeout(8000) }
      : {};
    fetch(
      `https://returnyoutubedislikeapi.com/votes?videoId=${encodeURIComponent(videoId)}`,
      options,
    )
      .then((response) => (response.ok ? response.json() : null))
      .then((json) => {
        if (!anyEnabled() || !json || token !== initToken || videoId !== activeVideoId) return;
        likesValue = json.likes ?? 0;
        dislikesValue = json.dislikes ?? 0;
        if (votesCache.size >= 64) {
          votesCache.delete(votesCache.keys().next().value);
        }
        votesCache.set(videoId, { likes: likesValue, dislikes: dislikesValue });
        previousState = readVoteState();
        scheduleApplyDislikeCount();
        applyLikeCount();
      })
      .catch(() => {
      });
  }

  function resetBindings() {
    if (lastLikeButton && lastLikeHandler) {
      lastLikeButton.removeEventListener("click", lastLikeHandler);
    }
    if (lastDislikeButton && lastDislikeHandler) {
      lastDislikeButton.removeEventListener("click", lastDislikeHandler);
    }
    lastLikeButton = null;
    lastDislikeButton = null;
    lastLikeHandler = null;
    lastDislikeHandler = null;
    if (activeDislikeObserver) {
      activeDislikeObserver.disconnect();
      activeDislikeObserver = null;
    }
  }

  function resetState() {
    initToken += 1;
    likesValue = 0;
    dislikesValue = 0;
    previousState = STATE_NEUTRAL;
    activeVideoId = null;
    resetBindings();
    if (applyFrameId) {
      cancelAnimationFrame(applyFrameId);
      applyFrameId = 0;
    }
    clearDislikeCount();
    clearLikeCount();
  }

  function tryInitialize(token, retriesLeft) {
    if (!anyEnabled() || token !== initToken) return;

    const videoId = getVideoId();
    if (!videoId) {
      activeVideoId = null;
      resetBindings();
      clearDislikeCount();
      return;
    }

    if (!isVideoReady() || !bindButtons()) {
      if (retriesLeft > 0) {
        setTimeout(() => tryInitialize(token, retriesLeft - 1), 120);
      } else {
        armLateRetry(token);
      }
      return;
    }

    activeVideoId = videoId;
    previousState = readVoteState();
    fetchVotes(videoId, token);
  }

  // A video whose action bar renders after the retry window would never
  // bind; re-arm once per navigation via a late timer and a one-shot
  // navigation event (both no-op when the buttons are already bound).
  let lateRetryArmed = false;
  function armLateRetry(token) {
    if (lateRetryArmed || token !== initToken) return;
    lateRetryArmed = true;
    setTimeout(() => {
      if (token !== initToken) return;
      scheduleInitialize();
    }, 5000);
    window.addEventListener(
      "yt-navigate-finish",
      () => {
        lateRetryArmed = false;
        scheduleInitialize();
      },
      { once: true, capture: true },
    );
  }

  function scheduleInitialize() {
    if (!anyEnabled()) return;
    // Same video with buttons already bound: nothing to do.
    if (getVideoId() === activeVideoId && lastLikeButton && lastLikeButton.isConnected) {
      return;
    }
    initToken += 1;
    // New video: no stale counts from the previous one.
    likesValue = 0;
    dislikesValue = 0;
    clearDislikeCount();
    clearLikeCount();
    resetBindings();
    const token = initToken;
    setTimeout(() => tryInitialize(token, 25), 0);
  }

  // One of three independent history.pushState/replaceState wrappers (nav.js
  // routes cross-tab, watch-id.js reports id flips); kept separate on
  // purpose — merging would couple the scripts' injection order.
  const originalPushState = history.pushState;
  history.pushState = function (...args) {
    const result = originalPushState.apply(this, args);
    scheduleInitialize();
    return result;
  };

  const originalReplaceState = history.replaceState;
  history.replaceState = function (...args) {
    const result = originalReplaceState.apply(this, args);
    scheduleInitialize();
    return result;
  };

  function syncPreferences() {
    const prevEnabled = dislikesEnabled;
    const prevShowLikes = showLikes;
    dislikesEnabled = readEnabled();
    showLikes = readShowLikes();
    if (dislikesEnabled === prevEnabled && showLikes === prevShowLikes) {
      if (anyEnabled()) scheduleInitialize();
      return;
    }
    // A switch turned off must clear the text it was rendering; the other
    // switch (if on) keeps the page initialized for the same video.
    if (prevShowLikes && !showLikes) clearLikeCount();
    if (prevEnabled && !dislikesEnabled) clearDislikeCount();
    if (anyEnabled()) {
      // Mid-video enable: the counts are already in memory and
      // scheduleInitialize would early-return (buttons bound), so render now.
      if (activeVideoId) {
        if (!prevEnabled && dislikesEnabled) scheduleApplyDislikeCount();
        if (!prevShowLikes && showLikes) applyLikeCount();
      }
      scheduleInitialize();
      return;
    }
    resetState();
  }

  window.addEventListener("yt-navigate-finish", scheduleInitialize, true);
  window.addEventListener("popstate", scheduleInitialize, true);
  // Only the two keys this script reads matter; every other toggle (gesture
  // prefs etc.) would otherwise trigger a sync getPreferences() round-trip.
  window.addEventListener(
    "preferencesChanged",
    (e) => {
      const key = e && e.detail && e.detail.key;
      if (key === "*" || key === "enable_display_dislikes" || key === "enable_show_likes") {
        syncPreferences();
      }
    },
    true,
  );

  window.returnDislike = { syncPreferences };

  // The `lite` bridge can lag behind document-start injection (the same
  // race player-hook.js init() retries for); without this the feature
  // would stay off until the user next flips any preference.
  let bridgeAttempts = 0;
  function bridgeReady() {
    const b = window.lite || window.Bridge;
    return !!(b && typeof b.getPreferences === "function");
  }
  function startWhenBridgeReady() {
    if (bridgeReady()) {
      syncPreferences();
      return;
    }
    if (bridgeAttempts++ < 25) {
      setTimeout(startWhenBridgeReady, 300);
      return;
    }
    console.warn("[dislikes] bridge unavailable; re-reading prefs on next navigation");
    window.addEventListener(
      "yt-navigate-finish",
      () => setTimeout(syncPreferences, 300),
      { once: true, capture: true },
    );
  }
  startWhenBridgeReady();
})();
