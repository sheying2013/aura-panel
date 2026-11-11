/**
 * 纯逻辑用例：只覆盖 validateDomain，不渲染组件。
 * 接受面与后端 DdnsAddresses.normalizeDomain 对齐（Unicode、punycode 顶级域、末尾点），
 * 拒绝面覆盖 URL、端口、路径、空白与 IP。
 * 项目当前未接入测试 runner（无 vitest/jest），接入后可直接执行本文件。
 */
import assert from "node:assert/strict";
import { test } from "node:test";

import { validateDomain } from "./DdnsManagement";

test("validateDomain 接受常见域名并忽略大小写与前后空白", () => {
  assert.equal(validateDomain("ddns.example.com"), null);
  assert.equal(validateDomain("  DDNS.Example.COM  "), null);
  assert.equal(validateDomain("a.b.c.example.com"), null);
});

test("validateDomain 接受末尾点（FQDN 的根点）", () => {
  assert.equal(validateDomain("ddns.example.com."), null);
  assert.equal(validateDomain("例え.テスト."), null);
});

test("validateDomain 接受 Unicode 标签与顶级域", () => {
  assert.equal(validateDomain("bücher.example"), null);
  assert.equal(validateDomain("例え.テスト"), null);
});

test("validateDomain 接受 punycode 顶级域", () => {
  assert.equal(validateDomain("example.xn--p1ai"), null);
  assert.equal(validateDomain("example.xn--zckzah"), null);
  assert.equal(validateDomain("xn--bcher-kva.example"), null);
});

test("validateDomain 拒绝空值与单标签域名", () => {
  assert.equal(validateDomain(""), "请填写域名");
  assert.equal(validateDomain("   "), "请填写域名");
  assert.ok(validateDomain("ddns"));
  assert.ok(validateDomain("ddns."));
});

test("validateDomain 拒绝空标签", () => {
  assert.ok(validateDomain(".example.com"));
  assert.ok(validateDomain("a..example.com"));
  assert.ok(validateDomain("example.com.."));
});

test("validateDomain 拒绝 URL、端口、路径、凭据与空白", () => {
  for (const value of [
    "http://ddns.example.com",
    "https://ddns.example.com",
    "//ddns.example.com",
    "ddns.example.com:8080",
    "ddns.example.com/x",
    "user@ddns.example.com",
    "ddns.example.com?x=1",
    "ddns.example.com#top",
    "ddns example.com",
    "ddns\nexample.com",
  ]) {
    assert.ok(validateDomain(value), value + " 应被拒绝");
  }
});

test("validateDomain 拒绝 IP 字面量，包括全角数字写法", () => {
  for (const value of [
    "1.2.3.4",
    "0.0.0.0",
    "255.255.255.255",
    "１.２.３.４",
    "[::1]",
    "::1",
    "2001:db8::1",
  ]) {
    assert.ok(validateDomain(value), value + " 应被拒绝");
  }
});

test("validateDomain 拒绝非法连字符、下划线与非法 punycode", () => {
  assert.ok(validateDomain("-bad.example.com"));
  assert.ok(validateDomain("bad-.example.com"));
  assert.ok(validateDomain("bad_label.example.com"));
  assert.ok(validateDomain("xn--a.example.com"));
});

test("validateDomain 限制总长度与单个标签长度", () => {
  const label = "a".repeat(64);

  assert.ok(validateDomain(label + ".com"));
  assert.equal(
    validateDomain("a".repeat(63) + "." + "b".repeat(63) + ".example.com"),
    null,
  );
  assert.equal(
    validateDomain([63, 63, 63, 63].map((n) => "a".repeat(n)).join(".")),
    "域名长度不能超过 253",
  );
});
