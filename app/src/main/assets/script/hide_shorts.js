(function () {
    if (window.__hideShorts?.syncPreferences) {
        window.__hideShorts.syncPreferences();
        return;
    }

    const HIDDEN_ATTR = "data-hide-shorts";
    const DISPLAY_ATTR = "data-hide-shorts-display";
    const closestSelectors = [
        "ytm-reel-shelf-renderer",
        "ytm-pivot-bar-item-renderer",
        "ytm-video-with-context-renderer",
        "ytm-rich-section-renderer",
        "grid-shelf-view-model",
    ];
    const shortsSelectors = [
        "ytm-shorts-lockup-view-model",
        ".pivot-bar-item-tab.pivot-shorts",
        "a[href^=\"/shorts/\"]",
    ];
    let enabled = false;

    function readEnabled() {
        return !!Lite.prefs().enable_hide_shorts;
    }

    function hideContainer(container) {
        if (!(container instanceof HTMLElement) || container.getAttribute(HIDDEN_ATTR) === "1") {
            return;
        }
        container.setAttribute(HIDDEN_ATTR, "1");
        container.setAttribute(DISPLAY_ATTR, container.style.display || "");
        container.style.display = "none";
    }

    function hideElement(element) {
        if (!(element instanceof Element)) return;
        for (const selector of closestSelectors) {
            const container = element.closest(selector);
            if (container) {
                // grid-shelf-view-model is a generic shelf: only hide it when
                // it actually contains shorts.
                if (
                    selector === "grid-shelf-view-model"
                    && !container.querySelector("ytm-shorts-lockup-view-model, a[href^=\"/shorts/\"]")
                ) {
                    continue;
                }
                hideContainer(container);
                return;
            }
        }
    }

    function scan() {
        if (!enabled) return;
        for (const selector of shortsSelectors) {
            document.querySelectorAll(selector).forEach(hideElement);
        }
    }

    function restore() {
        document.querySelectorAll(`[${HIDDEN_ATTR}="1"]`).forEach((element) => {
            if (!(element instanceof HTMLElement)) return;
            const previous = element.getAttribute(DISPLAY_ATTR);
            if (previous) {
                element.style.display = previous;
            } else {
                element.style.removeProperty("display");
            }
            element.removeAttribute(HIDDEN_ATTR);
            element.removeAttribute(DISPLAY_ATTR);
        });
    }

    // Lazy-rendered shelves only appear in the DOM after the first scan.
    let observer = null;
    let scheduled = 0;

    function startObserver() {
        if (observer) return;
        observer = new MutationObserver(() => {
            if (!enabled || scheduled) return;
            scheduled = requestAnimationFrame(() => {
                scheduled = 0;
                if (enabled) scan();
            });
        });
        observer.observe(document.documentElement, {
            childList: true,
            subtree: true,
        });
    }

    function stopObserver() {
        observer?.disconnect();
        observer = null;
        if (scheduled) cancelAnimationFrame(scheduled);
        scheduled = 0;
    }

    function syncPreferences(event) {
        // Skip keys this feature does not read; "*" (reset) must re-sync,
        // matching display_dislikes.js.
        const key = event && event.detail && event.detail.key;
        if (key && key !== "*" && key !== "enable_hide_shorts") return;
        const next = readEnabled();
        if (enabled === next) {
            if (enabled) scan();
            return;
        }
        enabled = next;
        if (enabled) {
            startObserver();
            scan();
            return;
        }
        stopObserver();
        restore();
    }

    window.addEventListener("preferencesChanged", syncPreferences, true);
    window.__hideShorts = { syncPreferences };

    // The bridge can lag behind document-start injection; core.js polls
    // for it and re-runs on the next navigation when it never arrives.
    Lite.bridgeReady(() => {
        if (typeof window.Bridge?.getPreferences !== "function") return false;
        syncPreferences();
        return true;
    });
})();
