/**
 * 组件级用例：DDNS 入口的角色收敛。
 *
 * 权限权威在后端 @RequireRole；这里验证非管理员/未知权限在浏览器侧看不到也不能操作，
 * 且"角色未加载/解析失败"不会被当成管理员。
 *
 * 运行：node --import ./tests/register.mjs --test ./tests/ddns-admin-gate.test.tsx
 */
import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";

import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

type DdnsAccess = "admin" | "denied" | "unknown";
type DdnsComponent = () => JSX.Element;

function createLocalStorage(): Storage {
  const store = new Map<string, string>();

  return {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
    key: (index: number) => Array.from(store.keys())[index] ?? null,
    get length() {
      return store.size;
    },
  } as Storage;
}

const localStorageStub = createLocalStorage();

// 组件依赖的 @/api/network 在模块顶层读取 window 与 import.meta.env，SSR 前先补最小浏览器壳
// react-hot-toast 依赖的 goober 在导入期就要插入 <style>，只补它用到的那几个节点方法
const noop = () => undefined;

const elementStub = () => ({
  style: {} as Record<string, string>,
  innerHTML: "",
  nonce: "",
  parentNode: null,
  firstChild: { data: "" },
  setAttribute: noop,
  appendChild: noop,
  addEventListener: noop,
  removeEventListener: noop,
});

const documentStub = {
  readyState: "complete",
  documentElement: {
    style: {} as Record<string, string>,
    addEventListener: noop,
    removeEventListener: noop,
  },
  createElement: elementStub,
  createElementNS: elementStub,
  createTextNode: () => ({}),
  querySelector: () => null,
  getElementById: () => null,
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  head: { appendChild: () => undefined },
  body: {
    appendChild: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
  },
};

class HtmlElementStub {
  focus() {}
  blur() {}
  addEventListener() {}
  removeEventListener() {}
}

const windowStub = {
  HTMLElement: HtmlElementStub,
  location: {
    href: "http://localhost/",
    origin: "http://localhost",
    protocol: "http:",
    host: "localhost",
    hostname: "localhost",
    pathname: "/ddns",
  },
  localStorage: localStorageStub,
  document: documentStub,
  screen: {
    orientation: {
      angle: 0,
      type: "landscape-primary",
      addEventListener: noop,
      removeEventListener: noop,
    },
    width: 0,
    height: 0,
  },
  visualViewport: { width: 0, height: 0, addEventListener: noop, removeEventListener: noop },
  navigator: {
    userAgent: "node-test",
    maxTouchPoints: 0,
    platform: "node",
    language: "zh-CN",
    languages: ["zh-CN"],
  },
  addEventListener: () => undefined,
  removeEventListener: () => undefined,
  matchMedia: () => ({
    matches: false,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    addListener: () => undefined,
    removeListener: () => undefined,
  }),
};

Object.assign(globalThis, {
  window: windowStub,
  document: documentStub,
  localStorage: localStorageStub,
});

const { default: DdnsManagement, resolveDdnsAccess } = (await import(
  "../src/components/DdnsManagement"
)) as {
  default: DdnsComponent;
  resolveDdnsAccess: (roleId: number | null | undefined) => DdnsAccess;
};

function setSession(roleId: string | null, token: string | null = "token") {
  localStorageStub.clear();
  if (token !== null) localStorageStub.setItem("token", token);
  if (roleId !== null) localStorageStub.setItem("role_id", roleId);
}

function render(): string {
  return renderToStaticMarkup(createElement(DdnsManagement));
}

beforeEach(() => {
  localStorageStub.clear();
});

test("resolveDdnsAccess 只把 role_id=0 当管理员，其余与缺失都收敛", () => {
  assert.equal(resolveDdnsAccess(0), "admin");
  assert.equal(resolveDdnsAccess(1), "denied");
  assert.equal(resolveDdnsAccess(99), "denied");
  assert.equal(resolveDdnsAccess(null), "unknown");
  assert.equal(resolveDdnsAccess(undefined), "unknown");
  assert.equal(resolveDdnsAccess(Number.NaN), "unknown");
  assert.equal(resolveDdnsAccess(1.5), "unknown");
});

test("普通用户直接访问 /ddns 只看到拒绝提示，没有新增与列表操作", () => {
  setSession("1");

  const html = render();

  assert.match(html, /仅管理员可访问/);
  assert.ok(!html.includes("新增域名"));
  assert.ok(!html.includes("DDNS 域名列表"));
  assert.ok(!html.includes("立即检查"));
});

test("缺少登录令牌时不误认管理员", () => {
  setSession("0", null);

  const html = render();

  assert.match(html, /无法确认当前账号权限/);
  assert.ok(!html.includes("新增域名"));
});

test("role_id 未写入时不误认管理员", () => {
  setSession(null);

  const html = render();

  assert.match(html, /无法确认当前账号权限/);
  assert.ok(!html.includes("新增域名"));
});

test("role_id 脏数据时不误认管理员", () => {
  setSession("0x0");

  const html = render();

  assert.match(html, /无法确认当前账号权限/);
  assert.ok(!html.includes("新增域名"));
});

test("管理员保留原有 DDNS 管理界面", () => {
  setSession("0");

  const html = render();

  assert.match(html, /DDNS 管理/);
  assert.match(html, /新增域名/);
  assert.match(html, /DDNS 域名列表/);
  assert.ok(!html.includes("仅管理员可访问"));
});
