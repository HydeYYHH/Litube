const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/script/gallery.js', 'utf8');
function fixture({ outside = false, link = false, badUrl = false } = {}) {
  let click, prevented = false, sent;
  const post = { querySelectorAll: () => images };
  const images = [0, 1, 2].map(index => ({
    currentSrc: badUrl ? 'https://untrusted.example/image.jpg' : `https://yt3.ggpht.com/image-${index}.jpg`,
    closest(selector) {
      if (selector === 'img') return this;
      if (selector.startsWith('ytm-backstage-image')) return outside ? null : {};
      if (selector.startsWith('ytm-backstage-post')) return post;
      return link ? {} : null;
    },
  }));
  const context = { document: { addEventListener: (_, handler) => { click = handler; } },
    Bridge: { gallery: (urls, index) => { sent = { urls: JSON.parse(urls), index }; } } };
  context.window = context;
  vm.runInNewContext(script, context); vm.runInNewContext(script, context);
  click({ target: images[1], preventDefault: () => { prevented = true; }, stopImmediatePropagation() {} });
  return { sent, prevented };
}
test('gallery keeps current post image order and clicked starting page', () => {
  const result = fixture();
  assert.equal(result.prevented, true);
  assert.deepEqual(result.sent, { urls: [0, 1, 2].map(i => `https://yt3.ggpht.com/image-${i}.jpg`), index: 1 });
});
test('non-attachments and links stay on the webpage', () => {
  for (const options of [{ outside: true }, { link: true }]) {
    const result = fixture(options); assert.equal(result.prevented, false); assert.equal(result.sent, undefined);
  }
});
test('untrusted images stay on the webpage', () => {
  const result = fixture({ badUrl: true }); assert.equal(result.prevented, false); assert.equal(result.sent, undefined);
});
