const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/script/gallery.js', 'utf8');
function fixture({ outside = false, link = false, badUrl = false, lazy = false, pointer = true } = {}) {
  class Node {
    constructor(tag, parent, properties = {}) {
      Object.assign(this, { tag, parent, children: [] }, properties);
      parent?.children.push(this);
    }
    matches(selector) { return selector.split(',').some(s => s === this.tag ||
      (s === 'a[href]' && this.tag === 'a') || (s === '[role="button"]' && this.role === 'button')); }
    closest(selector) { return this.matches(selector) ? this : this.parent?.closest(selector); }
    querySelectorAll(selector) { return this.children.flatMap(c => [ ...(c.matches(selector) ? [c] : []), ...c.querySelectorAll(selector) ]); }
    querySelector(selector) { return this.querySelectorAll(selector)[0]; }
    contains(node) { return node === this || this.children.some(c => c.contains(node)); }
  }
  const post = new Node('ytm-backstage-post-renderer');
  const multi = new Node(outside ? 'div' : 'ytm-post-multi-image-renderer', post);
  const nodes = [0, 1, 2].map(index => {
    const renderer = new Node(outside ? 'div' : 'ytm-backstage-image-renderer', multi, { role: 'button' });
    const url = badUrl ? 'https://untrusted.example/image.jpg' : `https://yt3.ggpht.com/image-${index}.jpg`;
    if (lazy) renderer.data = { image: { thumbnails: [{ url: url + '=s100', width: 100 }, { url, width: 1200 }] } };
    return new Node('img', link ? new Node('a', renderer) : renderer, { currentSrc: lazy ? '' : url });
  });
  const handlers = new Map(), calls = [];
  let time = 1000;
  const context = { URL, PointerEvent: pointer ? class {} : undefined, Date: { now: () => time },
    addEventListener: (name, handler) => handlers.set(name, handler),
    Bridge: { gallery: (urls, index) => calls.push({ urls: JSON.parse(urls), index }) } };
  context.window = context;
  vm.runInNewContext(script, context); vm.runInNewContext(script, context);
  function emit(name, properties = {}) {
    const event = { target: nodes[1], clientX: 10, clientY: 20, pointerId: 1, button: 0,
      preventDefault() { this.prevented = true; }, stopImmediatePropagation() {}, ...properties };
    handlers.get(name)?.(event); return event;
  }
  return { nodes, calls, emit, advance(ms) { time += ms; } };
}
test('gallery keeps current post image order and clicked starting page', () => {
  const result = fixture();
  assert.equal(result.emit('click').prevented, true);
  assert.deepEqual(result.calls[0], { urls: [0, 1, 2].map(i => `https://yt3.ggpht.com/image-${i}.jpg`), index: 1 });
});
test('non-attachments and links stay on the webpage', () => {
  for (const options of [{ outside: true }, { link: true }]) {
    const result = fixture(options); assert.equal(!!result.emit('click').prevented, false); assert.equal(result.calls.length, 0);
  }
});
test('untrusted images stay on the webpage', () => {
  const result = fixture({ badUrl: true }); assert.equal(!!result.emit('click').prevented, false); assert.equal(result.calls.length, 0);
});

test('wrapper tap opens lazy high-resolution images on release and suppresses compatibility click', () => {
  const result = fixture({ lazy: true });
  const target = result.nodes[1].parent;
  result.emit('pointerdown', { target });
  assert.equal(result.calls.length, 0);
  assert.equal(result.emit('pointerup', { target }).prevented, true);
  result.emit('click', { target });
  assert.equal(result.calls.length, 1);
  assert.equal(result.calls[0].index, 1);
  assert.equal(result.calls[0].urls[2], 'https://yt3.ggpht.com/image-2.jpg');
});

test('swipe, returning drag, cancellation, long press and multi-touch do not open', () => {
  for (const gesture of ['swipe', 'return', 'cancel', 'hold', 'multi']) {
    const f = fixture(); f.emit('pointerdown');
    if (gesture === 'swipe' || gesture === 'return') f.emit('pointermove', { clientX: 50 });
    if (gesture === 'cancel') f.emit('pointercancel');
    if (gesture === 'hold') f.advance(501);
    if (gesture === 'multi') f.emit('pointerdown', { isPrimary: false, pointerId: 2 });
    f.emit('pointerup', { clientX: gesture === 'swipe' ? 50 : 10 });
    f.emit('click', { detail: 1 });
    assert.equal(f.calls.length, 0, gesture);
  }
});

test('legacy touch fallback opens once before a document gesture trap can see touchend', () => {
  const f = fixture({ pointer: false });
  const point = { identifier: 4, clientX: 10, clientY: 20 };
  f.emit('touchstart', { touches: [point] });
  f.emit('touchend', { changedTouches: [point] });
  f.emit('click');
  assert.equal(f.calls.length, 1);
});

test('legacy pinch cannot open through its compatibility click', () => {
  const f = fixture({ pointer: false });
  const point = { identifier: 4, clientX: 10, clientY: 20 };
  f.emit('touchstart', { touches: [point] });
  f.emit('touchstart', { touches: [point, { ...point, identifier: 5 }] });
  f.emit('touchend', { changedTouches: [point] });
  f.emit('click', { detail: 1 });
  assert.equal(f.calls.length, 0);
});
