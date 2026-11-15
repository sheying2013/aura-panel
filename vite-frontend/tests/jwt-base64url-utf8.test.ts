import assert from 'node:assert/strict';
import { test } from 'node:test';
import { getRoleIdFromToken, getUserIdFromToken, getUsernameFromToken, isTokenValid } from '../src/utils/jwt';

// Synthetic payload only; signature verification belongs to the backend.
test('Base64URL payload containing - and _ decodes UTF-8 Chinese', () => {
  const now = Math.floor(Date.now() / 1000);
  let username = '';
  let encoded = '';
  for (let pad = 0; pad < 3; pad++) {
    username = '中文用户' + 'x'.repeat(pad) + String.fromCodePoint(0x10ffff);
    encoded = Buffer.from(JSON.stringify({ sub: '42', role_id: 0, user: username, iat: now, exp: now + 3600 }), 'utf8').toString('base64url');
    if (encoded.includes('-') && encoded.includes('_')) break;
  }
  assert.ok(encoded.includes('-') && encoded.includes('_'), 'fixture must exercise both Base64URL symbols');
  const token = 'e30.' + encoded + '.synthetic';
  assert.equal(getUsernameFromToken(token), username);
  assert.equal(getUserIdFromToken(token), 42);
  assert.equal(getRoleIdFromToken(token), 0);
  assert.equal(isTokenValid(token), true);
});

test('malformed Base64, JSON and UTF-8 payloads are rejected', () => {
  for (const payload of ['!not-base64!', Buffer.from('{not-json', 'utf8').toString('base64url'), Buffer.from([0xff, 0xfe]).toString('base64url')]) {
    const token = 'e30.' + payload + '.synthetic';
    assert.equal(isTokenValid(token), false);
    assert.equal(getUsernameFromToken(token), null);
    assert.equal(getRoleIdFromToken(token), null);
  }
});
