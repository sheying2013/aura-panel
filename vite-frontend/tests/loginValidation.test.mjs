import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { readFile } from 'node:fs/promises';
import { loginValidationErrors } from '../src/pages/loginValidation.ts';

test('admin 默认密码和旧账号短密码可进入登录流程', () => {
  assert.deepEqual(loginValidationErrors('admin', 'admin'), {});
  assert.deepEqual(loginValidationErrors('legacy', 'x'), {});
});
test('缺少用户名或密码时仍给出提示', () => {
  assert.deepEqual(loginValidationErrors('  ', '  '), { username: '请输入用户名', password: '请输入密码' });
});
test('登录页面调用校验函数而非重新施加最低密码长度', async () => {
  const source = await readFile(new URL('../src/pages/index.tsx', import.meta.url), 'utf8');
  assert.ok(source.includes('loginValidationErrors(form.username, form.password)'));
  assert.ok(!source.includes('form.password.length < 6'));
});
