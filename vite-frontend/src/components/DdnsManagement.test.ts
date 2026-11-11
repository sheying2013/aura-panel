/**
 * 纯逻辑用例：只覆盖状态推导、域名/间隔校验与时间格式化，不渲染组件。
 * 项目当前未接入测试 runner（无 vitest/jest），接入后可直接执行本文件。
 */
import type { DdnsRecord } from "./DdnsManagement";

import assert from "node:assert/strict";
import { test } from "node:test";

import {
  DEFAULT_INTERVAL_SECONDS,
  deriveStatus,
  formatInterval,
  formatTimestamp,
  parseIpList,
  validateDomain,
  validateInterval,
} from "./DdnsManagement";

const NOW = 1762770176000; // 2025-11-10T09:02:56Z，固定时间避免用例随环境漂移

function makeRecord(overrides: Partial<DdnsRecord> = {}): DdnsRecord {
  return {
    id: 1,
    domain: "ddns.example.com",
    resolvedIps: "1.1.1.1,2.2.2.2",
    primaryIp: "1.1.1.1",
    intervalSeconds: 60,
    enabled: 1,
    lastCheckTime: NOW - 30_000,
    lastChangeTime: NOW - 30_000,
    lastError: "",
    failCount: 0,
    createdTime: NOW - 600_000,
    updatedTime: NOW - 30_000,
    ...overrides,
  };
}

test("parseIpList 去掉空项与空白", () => {
  assert.deepEqual(parseIpList("1.1.1.1, 2.2.2.2 ,"), ["1.1.1.1", "2.2.2.2"]);
  assert.deepEqual(parseIpList(""), []);
  assert.deepEqual(parseIpList(null), []);
});

test("formatTimestamp 把 0 视为从未检查，不伪装成时间", () => {
  assert.equal(formatTimestamp(0), "从未");
  assert.equal(formatTimestamp(null), "从未");
  assert.match(formatTimestamp(NOW), /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/);
});

test("formatInterval 对缺失值回落到默认间隔", () => {
  assert.equal(formatInterval(120), "120 秒");
  assert.equal(formatInterval(0), DEFAULT_INTERVAL_SECONDS + " 秒");
  assert.equal(formatInterval(null), DEFAULT_INTERVAL_SECONDS + " 秒");
});

test("deriveStatus 优先展示最近一次失败原因", () => {
  const status = deriveStatus(
    makeRecord({ lastError: "DNS 解析失败：timeout", failCount: 3 }),
    NOW,
  );

  assert.equal(status.key, "error");
  assert.equal(status.label, "检查失败");
  assert.ok(status.detail.includes("3 次"));
  assert.ok(status.detail.includes("timeout"));
});

test("deriveStatus 对从未下发的记录标记未确认", () => {
  const status = deriveStatus(
    makeRecord({ primaryIp: "", lastCheckTime: 0 }),
    NOW,
  );

  assert.equal(status.key, "unconfirmed");
  assert.equal(status.label, "未确认");
});

test("deriveStatus 对暂停记录不当作正常", () => {
  const status = deriveStatus(makeRecord({ enabled: 0 }), NOW);

  assert.equal(status.key, "paused");
  assert.equal(status.color, "default");
});

test("deriveStatus 对超过两个周期未检查的记录标记超期", () => {
  const status = deriveStatus(
    makeRecord({ lastCheckTime: NOW - 130_000 }),
    NOW,
  );

  assert.equal(status.key, "stale");
});

test("deriveStatus 仅在检查新鲜且无错误时返回正常", () => {
  assert.equal(deriveStatus(makeRecord(), NOW).key, "ok");
});

test("validateDomain 拒绝空值与非法域名，接受常见 DDNS 域名", () => {
  assert.equal(validateDomain(""), "请填写域名");
  assert.ok(validateDomain("ddns"));
  assert.ok(validateDomain("http://ddns.example.com"));
  assert.equal(validateDomain("ddns.example.com"), null);
  assert.equal(validateDomain("  DDNS.Example.COM  "), null);
});

test("validateInterval 限制 10 到 3600 秒", () => {
  assert.ok(validateInterval("9"));
  assert.ok(validateInterval("3601"));
  assert.ok(validateInterval("1.5"));
  assert.equal(validateInterval("10"), null);
  assert.equal(validateInterval("3600"), null);
});
