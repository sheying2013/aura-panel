/**
 * 静态提示契约：锁内本地退出不等于服务端已经撤销。
 * 约定集中验证时运行：
 * node --import ./tests/register.mjs --test ./tests/logout-notice-contract.test.ts
 *
 * 此用例检查源码分支和文案，不模拟 React toast 或网络完成时序。
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

const cases = [
  { file: '../src/layouts/admin.tsx', marker: 'const handleLogout = async' },
  { file: '../src/pages/profile.tsx', marker: 'const handleLogout = async' },
  { file: '../src/pages/change-password.tsx', marker: 'const logout = async' },
] as const;

for (const entry of cases) {
  test(entry.file + '：退出结果提示区分已清本地与服务端未确认', () => {
    const source = readFileSync(new URL(entry.file, import.meta.url), 'utf8');
    const start = source.indexOf(entry.marker);
    assert.notEqual(start, -1, '未找到退出处理函数');
    const end = source.indexOf('\n  };', start);
    assert.ok(end > start, '未找到退出处理函数末尾');
    const handler = source.slice(start, end);

    // failed 必须先返回，不能显示成功分支或导航。
    assert.match(handler, /const result = await safeLogout\([^)]*\)/);
    assert.match(handler, /if \(result === 'failed'\)[\s\S]*?toast\.error\([\s\S]*?return;/);
    // 限定两种提示均出现在 local-only/locked 的分支位置。
    assert.match(handler, /toast\(result === 'local-only'\s*\? '已尽力清理本地会话；跨标签状态及服务端撤销均未确认。'\s*:\s*'本地会话已清除；服务端注销结果未确认。'/);
    assert.doesNotMatch(handler, /toast\.success\s*\(/);
    assert.doesNotMatch(handler, /服务端(?:已|成功)(?:注销|撤销)|(?:注销|撤销)已确认/);
    assert.ok(handler.indexOf("if (result === 'failed')") < handler.indexOf('navigate('));
  });
}
