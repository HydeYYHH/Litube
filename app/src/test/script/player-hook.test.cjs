const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function page(href = 'https://m.youtube.com/watch?v=aaaaaaaaaaa', withNavigation = false) {
  const events = new Map();
  const documentListeners = [];
  const windowListeners = [];
  const bridgeEvents = [];
  const timers = new Map();
  let nextTimer = 1;
  let layout = null;
  const classes = new Set();
  const styles = new Map();
  const root = {
    lang: 'en',
    classList: {
      contains: (name) => classes.has(name),
      add: (name) => classes.add(name),
      remove: (name) => classes.delete(name),
      toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); },
    },
    appendChild(node) { styles.set(node.id, node); },
  };
  const player = {
    style: {}, classList: { contains: () => false },
    querySelector: () => null,
    getBoundingClientRect: () => ({ top: 32, height: 180 }),
  };
  const context = {
    URL, console: { warn() {} }, Element: class {},
    location: { href, origin: 'https://m.youtube.com', search: new URL(href).search },
    navigator: { language: 'en' },
    history: { pushState() {}, replaceState() {} },
    document: {
      readyState: 'loading', documentElement: root,
      visibilityState: 'visible',
      addEventListener(name, handler, capture) { events.set(name, handler); documentListeners.push({ name, handler, capture }); },
      querySelector: (selector) => selector === '#movie_player' ? player : null,
      querySelectorAll: () => [],
      getElementById: (id) => styles.get(id) || null,
      createElement: () => ({}),
    },
    lite: { play() { layout = null; }, setPlayerLayout(top, height) { layout = [top, height]; }, setPageHasPlaylist() {},
      prepare() { bridgeEvents.push('prepare'); }, openTab() { bridgeEvents.push('open'); } },
    setTimeout(fn) { const id = nextTimer++; timers.set(id, fn); return id; },
    clearTimeout(id) { timers.delete(id); }, setInterval() { return 1; },
    requestAnimationFrame: (fn) => fn(),
    addEventListener(name, handler, capture) { windowListeners.push({ name, handler, capture }); },
    MutationObserver: class { observe() {} },
  };
  Object.defineProperty(context.location, "pathname", { get: () => new URL(context.location.href).pathname });
  context.window = context;
  context.Bridge = context.lite;
  context.top = context;
  context.self = context;
  const assets = path.join(__dirname, '../../main/assets/script');
  vm.runInNewContext([
    fs.readFileSync(path.join(assets, 'core.js'), 'utf8'),
    ...(withNavigation ? [fs.readFileSync(path.join(assets, 'nav.js'), 'utf8')] : []),
    fs.readFileSync(path.join(assets, 'player-hook.js'), 'utf8'),
  ].join('\n'), context);
  events.get('DOMContentLoaded')();
  function click(href) {
    const anchor = { href, getAttribute: () => href };
    const event = { button: 0, defaultPrevented: false, stopped: false,
      target: { closest: selector => selector === 'a' || selector === 'a[href]' ? anchor : null },
      preventDefault() { this.defaultPrevented = true; }, stopImmediatePropagation() { this.stopped = true; },
    };
    for (const listener of [...windowListeners.filter(l => l.capture), ...documentListeners.filter(l => l.capture),
      ...documentListeners.filter(l => !l.capture)]) {
      if (listener.name !== 'click') continue;
      listener.handler(event);
      if (event.stopped) break;
    }
  }
  return { context, player, classes, styles, layout: () => layout, click, bridgeEvents,
    flushTimers() { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); } };
}

function historyPlayer() {
  const fixture = page();
  const events = new Map(), videoEvents = new Map(), calls = [];
  let state = -1;
  const video = { muted: false, pause: () => calls.push('element-pause'),
    addEventListener: (name, fn) => videoEvents.set(name, fn),
    removeEventListener: name => videoEvents.delete(name) };
  Object.assign(fixture.player, {
    querySelector: () => video,
    addEventListener: (name, fn) => events.set(name, fn),
    removeEventListener: name => events.delete(name),
    mute: () => calls.push('mute'), unMute: () => calls.push('unmute'),
    getPlayerState: () => state,
    getCurrentTime: () => 23,
    getVideoData: () => ({ video_id: fixture.context.Lite.id() }),
    setPlaybackQualityRange: (a, b) => calls.push(['quality', a, b]),
    seekTo: time => calls.push(['seek', time]),
    playVideo: () => calls.push('play'),
    pauseVideo: () => { calls.push('pause'); state = 2; },
  });
  fixture.context.__syncPlayerCompact();
  return { ...fixture, video, calls, events, videoEvents,
    buffering() { state = 3; },
    playing() { state = 1; events.get('onStateChange')?.({ data: 1 }); calls.push('youtube-history'); } };
}

test('muted web player can emit its history transition before native takeover pauses it', () => {
  const f = historyPlayer();
  assert.equal(f.video.muted, true);
  assert.equal(f.calls.includes('pause'), false);
  assert.equal(f.calls.includes('element-pause'), false);
  assert.deepEqual(f.calls.find(c => Array.isArray(c) && c[0] === 'seek'), ['seek', 23]);
  f.playing();
  f.context.__syncPlayerCompact();
  assert.equal(f.calls.includes('pause'), false);
  f.flushTimers();
  assert.ok(f.calls.indexOf('youtube-history') < f.calls.indexOf('pause'));
  assert.equal(f.calls.filter(c => c === 'play').length, 1);
  assert.equal(f.calls.filter(c => c === 'pause').length, 1);
});

test('startup seek buffering is not preempted by an earlier PLAYING callback', () => {
  const f = historyPlayer(); f.playing(); f.buffering(); f.flushTimers();
  assert.equal(f.calls.includes('pause'), false);
  f.playing(); f.flushTimers();
  assert.equal(f.calls.filter(c => c === 'pause').length, 1);
});

test('ad playback does not consume the content history bootstrap', () => {
  const f = historyPlayer();
  let ad = true;
  f.player.classList.contains = name => ad && name === 'ad-showing';
  f.context.location.href = 'https://m.youtube.com/watch?v=bbbbbbbbbbb';
  f.context.__syncPlayerCompact();
  assert.equal(f.calls.filter(c => c === 'play').length, 1);
  ad = false; f.context.__syncPlayerCompact();
  assert.equal(f.calls.filter(c => c === 'play').length, 2);
});

test('pending watch pause cannot pause or mute a reused Shorts player', () => {
  const f = historyPlayer(); f.playing();
  f.context.location.href = 'https://m.youtube.com/shorts/aaaaaaaaaaa';
  f.context.__syncPlayerCompact(); f.flushTimers();
  assert.equal(f.video.muted, false);
  assert.equal(f.calls.includes('pause'), false);
  assert.equal(f.events.has('onStateChange'), false);
  assert.equal(f.videoEvents.has('playing'), false);
});

test('SPA replacement arms history once for the next watch video', () => {
  const f = historyPlayer(); f.playing(); f.flushTimers();
  f.context.location.href = 'https://m.youtube.com/watch?v=bbbbbbbbbbb';
  f.context.__syncPlayerCompact();
  assert.equal(f.calls.filter(c => c === 'play').length, 2);
  f.playing(); f.flushTimers();
  assert.equal(f.calls.filter(c => c === 'pause').length, 2);
});

test('cold controller waits for metadata and bootstraps a non-zero history seek', () => {
  const f = historyPlayer();
  let duration = 0;
  f.player.getDuration = () => duration;
  f.player.getCurrentTime = () => 0;
  f.context.location.href = 'https://m.youtube.com/watch?v=bbbbbbbbbbb';
  f.context.__syncPlayerCompact();
  assert.equal(f.calls.filter(c => c === 'play').length, 1);
  duration = 19;
  f.context.__syncPlayerCompact();
  assert.deepEqual(f.calls.filter(c => Array.isArray(c) && c[0] === 'seek').at(-1), ['seek', 1]);
  assert.equal(f.calls.filter(c => c === 'play').length, 2);
});

test('home navigation prepares the selected video before document capture stops propagation', () => {
  const fixture = page('https://m.youtube.com/', true);
  fixture.click('https://m.youtube.com/watch?v=bbbbbbbbbbb');
  assert.deepEqual(fixture.bridgeEvents, ['prepare', 'open']);
});

test('playlist sentinel is encoded only by evaluateJavascript, not by the script', () => {
  const { context } = page();
  assert.equal(context.__playlistNav(-1), 'missing-playlist');
});

test('first layout is reported after play resets native geometry', () => {
  assert.deepEqual(page().layout(), [32, 180]);
});

test('width and horizontal offset changes report even when height is unchanged', () => {
  const fixture = page();
  let bounds;
  fixture.context.innerWidth = 1000;
  fixture.context.lite.setPlayerBounds = (...values) => { bounds = values; };
  fixture.player.getBoundingClientRect = () => ({ left: 120, top: 48, width: 640, height: 360 });
  fixture.context.__syncPlayerCompact();
  assert.deepEqual(bounds, [120, 48, 640, 360, 1000]);
  fixture.player.getBoundingClientRect = () => ({ left: 80, top: 48, width: 700, height: 360 });
  fixture.context.__syncPlayerCompact();
  assert.deepEqual(bounds, [80, 48, 700, 360, 1000]);
});

test('compact watch frees the slot but reports its original geometry after resize', () => {
  const fixture = page();
  let compact = true;
  let height = 460;
  fixture.context.lite.isPlayerCompact = () => compact;
  fixture.player.getBoundingClientRect = () => ({
    top: 48, height: fixture.classes.has('lite-compact-player') ? 0 : height,
  });
  fixture.context.__syncPlayerCompact();
  assert.equal(fixture.classes.has('lite-compact-player'), true);
  assert.deepEqual(fixture.layout(), [48, 460]);
  height = 576;
  fixture.context.__syncPlayerCompact();
  assert.deepEqual(fixture.layout(), [48, 576]);
  assert.equal(fixture.classes.has('lite-compact-player'), true);
  assert.equal(fixture.styles.size, 2);
  assert.match(fixture.styles.get('lite-touch-style').textContent, /html\s*\{\s*-webkit-tap-highlight-color:\s*transparent/);
  compact = false;
  fixture.context.__syncPlayerCompact();
  assert.equal(fixture.classes.has('lite-compact-player'), false);
});

test('compact slot is removed on navigation and never applied to Shorts', () => {
  const fixture = page();
  fixture.context.lite.isPlayerCompact = () => true;
  fixture.context.__syncPlayerCompact();
  assert.equal(fixture.classes.has('lite-compact-player'), true);
  fixture.context.location.href = 'https://m.youtube.com/shorts/aaaaaaaaaaa';
  fixture.context.__syncPlayerCompact();
  assert.equal(fixture.classes.has('lite-compact-player'), false);
  fixture.context.location.href = 'https://m.youtube.com/';
  fixture.context.__syncPlayerCompact();
  assert.equal(fixture.classes.has('lite-compact-player'), false);
});

test('playlist next navigates to the target URL and returns a plain sentinel', () => {
  const { context } = page();
  context.ytInitialData = { contents: { singleColumnWatchNextResults: { playlist: { playlist: {
    contents: ['aaaaaaaaaaa', 'bbbbbbbbbbb'].map(videoId => ({ playlistPanelVideoRenderer: {
      videoId, navigationEndpoint: { commandMetadata: { webCommandMetadata: { url: `/watch?v=${videoId}&list=PLtest` } } },
    } })),
  } } } } };
  assert.equal(context.__playlistNav(1), 'navigating');
  assert.equal(context.location.href, 'https://m.youtube.com/watch?v=bbbbbbbbbbb&list=PLtest');
});

test('playlist next at the last item stops instead of wrapping', () => {
  const { context } = page();
  context.location.href = 'https://m.youtube.com/watch?v=bbbbbbbbbbb&list=PLtest';
  context.location.search = '?v=bbbbbbbbbbb&list=PLtest';
  context.ytInitialData = { contents: { singleColumnWatchNextResults: { playlist: { playlist: {
    contents: ['aaaaaaaaaaa', 'bbbbbbbbbbb'].map(videoId => ({ playlistPanelVideoRenderer: {
      videoId, navigationEndpoint: { commandMetadata: { webCommandMetadata: { url: `/watch?v=${videoId}&list=PLtest` } } },
    } })),
  } } } } };
  assert.equal(context.__playlistNav(1), 'playlist-end');
  assert.equal(context.location.href, 'https://m.youtube.com/watch?v=bbbbbbbbbbb&list=PLtest');
});

