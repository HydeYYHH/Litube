const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const src = fs.readFileSync(
  path.join(__dirname, '../../main/assets/script/display_dislikes.js'),
  'utf8',
);

test('vote handlers bind click not touchstart', () => {
  assert.equal((src.match(/addEventListener\("touchstart"/g) || []).length, 0);
  assert.match(src, /addEventListener\("click", lastLikeHandler\)/);
  assert.match(src, /addEventListener\("click", lastDislikeHandler\)/);
});

test('disabling restores the original like and dislike text', () => {
  assert.match(src, /likeOriginalText = container\.textContent/);
  assert.match(src, /dislikeOriginalText = container\.textContent/);
  assert.match(src, /container\.textContent = likeOriginalText/);
  assert.match(src, /container\.textContent = dislikeOriginalText/);
  assert.doesNotMatch(src, /function clearLikeCount\(\) \{[\s\S]*container\.textContent = "";/);
});
