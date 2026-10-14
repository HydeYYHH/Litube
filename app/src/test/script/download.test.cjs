const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const CORE = fs.readFileSync(
  path.join(__dirname, '../../main/assets/script/core.js'), 'utf8');
const BRIDGE = fs.readFileSync(
  path.join(__dirname, '../../main/assets/script/download.js'), 'utf8');
const PLAYER_HOOK = fs.readFileSync(
  path.join(__dirname, '../../main/assets/script/player-hook.js'), 'utf8');
const SHORTS_QUALITY = fs.readFileSync(
  path.join(__dirname, '../../main/assets/script/shorts-quality.js'), 'utf8');

// A just-enough DOM: parent/child links, attribute bag, class/id/tag
// matching over comma selectors, clone, and an id registry.
class El {
  constructor(tag, className) {
    this.tagName = tag.toUpperCase();
    this.className = className || '';
    this.attrs = {};
    this.children = [];
    this.parentNode = null;
    this.style = {};
    this.dataset = new Proxy({}, {
      get: (_, key) => this.attrs[dataAttr(key)],
      set: (_, key, value) => { this.attrs[dataAttr(key)] = String(value); return true; },
    });
    this.textContent = '';
    this.listeners = {};
    this.shadowRoot = null;
  }

  get id() { return this.attrs.id || ''; }
  set id(value) { this.attrs.id = value; }

  get href() { return this.attrs.href || ''; }
  set href(value) { this.attrs.href = value; }

  get parentElement() { return this.parentNode instanceof El ? this.parentNode : null; }
  get isConnected() {
    let n = this;
    while (n.parentNode) n = n.parentNode;
    return n === this.root;
  }
  get previousElementSibling() {
    const sibs = this.parentNode ? this.parentNode.children : [];
    return sibs[sibs.indexOf(this) - 1] || null;
  }
  get nextElementSibling() {
    const sibs = this.parentNode ? this.parentNode.children : [];
    return sibs[sibs.indexOf(this) + 1] || null;
  }
  get nextSibling() { return this.nextElementSibling; }
  get firstElementChild() { return this.children[0] || null; }

  appendChild(node) { return this.insertBefore(node, null); }
  insertBefore(node, ref) {
    if (node.parentNode) node.parentNode.remove(node);
    const at = ref ? this.children.indexOf(ref) : this.children.length;
    this.children.splice(at < 0 ? this.children.length : at, 0, node);
    node.parentNode = this;
    node.root = this.root;
    for (const d of descendants(node)) d.root = this.root;
    return node;
  }
  remove(node) {
    if (node === undefined) {
      if (this.parentNode) this.parentNode.remove(this);
      return;
    }
    const at = this.children.indexOf(node);
    if (at >= 0) {
      this.children.splice(at, 1);
      node.parentNode = null;
    }
  }
  replaceWith(node) {
    const parent = this.parentNode;
    if (!parent) return;
    parent.insertBefore(node, this);
    this.remove();
  }
  cloneNode() {
    const copy = new El(this.tagName, this.className);
    copy.attrs = { ...this.attrs };
    copy.textContent = this.textContent;
    // A custom-element clone re-upgrades and rebuilds its shadow root.
    copy.shadowRoot = this.shadowRoot ? this.shadowRoot.cloneNode() : null;
    for (const child of this.children) copy.appendChild(child.cloneNode());
    return copy;
  }

  getAttribute(name) { return this.attrs[name] ?? null; }
  hasAttribute(name) { return name in this.attrs; }
  getBoundingClientRect() { return { width: 360, height: 48, top: 0, bottom: 48 }; }
  setAttribute(name, value) { this.attrs[name] = String(value); }
  removeAttribute(name) { delete this.attrs[name]; }

  matchesOne(token) {
    token = token.trim();
    if (!token) return false;
    const tag = token.match(/^[a-zA-Z][\w-]*/);
    let rest = token;
    if (tag) {
      if (this.tagName !== tag[0].toUpperCase()) return false;
      rest = token.slice(tag[0].length);
    }
    if (!rest) return true;
    const parts = rest.match(/(\.[\w-]+|#[\w-]+|\[[^\]]+\])/g);
    return !!parts && parts.join('') === rest && parts.every((x) => this.matchesSimple(x));
  }

  matchesSimple(token) {
    token = token.trim();
    if (!token) return false;
    if (token.startsWith('.')) return this.className.split(/\s+/).includes(token.slice(1));
    if (token.startsWith('#')) return this.id === token.slice(1);
    if (token.startsWith('[')) {
      const body = token.slice(1, token.lastIndexOf(']'));
      const op = body.match(/([~|^$*]?=)/);
      if (!op) return this.attrs[body] !== undefined;
      const have = this.attrs[body.slice(0, op.index)];
      if (have === undefined) return false;
      const value = body.slice(op.index + op[1].length).replace(/^["']|["']$/g, '');
      if (op[1] === '*=') return have.includes(value);
      if (op[1] === '^=') return have.startsWith(value);
      if (op[1] === '$=') return have.endsWith(value);
      return have === value;
    }
    return this.tagName === token.toUpperCase();
  }
  matches(selector) {
    return String(selector).split(',').some((t) => this.matchesOne(t));
  }
  closest(selector) {
    let n = this;
    while (n) {
      if (n.matches(selector)) return n;
      n = n.parentElement;
    }
    return null;
  }
  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
  querySelectorAll(selector) {
    const hits = [];
    for (const node of descendants(this)) if (node.matches(selector)) hits.push(node);
    return hits;
  }
  addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); }
}

function dataAttr(key) {
  return 'data-' + String(key).replace(/[A-Z]/g, (c) => '-' + c.toLowerCase());
}

function descendants(node) {
  const out = [];
  const walk = (n) => { for (const c of n.children) { out.push(c); walk(c); } };
  walk(node);
  return out;
}

function page({
  href = 'https://m.youtube.com/watch?v=aaaaaaaaaaa', live = false,
  withSvg = true, nestedOnly = false, subscribeOnly = false, hydrated = false,
  c3State = null, withVoteCount = false, scripts = [],
} = {}) {
  const row = new El('div', 'slim-video-action-bar-actions');
  const avatar = new El('img', 'slim-video-owner-icon');
  const subscribe = new El('ytm-subscribe-button-renderer');
  const freeChip = () => {
    const host = new El('button-view-model', 'ytSpecButtonViewModelHost');
    const iconHost = new El('div', 'ytSpecButtonShapeNextIcon');
    const svg = new El('svg');
    svg.appendChild(new El('path'));
    if (c3State) {
      const c3 = new El('c3-icon');
      const shape = new El('span', 'yt-icon-shape');
      if (c3State === 'ready') shape.appendChild(svg);
      c3.appendChild(shape);
      iconHost.appendChild(c3);
    } else if (withSvg) {
      iconHost.appendChild(svg);
    }
    host.appendChild(iconHost);
    host.appendChild(new El('span', 'ytSpecButtonShapeNextButtonTextContent'));
    return host;
  };
  const chip = freeChip();

  // Some native templates put the c3-icon glyph in a shadow root.
  const like = new El('like-button-view-model');
  const likeHost = new El('button-view-model', 'ytSpecButtonViewModelHost');
  const likeC3 = new El('c3-icon');
  const likeShadow = new El('#shadow-root');
  const likeSvg = new El('svg');
  likeSvg.appendChild(new El('path'));
  likeShadow.appendChild(likeSvg);
  likeC3.shadowRoot = likeShadow;
  likeHost.appendChild(likeC3);
  likeHost.appendChild(new El('div', 'ytSpecButtonShapeNextButtonTextContent'));
  const digits = new El('span', 'ytAttributedStringHost');
  digits.textContent = '0';
  likeHost.appendChild(digits);
  like.appendChild(likeHost);

  const dislike = new El('dislike-button-view-model');
  const dislikeHost = new El('button-view-model', 'ytSpecButtonViewModelHost');
  const dislikeC3 = new El('c3-icon');
  const dislikeShadow = new El('#shadow-root');
  const dislikeSvg = new El('svg');
  dislikeSvg.appendChild(new El('path'));
  dislikeShadow.appendChild(dislikeSvg);
  dislikeC3.shadowRoot = dislikeShadow;
  dislikeHost.appendChild(dislikeC3);
  dislikeHost.appendChild(new El('div', 'ytSpecButtonShapeNextButtonTextContent'));
  dislikeHost.appendChild(new El('span', 'ytAttributedStringHost'));
  if (withVoteCount) {
    const count = new El('span');
    count.setAttribute('data-lite-vote-count', '');
    count.textContent = '128';
    dislikeHost.appendChild(count);
  }
  dislike.appendChild(dislikeHost);

  // The subscribe pill hosts no view-model and its svg is a lottie frame.
  const pillHost = new El('button', 'ytSpecButtonShapeNextHost');
  const pillLottie = new El('lottie-component');
  pillLottie.appendChild(new El('svg'));
  pillHost.appendChild(pillLottie);
  pillHost.appendChild(new El('div', 'ytSpecButtonShapeNextButtonTextContent'));
  subscribe.appendChild(pillHost);

  const panel = new El('div', 'watch-below-the-player');
  const posted = [];
  const doc = new El('#document');
  doc.root = doc;
  const html = new El('html');
  doc.appendChild(html);
  const barHost = new El('ytm-slim-video-action-bar-renderer');
  html.appendChild(barHost);
  barHost.appendChild(row);
  row.appendChild(avatar);
  if (nestedOnly) {
    row.appendChild(like);
    row.appendChild(dislike);
  } else if (subscribeOnly) {
    row.appendChild(subscribe);
  } else if (hydrated) {
    // Steady-state bar: like/dislike hydrated ahead of the free chips.
    row.appendChild(subscribe);
    row.appendChild(like);
    row.appendChild(dislike);
    row.appendChild(chip);
    row.appendChild(freeChip());
  } else {
    row.appendChild(subscribe);
    row.appendChild(chip);
  }
  html.appendChild(panel);

  html.lang = 'en';
  const context = {
    URL,
    console: { warn() {}, error() {} },
    Element: El,
    document: {
      root: doc,
      documentElement: html,
      body: new El('body'),
      readyState: 'complete',
      visibilityState: 'visible',
      getElementById: (id) => descendants(doc).find((n) => n.id === id) || null,
      querySelector: (selector) =>
        (String(selector).includes('slim-video-action-bar-actions') ? row : null) ||
        descendants(doc).find((n) => n.matches(selector)) || null,
      querySelectorAll: (selector) => descendants(doc).filter((n) => n.matches(selector)),
      createElement: (tag) => new El(tag),
      createElementNS: (ns, tag) => new El(tag),
      addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
      listeners: {},
    },
    Download: {
      addEventListener() {},
      postMessage(json) { posted.push(JSON.parse(json)); },
    },
    location: { href, hostname: 'm.youtube.com', hash: '' },
    navigator: { language: 'en' },
    history: { pushState() {}, back() {} },
    ytInitialPlayerResponse: { videoDetails: { videoId: 'aaaaaaaaaaa', isLive: live } },
    setTimeout: () => 1,
    clearTimeout: () => {},
    setInterval: () => 1,
    requestAnimationFrame: (fn) => fn(),
    MutationObserver: class { observe() {} },
    matchMedia: () => ({ matches: false }),
    listeners: {},
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
  };
  context.window = context;
  context.globalThis = context;
  context.top = context;
  context.self = context;
  vm.runInNewContext([CORE, ...scripts, BRIDGE].join('\n'), context);
  return { context, row, chip, like, dislike, posted };
}

function ids(row) {
  return row.children.map((n) => n.id || n.tagName.toLowerCase());
}

function tap(context, target, types = ['pointerdown', 'click']) {
  const path = [];
  for (let node = target; node; node = node.parentNode) path.push(node);
  let event;
  for (const type of types) {
    event = { type, button: 0, target, defaultPrevented: false, stopped: false, composedPath: () => path,
      preventDefault() { this.defaultPrevented = true; }, stopImmediatePropagation() { this.stopped = true; } };
    for (const fn of [...(context.listeners[type] || []), ...(context.document.listeners[type] || [])]) {
      if (event.stopped) break;
      fn(event);
    }
  }
  return event;
}

function sheetObserver(context) {
  const observers = [];
  context.MutationObserver = class {
    constructor(fn) { this.fn = fn; observers.push(this); }
    observe() {} disconnect() {}
    added(node) { this.fn([{ addedNodes: node ? [node] : [] }]); }
  };
  return () => observers[observers.length - 1];
}

function listSheet(context, labels, values = {}) {
  const list = new El('div');
  for (const label of labels) {
    const item = new El('yt-list-item-view-model');
    const button = new El('button');
    const icon = new El('span', 'yt-icon-shape');
    const svg = new El('svg');
    svg.appendChild(new El('path'));
    icon.appendChild(svg);
    const title = new El('span', 'ytListItemViewModelTitle');
    title.textContent = label;
    button.appendChild(icon);
    button.appendChild(title);
    if (values[label]) {
      const value = new El('span', 'ytListItemViewModelSelectionText');
      value.textContent = values[label];
      button.appendChild(value);
    }
    item.appendChild(button);
    list.appendChild(item);
  }
  const layout = new El('bottom-sheet-layout');
  layout.appendChild(list);
  context.document.documentElement.appendChild(layout);
  return { layout, list, titles: () => list.children.map((n) => n.querySelector('.ytListItemViewModelTitle').textContent) };
}

function toShorts(context, id = 'bbbbbbbbbbb') {
  context.location.href = `https://m.youtube.com/shorts/${id}`;
  context.location.pathname = `/shorts/${id}`;
}

function shortsSheet(context) {
  toShorts(context);
  const sheet = listSheet(context, ['Description', 'Quality', 'Report']);
  sheet.list.children[1].setAttribute('data-injected', 'shorts-quality');
  return sheet;
}

function feedCard(context) {
  const card = new El('ytm-media-item');
  const link = new El('a');
  link.setAttribute('href', '/watch?v=ccccccccccc');
  const title = new El('h3', 'media-item-headline');
  title.textContent = 'Card C';
  link.appendChild(title);
  const menu = new El('ytm-bottom-sheet-renderer', 'media-item-menu');
  const trigger = new El('button');
  menu.appendChild(trigger);
  card.appendChild(link);
  card.appendChild(menu);
  context.document.documentElement.appendChild(card);
  return trigger;
}

function feedSheet(context, labels) {
  const list = new El('div', 'bottom-sheet-media-menu-item');
  for (const label of labels) {
    const item = new El('ytm-menu-navigation-item-renderer');
    const button = new El('button', 'menu-item-button');
    const text = new El('span', 'ytAttributedStringHost');
    text.setAttribute('role', 'text');
    text.textContent = label;
    button.appendChild(new El('c3-icon'));
    button.appendChild(text);
    item.appendChild(button);
    list.appendChild(item);
  }
  const layout = new El('bottom-sheet-layout');
  layout.appendChild(list);
  context.document.documentElement.appendChild(layout);
  return { layout, list, titles: () => list.children.map((n) => n.querySelector('[role="text"]').textContent) };
}

test('reused action buttons refresh translations without duplicating controls', () => {
  const { context, row, posted } = page();
  const queue = row.querySelector('#queueButton');
  const requestCount = posted.length;
  context.document.documentElement.lang = 'de-DE'; context.Lite.wake();
  assert.equal(queue.getAttribute('aria-label'), 'Zur Warteschlange hinzufügen');
  assert.equal(row.querySelector('#openWithButton').getAttribute('aria-label'), 'Öffnen mit');
  context.document.documentElement.lang = 'zh-Hant-HK'; context.Lite.wake();
  assert.equal(row.querySelector('#queueButton'), queue);
  assert.equal(queue.getAttribute('aria-label'), '加入佇列');
  assert.equal(row.querySelectorAll('#queueButton').length, 1);
  assert.equal(posted.length, requestCount);
});

test('watch page injects download, queue, and open with before the native chip, in order', () => {
  const { row } = page();
  assert.deepEqual(
    ids(row),
    ['img', 'ytm-subscribe-button-renderer',
      'downloadButton', 'queueButton', 'openWithButton', 'button-view-model'],
  );
});

test('entries own one glyph and an accessible localized button without native renderers', () => {
  const { row } = page();
  const download = row.querySelector('#downloadButton');
  assert.equal(download.querySelector('path').attrs.d.startsWith('M480-320'), true);
  // Icon-only entries never import native text or view-model lifecycle.
  assert.equal(download.getAttribute('aria-label'), 'Download');
  assert.equal(download.querySelector('.ytSpecButtonShapeNextButtonTextContent'), null);
  assert.equal(download.querySelector('button').getAttribute('aria-label'), 'Download');
  assert.equal(download.querySelector('button-view-model,c3-icon'), null);
  assert.equal(row.querySelector('#queueButton').getAttribute('aria-label'), 'Add to queue');
  assert.equal(row.querySelector('#openWithButton').getAttribute('aria-label'), 'Open with');
  // Squeezing bars collapse entries below icon size, overlapping glyphs.
  assert.equal(download.style.flex, 'none');
});

test('stable rows are not rewritten: repeated passes keep the same nodes', () => {
  const { context, row } = page();
  const before = [...row.children];
  context.Lite.wake();
  context.Lite.wake();
  assert.deepEqual([...row.children], before);
});

test('entries render before the native chip SVG hydrates', () => {
  const { row } = page({ withSvg: false });
  assert.deepEqual(
    ids(row),
    ['img', 'ytm-subscribe-button-renderer',
      'downloadButton', 'queueButton', 'openWithButton', 'button-view-model'],
  );
  const host = row.querySelector('#downloadButton').querySelector('button');
  assert.equal(host.querySelector('path').attrs.d.startsWith('M480-320'), true);
});

test('a misplaced entry is re-anchored in front of the chip without duplicates', () => {
  const { context, row } = page();
  row.insertBefore(row.querySelector('#downloadButton'), row.children[0]);
  assert.equal(ids(row)[0], 'downloadButton');
  context.Lite.wake();
  assert.deepEqual(
    ids(row),
    ['img', 'ytm-subscribe-button-renderer',
      'downloadButton', 'queueButton', 'openWithButton', 'button-view-model'],
  );
  assert.equal(row.querySelectorAll('#downloadButton').length, 1);
});

test('a re-rendered row adopts the existing entries instead of cloning again', () => {
  const { context, row, chip } = page();
  const download = row.querySelector('#downloadButton');
  const freshChip = chip.cloneNode();
  row.insertBefore(freshChip, chip);
  context.Lite.wake();
  assert.equal(row.querySelector('#downloadButton'), download);
  assert.deepEqual(ids(row), [
    'img', 'ytm-subscribe-button-renderer',
    'downloadButton', 'queueButton', 'openWithButton',
    'button-view-model', 'button-view-model',
  ]);
});

test('live pages swap the three entries for the chat entry', () => {
  const { row } = page({ live: true });
  assert.deepEqual(
    ids(row),
    ['img', 'ytm-subscribe-button-renderer', 'chatButton', 'button-view-model'],
  );
});

test('a bar with only vote renderers keeps them intact and inserts independent actions after dislike', () => {
  const { row, dislike } = page({ nestedOnly: true });
  assert.deepEqual(ids(row), [
    'img', 'like-button-view-model', 'dislike-button-view-model',
    'downloadButton', 'queueButton', 'openWithButton',
  ]);
  const glyph = row.querySelector('#downloadButton').querySelector('path');
  assert.equal(glyph.attrs.d.startsWith('M480-320'), true);
  assert.equal(row.querySelector('#downloadButton').querySelector('c3-icon'), null);
  assert.ok(dislike.querySelector('c3-icon').shadowRoot.querySelector('svg'));
  // Native rolling digits never enter the independent actions.
  const download = row.querySelector('#downloadButton');
  assert.equal(download.getAttribute('aria-label'), 'Download');
  assert.equal(download.querySelector('.ytAttributedStringHost'), null);
});

test('independent actions never copy vote labels or change the original dislike', () => {
  const { row, dislike } = page({ nestedOnly: true, withVoteCount: true });
  assert.equal(dislike.querySelector('[data-lite-vote-count]').textContent, '128');
  for (const id of ['downloadButton', 'queueButton', 'openWithButton']) {
    assert.equal(row.querySelector('#' + id).querySelector('[data-lite-vote-count]'), null);
    assert.ok(row.querySelector('#' + id).querySelector('svg'));
  }
});

test('native c3-icon hydration cannot replace or duplicate independent action glyphs', () => {
  for (const c3State of ['loading', 'ready']) {
    const { context, row, chip } = page({ c3State });
    for (const id of ['downloadButton', 'queueButton', 'openWithButton']) {
      const entry = row.querySelector('#' + id);
      const slot = entry.querySelector('button');
      assert.equal(slot.children.length, 1);
      assert.equal(entry.querySelector('c3-icon'), null);
      assert.equal(slot.children[0].tagName, 'SVG');
      assert.equal(entry.querySelectorAll('svg').length, 1);
    }
    // The native source keeps its own lifecycle; a late render there must
    // not replace or move any of the entries' SVGs.
    const nativeShape = chip.querySelector('.yt-icon-shape');
    const glyphs = row.querySelectorAll('[data-injected]').map((entry) => entry.querySelector('svg'));
    nativeShape.appendChild(new El('svg'));
    context.Lite.wake();
    assert.deepEqual(row.querySelectorAll('[data-injected]').map((entry) => entry.querySelector('svg')), glyphs);
    assert.ok(chip.querySelector('c3-icon'));
  }
});

test('a hydrated bar slots the entries between dislike and the free chips', () => {
  const { row } = page({ hydrated: true });
  assert.deepEqual(ids(row), [
    'img', 'ytm-subscribe-button-renderer',
    'like-button-view-model', 'dislike-button-view-model',
    'downloadButton', 'queueButton', 'openWithButton',
    'button-view-model', 'button-view-model',
  ]);
});

test('a late dislike hydration re-anchors the block ahead of the chips', () => {
  const { context, row, chip, like, dislike } = page();
  assert.deepEqual(
    ids(row),
    ['img', 'ytm-subscribe-button-renderer',
      'downloadButton', 'queueButton', 'openWithButton', 'button-view-model'],
  );
  // YouTube hydrates like/dislike in front of the block after the fact.
  row.insertBefore(like, chip);
  row.insertBefore(dislike, chip);
  context.Lite.wake();
  assert.deepEqual(ids(row), [
    'img', 'ytm-subscribe-button-renderer', 'like-button-view-model',
    'dislike-button-view-model', 'downloadButton', 'queueButton',
    'openWithButton', 'button-view-model',
  ]);
});

test('a subscribe-only bar with a lottie svg ships nothing rather than stamping it', () => {
  const { row } = page({ subscribeOnly: true });
  assert.deepEqual(
    ids(row),
    ['img', 'ytm-subscribe-button-renderer'],
  );
});

test('entry clicks route through the bridge', () => {
  const { context, row, posted } = page();
  const queued = [];
  const opened = [];
  context.Bridge = {
    addToQueue: (json) => queued.push(JSON.parse(json)),
    openWith: (url) => opened.push(url),
  };
  const click = (id) => row.querySelector('#' + id).listeners.click.forEach(
    (fn) => fn({ preventDefault() {}, stopImmediatePropagation() {} }));
  click('downloadButton');
  // The load pass posts requestStatus first; the click adds the confirm.
  const confirm = posted.find((message) => message.type === 'openConfirm');
  assert.equal(confirm.videoId, 'aaaaaaaaaaa');
  click('queueButton');
  click('openWithButton');
  assert.equal(queued.length, 1);
  assert.equal(queued[0].videoId, 'aaaaaaaaaaa');
  assert.equal(queued[0].url, context.location.href);
  assert.deepEqual(opened, [context.location.href]);
});

test('the chat entry opens the panel and posts nothing new', () => {
  const { context, row, posted } = page({ live: true });
  row.querySelector('#chatButton').listeners.click.forEach(
    (fn) => fn({ preventDefault() {}, stopImmediatePropagation() {} }));
  assert.ok(context.document.getElementById('chatBox'));
  // Only the load-time status request: chat clicks never touch the bridge.
  assert.deepEqual(posted.map((message) => message.type), ['requestStatus']);
});

test('non-watch pages keep no entries', () => {
  const { context, row } = page({ href: 'https://m.youtube.com/feed/subscriptions' });
  assert.deepEqual(ids(row), ['img', 'ytm-subscribe-button-renderer', 'button-view-model']);
});

test('a later status relabels the download entry, an unrelated one does not', () => {
  const { context, row } = page();
  const download = row.querySelector('#downloadButton');
  const labelOf = () => download.getAttribute('aria-label');
  context.listeners.downloadStatus.forEach((fn) =>
    fn({ detail: JSON.stringify({ videoId: 'aaaaaaaaaaa', watchPageDownloaded: true }) }));
  assert.equal(labelOf(), 'Downloaded');
  assert.equal(download.getAttribute('data-downloaded'), '1');
  context.listeners.downloadStatus.forEach((fn) =>
    fn({ detail: JSON.stringify({ videoId: 'bbbbbbbbbbb', watchPageDownloaded: true }) }));
  assert.equal(labelOf(), 'Downloaded');
});

test('playlist snapshots walk page data and DOM without duplicates', () => {
  const { context } = page({ href: 'https://m.youtube.com/playlist?list=PL' });
  context.ytInitialData = { contents: { singleColumnBrowseResultsRenderer: {
    b1: { playlistVideoRenderer: { videoId: 'aaaaaaaaaaa', title: { runs: [{ text: 'One' }] } } },
  } } };
  const root = new El('ytm-playlist-video-list-renderer');
  const link = new El('a');
  link.attrs.href = '/watch?v=bbbbbbbbbbb';
  link.textContent = 'Two';
  root.appendChild(link);
  context.document.querySelectorAll = (selector) => (
    String(selector).startsWith('ytm-playlist-video-list-renderer') ? [root] : []
  );
  const snap = context.__download.collect();
  // Items are built inside the vm realm; spread them so deepStrictEqual
  // compares values, not cross-realm prototypes.
  assert.deepEqual(Array.from(snap.items, (item) => ({ ...item })), [
    { videoId: 'aaaaaaaaaaa', title: 'One', author: null },
    { videoId: 'bbbbbbbbbbb', title: 'Two', author: null },
  ]);
});

test('the Shorts sheet gets one Download row right after Quality that opens the confirm and closes', () => {
  const { context, posted } = page();
  const sheet = shortsSheet(context);
  context.Lite.wake(); context.Lite.wake();
  assert.deepEqual(sheet.titles(), ['Description', 'Quality', 'Download', 'Report']);
  const row = sheet.list.children[2];
  assert.equal(row.getAttribute('data-injected'), 'shorts-download');
  assert.equal(row.querySelector('button').getAttribute('aria-label'), 'Download');
  assert.equal(row.querySelector('path').attrs.d.startsWith('M480-320'), true);
  const closedAfter = [];
  context.location.hash = '#bottom-sheet';
  context.history.back = () => closedAfter.push(posted[posted.length - 1].type);
  assert.equal(tap(context, row.querySelector('button')).defaultPrevented, true);
  const { type, videoId, title, author } = posted[posted.length - 1];
  assert.deepEqual({ type, videoId, title, author },
    { type: 'openConfirm', videoId: 'bbbbbbbbbbb', title: '', author: null });
  assert.deepEqual(closedAfter, ['openConfirm']);
  context.location.href = 'https://m.youtube.com/watch?v=aaaaaaaaaaa';
  context.location.pathname = '/watch';
  context.Lite.wake();
  assert.deepEqual(sheet.titles(), ['Description', 'Quality', 'Report']);
});

test('a button tap runs sheet hooks on the next added nodes', () => {
  const { context } = page();
  const sheet = shortsSheet(context);
  const observer = sheetObserver(context);
  tap(context, sheet.list.children[0].querySelector('button'));
  observer().added();
  assert.deepEqual(sheet.titles(), ['Description', 'Quality', 'Report']);
  observer().added(sheet.list);
  assert.deepEqual(sheet.titles(), ['Description', 'Quality', 'Download', 'Report']);
});

test('the feed ⋮ sheet leads with Add to queue and Download; each acts, then closes the sheet', () => {
  const { context, posted } = page({ href: 'https://m.youtube.com/results?search_query=c', scripts: [PLAYER_HOOK] });
  const diagnostics = [];
  context.__litubeDiagnostic = (...args) => diagnostics.push(args);
  const observer = sheetObserver(context);
  tap(context, feedCard(context));
  const sheet = feedSheet(context, ['Watch later', 'Add to playlist']);
  observer().added(sheet.layout);
  observer().added(sheet.layout);
  assert.deepEqual(sheet.titles(), ['Add to queue', 'Download', 'Watch later', 'Add to playlist']);
  assert.deepEqual(diagnostics, []);
  const queued = [], closed = [], before = posted.length;
  context.Bridge = { addToQueue: (json) => queued.push(JSON.parse(json)) };
  context.location.hash = '#bottom-sheet';
  context.history.back = () => closed.push([queued.length, posted.length - before]);
  tap(context, sheet.list.children[0].querySelector('button'));
  tap(context, sheet.list.children[1].querySelector('button'));
  assert.deepEqual(closed, [[1, 0], [1, 1]]);
  assert.equal(queued[0].videoId, 'ccccccccccc');
  const { type, videoId, title, author } = posted[posted.length - 1];
  assert.deepEqual({ type, videoId, title, author },
    { type: 'openConfirm', videoId: 'ccccccccccc', title: 'Card C', author: null });
  tap(context, sheet.list.children[2].querySelector('button'));
  sheet.layout.remove();
  const share = feedSheet(context, ['Copy link']);
  observer().added(share.layout);
  assert.deepEqual(share.titles(), ['Copy link']);
});

test('on Shorts only the player ⋮ menu gets Quality and Download, a comment ⋮ menu does not', () => {
  for (const opener of ['ytm-bottom-sheet-renderer', 'ytm-menu-renderer']) {
    const { context } = page({ scripts: [SHORTS_QUALITY] });
    const observer = sheetObserver(context);
    const overlay = new El('ytm-reel-player-overlay-renderer');
    const menu = new El(opener);
    menu.appendChild(new El('button'));
    overlay.appendChild(menu);
    const comments = new El('ytm-engagement-panel');
    comments.appendChild(new El(opener));
    comments.children[0].appendChild(new El('button'));
    context.document.documentElement.appendChild(overlay);
    context.document.documentElement.appendChild(comments);
    toShorts(context);
    tap(context, comments.querySelector('button'));
    const comment = listSheet(context, ['Reply', 'Report']);
    observer().added(comment.layout);
    context.Lite.wake();
    assert.deepEqual(comment.titles(), ['Reply', 'Report'], opener);
    comment.layout.remove();
    tap(context, menu.children[0]);
    const player = listSheet(context, ['Description', 'Report']);
    observer().added(player.layout);
    assert.deepEqual(player.titles(), ['Description', 'Report', 'Quality', 'Download'], opener);
  }
});

test('a Shorts menu opened by a long press gets Quality and Download after its settings', () => {
  const { context } = page({ scripts: [SHORTS_QUALITY] });
  const observer = sheetObserver(context);
  const overlay = new El('ytm-reel-player-overlay-renderer');
  overlay.appendChild(new El('div'));
  context.document.documentElement.appendChild(overlay);
  toShorts(context);
  tap(context, overlay.children[0], ['pointerdown']);
  const menu = listSheet(context, ['Description', 'Captions', 'Report'], { Captions: 'Off' });
  observer().added(menu.layout);
  assert.deepEqual(menu.titles(), ['Description', 'Captions', 'Quality', 'Download', 'Report']);
});

test('openConfirm posts the native confirm only for a valid id', () => {
  const { context, posted } = page();
  const before = posted.length;
  context.__download.openConfirm({ videoId: 'ccccccccccc', title: 'T', author: 'A' });
  context.__download.openConfirm({ videoId: 'bad', title: 'T' });
  context.__download.openConfirm(null);
  assert.deepEqual(posted.slice(before).map((m) => [m.type, m.videoId, m.title, m.author]),
    [['openConfirm', 'ccccccccccc', 'T', 'A']]);
});
