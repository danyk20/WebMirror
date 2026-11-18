// Tests the pure typing logic of the player (Tesla keyboard). Run: node app/src/test/js/typing.test.js
// It extracts the text between TYPING-MATH-BEGIN and TYPING-MATH-END from player.js, so it tests the shipped code.
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');

const src = fs.readFileSync(path.join(__dirname, '../../main/assets/web/player.js'), 'utf8');
const a = src.indexOf('// TYPING-MATH-BEGIN');
const b = src.indexOf('// TYPING-MATH-END');
assert(a > 0 && b > a, 'markers not found');
const lib = new Function(src.slice(a, b) + '; return { kbdFieldSpec, kbdSplitText, kbdKeyFromKeydown, kbdKeyFromBeforeInput, kbdButtonVisible, KBD_MAX_CHUNK };')();
const { kbdFieldSpec, kbdSplitText, kbdKeyFromKeydown, kbdKeyFromBeforeInput, kbdButtonVisible, KBD_MAX_CHUNK } = lib;

let n = 0;
function check(name, fn) { fn(); n++; }

check('field spec per kind', function () {
  const t = kbdFieldSpec('text', 'done', false);
  assert.deepStrictEqual([t.tag, t.type, t.inputmode, t.enterkeyhint], ['input', 'text', 'text', 'done']);
  assert.strictEqual(t.autocomplete, 'off'); assert.strictEqual(t.autocapitalize, 'off');
  assert.strictEqual(t.autocorrect, 'off'); assert.strictEqual(t.spellcheck, false);
  assert.strictEqual(kbdFieldSpec('number', 'next', false).inputmode, 'numeric');
  assert.deepStrictEqual([kbdFieldSpec('phone', 'done', false).type, kbdFieldSpec('phone', 'done', false).inputmode], ['tel', 'tel']);
  assert.strictEqual(kbdFieldSpec('email', 'send', false).type, 'email');
  assert.strictEqual(kbdFieldSpec('url', 'go', false).inputmode, 'url');
  assert.strictEqual(kbdFieldSpec('password', 'done', false).type, 'password');
  assert.strictEqual(kbdFieldSpec('password', 'done', true).tag, 'input'); // never a textarea for a password
  const m = kbdFieldSpec('text', 'enter', true);
  assert.deepStrictEqual([m.tag, m.type], ['textarea', null]);
  assert.strictEqual(kbdFieldSpec('text', 'bogus', false).enterkeyhint, 'enter');
  assert.strictEqual(kbdFieldSpec(undefined, 'search', false).enterkeyhint, 'search');
});

check('text is split into messages', function () {
  assert.deepStrictEqual(kbdSplitText('hello', 200), [{ t: 'type', text: 'hello' }]);
  assert.deepStrictEqual(kbdSplitText('', 200), []);
  assert.deepStrictEqual(kbdSplitText(null, 200), []);
  assert.deepStrictEqual(kbdSplitText('a\nb', 200), [{ t: 'type', text: 'a' }, { t: 'key', key: 'Enter' }, { t: 'type', text: 'b' }]);
  assert.deepStrictEqual(kbdSplitText('a\r\nb', 200), [{ t: 'type', text: 'a' }, { t: 'key', key: 'Enter' }, { t: 'type', text: 'b' }]);
  assert.deepStrictEqual(kbdSplitText('\n', 200), [{ t: 'key', key: 'Enter' }]);
  assert.deepStrictEqual(kbdSplitText('a\tb', 200), [{ t: 'type', text: 'a' }, { t: 'key', key: 'Tab' }, { t: 'type', text: 'b' }]);
  assert.deepStrictEqual(kbdSplitText('a\u0000b\u007f', 200), [{ t: 'type', text: 'ab' }]);
  const long = 'x'.repeat(450);
  const parts = kbdSplitText(long, KBD_MAX_CHUNK);
  assert.deepStrictEqual(parts.map(function (p) { return p.text.length; }), [200, 200, 50]);
  assert.strictEqual(parts.map(function (p) { return p.text; }).join(''), long);
});

check('surrogate pairs are never cut', function () {
  const s = 'x'.repeat(199) + '😀' + 'y'; // the emoji straddles the 200 limit
  const parts = kbdSplitText(s, 200);
  assert.strictEqual(parts.map(function (p) { return p.text; }).join(''), s);
  parts.forEach(function (p) {
    assert(p.text.length <= 200);
    const last = p.text.charCodeAt(p.text.length - 1);
    assert(!(last >= 0xd800 && last <= 0xdbff), 'ends with a lone high surrogate');
    const first = p.text.charCodeAt(0);
    assert(!(first >= 0xdc00 && first <= 0xdfff), 'starts with a lone low surrogate');
  });
  // tiny chunk size still makes progress
  assert.strictEqual(kbdSplitText('😀😀', 1).map(function (p) { return p.text; }).join(''), '😀😀');
});

check('keydown mapping', function () {
  assert.strictEqual(kbdKeyFromKeydown('Backspace', true, false), 'Backspace');
  assert.strictEqual(kbdKeyFromKeydown('Backspace', false, false), null); // edits the text being composed
  assert.strictEqual(kbdKeyFromKeydown('Backspace', true, true), null);
  assert.strictEqual(kbdKeyFromKeydown('Enter', false, false), 'Enter');
  assert.strictEqual(kbdKeyFromKeydown('Enter', true, true), null);   // commits the composition instead
  assert.strictEqual(kbdKeyFromKeydown('Tab', true, false), 'Tab');
  assert.strictEqual(kbdKeyFromKeydown('ArrowLeft', true, false), 'ArrowLeft');
  assert.strictEqual(kbdKeyFromKeydown('ArrowRight', false, false), null);
  assert.strictEqual(kbdKeyFromKeydown('ArrowUp', true, false), null);
  assert.strictEqual(kbdKeyFromKeydown('a', true, false), null);
  assert.strictEqual(kbdKeyFromKeydown('Unidentified', true, false), null);
});

check('beforeinput mapping', function () {
  assert.strictEqual(kbdKeyFromBeforeInput('deleteContentBackward', true, false), 'Backspace');
  assert.strictEqual(kbdKeyFromBeforeInput('deleteContentBackward', false, false), null);
  assert.strictEqual(kbdKeyFromBeforeInput('deleteContentBackward', true, true), null);
  assert.strictEqual(kbdKeyFromBeforeInput('insertLineBreak', true, false), 'Enter');
  assert.strictEqual(kbdKeyFromBeforeInput('insertParagraph', false, false), 'Enter');
  assert.strictEqual(kbdKeyFromBeforeInput('insertText', true, false), null);
  assert.strictEqual(kbdKeyFromBeforeInput('insertCompositionText', true, true), null);
});

check('keyboard button visibility', function () {
  assert.strictEqual(kbdButtonVisible(true, true, true), true);
  assert.strictEqual(kbdButtonVisible(false, true, true), false);
  assert.strictEqual(kbdButtonVisible(true, false, true), false);
  assert.strictEqual(kbdButtonVisible(true, true, false), false);
});

console.log('typing tests passed: ' + n + ' checks');
