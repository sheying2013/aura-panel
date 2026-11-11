import { useCallback, useEffect, useState } from "react";
import { Alert } from "@heroui/alert";
import { Button } from "@heroui/button";
import { Card, CardBody, CardHeader } from "@heroui/card";
import { Chip } from "@heroui/chip";
import { Input } from "@heroui/input";
import {
  Modal,
  ModalBody,
  ModalContent,
  ModalFooter,
  ModalHeader,
  useDisclosure,
} from "@heroui/modal";
import { Spinner } from "@heroui/spinner";
import { Switch } from "@heroui/switch";
import {
  Table,
  TableBody,
  TableCell,
  TableColumn,
  TableHeader,
  TableRow,
} from "@heroui/table";
import toast from "react-hot-toast";

import Network from "@/api/network";

/**
 * DDNS 管理界面。
 *
 * 契约来源：com.admin.ddns.controller.DdnsController（/api/v1/ddns/list|create|update|delete|check），
 * 后端仍在开发中，字段以后端 DdnsRecord 为准；这里只做展示与转发，不在前端推算解析结果。
 */

export interface DdnsRecord {
  id: number;
  domain: string;
  /** 最近一次成功解析到的全部地址，逗号分隔。 */
  resolvedIps: string;
  /** 最近保存的目标主地址；该字段本身不证明所有节点已应用。 */
  primaryIp: string;
  intervalSeconds: number;
  /** 1 启用自动检查，0 暂停。 */
  enabled: number;
  lastCheckTime: number;
  lastChangeTime: number;
  /** 最近一次失败原因，成功后由后端清空。 */
  lastError: string;
  failCount: number;
  createdTime: number;
  updatedTime: number;
}

/** checkNow 返回体：DdnsCheckResult 序列化后的形状。 */
export interface DdnsCheckResult {
  success: boolean;
  changed: boolean;
  affectedForwards: number;
  message: string;
  record: DdnsRecord | null;
}

export type DdnsStatusKey = "paused" | "error" | "unconfirmed" | "stale" | "ok";

export interface DdnsStatus {
  key: DdnsStatusKey;
  label: string;
  color: "default" | "primary" | "success" | "warning" | "danger";
  /** 说明状态依据，避免把「没检查过」说成「正常」。 */
  detail: string;
}

export const DEFAULT_INTERVAL_SECONDS = 60;

export type DdnsAccess = "admin" | "denied" | "unknown";

/**
 * 前端只负责入口收敛，权限权威仍在后端 @RequireRole。
 * 角色缺失、令牌失效或解析失败都属于「未知」，不得当成管理员。
 */
export function resolveDdnsAccess(
  roleId: number | null | undefined,
): DdnsAccess {
  if (typeof roleId !== "number" || !Number.isInteger(roleId)) return "unknown";

  return roleId === 0 ? "admin" : "denied";
}

/**
 * 角色来源与登录页、管理端布局一致：登录成功时由服务端返回并写入 role_id（0 为管理员）。
 * 这里不复用 utils/auth 的 JWT 解析——它的 atob 解码会拒绝合法的 base64url 载荷，
 * 真实管理员中约一成会被误判成「未知」；解析失败一律 fail-closed。
 */
function readDdnsAccess(): DdnsAccess {
  try {
    if (!localStorage.getItem("token")) return "unknown";

    const stored = localStorage.getItem("role_id");

    if (stored === null || !/^\d+$/.test(stored)) return "unknown";

    return resolveDdnsAccess(Number(stored));
  } catch {
    return "unknown";
  }
}

export function parseIpList(csv: string | null | undefined): string[] {
  if (!csv) return [];

  return csv
    .split(",")
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

export function formatTimestamp(ts: number | null | undefined): string {
  if (!ts || ts <= 0) return "从未";

  const d = new Date(ts);
  const pad = (n: number) => String(n).padStart(2, "0");

  return (
    String(d.getFullYear()) +
    "-" +
    pad(d.getMonth() + 1) +
    "-" +
    pad(d.getDate()) +
    " " +
    pad(d.getHours()) +
    ":" +
    pad(d.getMinutes()) +
    ":" +
    pad(d.getSeconds())
  );
}

export function formatInterval(seconds: number | null | undefined): string {
  const value = seconds && seconds > 0 ? seconds : DEFAULT_INTERVAL_SECONDS;

  return value + " 秒";
}

/**
 * 状态只由后端落库字段推导：
 * - lastError 非空 → 最近一次检查失败（错误原文展示，不吞掉）；
 * - primaryIp 为空 → 尚无已保存的主地址；
 * - 超过 2 个检查周期没有落库 → 检查超期，本轮结果未知。
 */
export function deriveStatus(record: DdnsRecord, now: number): DdnsStatus {
  if (record.enabled === 0) {
    return {
      key: "paused",
      label: "已暂停",
      color: "default",
      detail: "自动检查已暂停；已保存的主地址不代表节点当前配置已确认。",
    };
  }

  if (record.lastError) {
    return {
      key: "error",
      label: "检查失败",
      color: "danger",
      detail:
        "连续失败 " + (record.failCount || 0) + " 次：" + record.lastError,
    };
  }

  if (!record.primaryIp) {
    return {
      key: "unconfirmed",
      label: "未确认",
      color: "warning",
      detail: "尚无已保存的主地址；节点应用状态未知。",
    };
  }

  const intervalMs =
    (record.intervalSeconds || DEFAULT_INTERVAL_SECONDS) * 1000;

  if (!record.lastCheckTime || record.lastCheckTime <= 0) {
    return {
      key: "unconfirmed",
      label: "未确认",
      color: "warning",
      detail: "后端尚无检查记录，最近状态未知。",
    };
  }

  if (now - record.lastCheckTime > intervalMs * 2) {
    return {
      key: "stale",
      label: "检查超期",
      color: "warning",
      detail: "已超过 2 个检查周期没有新的检查结果，当前地址可能已过期。",
    };
  }

  return {
    key: "ok",
    label: "检查成功",
    color: "success",
    detail: "最近一次检查已成功；节点当前应用状态需另行核实。",
  };
}

const DOMAIN_LABEL = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const IPV4_LITERAL = /^\d{1,3}(?:\.\d{1,3}){3}$/;
const DOMAIN_FORMAT_ERROR = "域名格式不正确，示例：ddns.example.com";

/**
 * 使用浏览器的 ASCII 域名形式做前端格式检查，最终规范化以服务端为准。
 *
 * 非 ASCII 与 xn-- 标签交给浏览器 URL 解析器内置的 UTS#46；它与后端 IDN.toASCII 的规则可能不同，
 * 其余纯 ASCII 原样返回：URL 解析器会把 example.123 这类数字 TLD 误判成 IPv4 而报错。
 */
function toAsciiDomain(value: string): string | null {
  const candidate = value.endsWith(".") ? value.slice(0, -1) : value;
  const needsIdna =
    /[^\x00-\x7f]/.test(candidate) || /(?:^|\.)xn--/.test(candidate);

  if (!needsIdna) return candidate;

  try {
    const host = new URL("http://" + candidate).hostname;

    return host.endsWith(".") ? host.slice(0, -1) : host;
  } catch {
    return null;
  }
}

export function validateDomain(domain: string): string | null {
  const value = domain.trim().toLowerCase();

  if (!value) return "请填写域名";
  // 与后端一致：协议、端口、路径、凭据、空白都不是「一个可解析的域名」
  if (
    value.includes("://") ||
    value.startsWith("[") ||
    value.endsWith("]") ||
    /[/@?#:%\\\s]/.test(value)
  ) {
    return "域名不能包含协议、端口、路径或空白，示例：ddns.example.com";
  }

  const ascii = toAsciiDomain(value);

  if (ascii === null) return DOMAIN_FORMAT_ERROR;
  if (ascii.length > 253) return "域名长度不能超过 253";
  if (IPV4_LITERAL.test(ascii)) return "这里要填域名，不是 IP";

  const labels = ascii.split(".");

  // 顶级域同样按标签校验：punycode（xn--…）与数字标签都放行，后端仍会再校验一次
  if (labels.length < 2 || labels.some((label) => !DOMAIN_LABEL.test(label))) {
    return DOMAIN_FORMAT_ERROR;
  }

  return null;
}

export function validateInterval(value: string): string | null {
  if (!/^\d+$/.test(value.trim())) return "检查间隔必须是整数秒";

  const seconds = Number(value);

  if (seconds < 10 || seconds > 3600) return "检查间隔必须在 10 到 3600 秒之间";

  return null;
}

const emptyForm = {
  domain: "",
  interval: String(DEFAULT_INTERVAL_SECONDS),
  enabled: true,
};

export default function DdnsManagement() {
  const [access, setAccess] = useState<DdnsAccess>(readDdnsAccess);
  const [records, setRecords] = useState<DdnsRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [saving, setSaving] = useState(false);
  const [checkingId, setCheckingId] = useState<number | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);

  const [form, setForm] = useState(emptyForm);
  const [editing, setEditing] = useState<DdnsRecord | null>(null);
  const [editInterval, setEditInterval] = useState("");
  const [editEnabled, setEditEnabled] = useState(true);
  const [pendingDelete, setPendingDelete] = useState<DdnsRecord | null>(null);

  const createDisclosure = useDisclosure();
  const editDisclosure = useDisclosure();
  const deleteDisclosure = useDisclosure();

  // 同一浏览器里换了账号（其它标签页登录/退出）时重新判定，不让旧管理员状态继续生效
  useEffect(() => {
    const syncAccess = () => setAccess(readDdnsAccess());

    window.addEventListener("storage", syncAccess);

    return () => window.removeEventListener("storage", syncAccess);
  }, []);

  const load = useCallback(async () => {
    if (access !== "admin") {
      setLoading(false);

      return;
    }
    setLoading(true);
    const res = await Network.post<DdnsRecord[]>("/ddns/list");

    if (res.code === 0 && Array.isArray(res.data)) {
      setRecords(res.data);
      setLoadError("");
    } else {
      setRecords([]);
      setLoadError(res.msg || "DDNS 列表加载失败");
    }
    setLoading(false);
  }, [access]);

  useEffect(() => {
    load();
  }, [load]);

  const replaceRecord = (record: DdnsRecord | null | undefined) => {
    if (!record) return;
    setRecords((prev) =>
      prev.map((item) => (item.id === record.id ? record : item)),
    );
  };

  // 权限在操作发生时复核：渲染期判定可能已过期，后端 @RequireRole 仍是最终权威
  const assertAdmin = (): boolean => {
    if (access === "admin") return true;
    toast.error("权限不足，仅管理员可操作 DDNS");

    return false;
  };

  const handleCreate = async () => {
    if (!assertAdmin()) return;

    const domainError = validateDomain(form.domain);
    const intervalError = validateInterval(form.interval);

    if (domainError) {
      toast.error(domainError);

      return;
    }
    if (intervalError) {
      toast.error(intervalError);

      return;
    }

    setSaving(true);
    const res = await Network.post<DdnsRecord>("/ddns/create", {
      domain: form.domain.trim().toLowerCase(),
      intervalSeconds: Number(form.interval),
      enabled: form.enabled ? 1 : 0,
    });

    setSaving(false);
    if (res.code !== 0) {
      toast.error(res.msg || "创建失败");

      return;
    }
    // 创建时会顺带解析一次；失败原因已写进 lastError，这里提示但不当成功
    if (res.data?.lastError) {
      toast.error("已创建，但首次解析失败：" + res.data.lastError);
    } else {
      toast.success("已创建");
    }
    createDisclosure.onClose();
    setForm(emptyForm);
    load();
  };

  const openEdit = (record: DdnsRecord) => {
    if (!assertAdmin()) return;

    setEditing(record);
    setEditInterval(String(record.intervalSeconds || DEFAULT_INTERVAL_SECONDS));
    setEditEnabled(record.enabled !== 0);
    editDisclosure.onOpen();
  };

  const handleUpdate = async () => {
    if (!assertAdmin() || !editing) return;

    const intervalError = validateInterval(editInterval);

    if (intervalError) {
      toast.error(intervalError);

      return;
    }

    setSaving(true);
    // 域名创建后不可修改，后端会拒绝，因此只提交 id / 间隔 / 启停
    const res = await Network.post<DdnsRecord>("/ddns/update", {
      id: editing.id,
      intervalSeconds: Number(editInterval),
      enabled: editEnabled ? 1 : 0,
    });

    setSaving(false);
    if (res.code !== 0) {
      toast.error(res.msg || "更新失败");

      return;
    }
    replaceRecord(res.data);
    toast.success("已保存");
    editDisclosure.onClose();
    load();
  };

  const handleDelete = async () => {
    if (!assertAdmin() || !pendingDelete) return;

    setDeletingId(pendingDelete.id);
    const res = await Network.post<string>("/ddns/delete", {
      id: pendingDelete.id,
    });

    setDeletingId(null);
    if (res.code !== 0) {
      toast.error(res.msg || "删除失败");

      return;
    }
    // 后端会如实说明有多少入口节点未确认回落，原文透出，不改成「已全部清理」
    const message = typeof res.data === "string" ? res.data : "已删除";

    if (message.includes("未确认")) {
      toast(message, { icon: "⚠️", duration: 6000 });
    } else {
      toast.success(message);
    }
    deleteDisclosure.onClose();
    setPendingDelete(null);
    load();
  };

  const handleCheck = async (record: DdnsRecord) => {
    if (!assertAdmin()) return;

    setCheckingId(record.id);
    const res = await Network.post<DdnsCheckResult>("/ddns/check", {
      id: record.id,
    });

    setCheckingId(null);
    if (res.code !== 0) {
      toast.error(res.msg || "检查失败");
      load();

      return;
    }
    replaceRecord(res.data?.record);
    const summary = res.data?.message || "检查完成";

    if (res.data?.changed) {
      toast.success(
        summary + "，影响转发 " + (res.data.affectedForwards || 0) + " 条",
      );
    } else {
      toast(summary, { icon: "ℹ️" });
    }
    load();
  };

  const errorCount = records.filter((item) => item.lastError).length;
  const enabledCount = records.filter((item) => item.enabled !== 0).length;
  const now = Date.now();

  if (access !== "admin") {
    const denied = access === "denied";

    return (
      <div className="container mx-auto max-w-3xl px-3 lg:px-6 py-8">
        <Alert
          color={denied ? "warning" : "danger"}
          description={
            denied
              ? "DDNS 管理需要管理员权限；当前账号不允许查看或修改域名解析。"
              : "登录状态或角色信息不可用，请重新登录后再试。"
          }
          title={denied ? "仅管理员可访问" : "无法确认当前账号权限"}
          variant="flat"
        />
      </div>
    );
  }

  return (
    <div className="container mx-auto max-w-7xl px-3 lg:px-6 py-8">
      <div className="flex flex-wrap items-start justify-between gap-3 mb-6">
        <div>
          <h1 className="text-2xl font-bold mb-2 text-foreground">DDNS 管理</h1>
          <p className="text-default-600 text-sm">
            面板按域名解析结果，把主地址下发到引用该域名的转发入口节点。
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Button isLoading={loading} size="sm" variant="flat" onPress={load}>
            刷新
          </Button>
          <Button color="primary" size="sm" onPress={createDisclosure.onOpen}>
            新增域名
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mb-4">
        <Card className="border border-divider shadow-sm">
          <CardBody className="py-3">
            <span className="text-xs text-default-500">记录总数</span>
            <span className="text-xl font-semibold text-foreground">
              {records.length}
            </span>
          </CardBody>
        </Card>
        <Card className="border border-divider shadow-sm">
          <CardBody className="py-3">
            <span className="text-xs text-default-500">自动检查中</span>
            <span className="text-xl font-semibold text-foreground">
              {enabledCount}
            </span>
          </CardBody>
        </Card>
        <Card className="border border-divider shadow-sm">
          <CardBody className="py-3">
            <span className="text-xs text-default-500">最近检查失败</span>
            <span className="text-xl font-semibold text-danger">
              {errorCount}
            </span>
          </CardBody>
        </Card>
      </div>

      {loadError && (
        <Alert
          className="mb-4"
          color="danger"
          description={loadError}
          title="列表加载失败"
          variant="flat"
        />
      )}

      <Card className="border border-divider shadow-sm">
        <CardHeader className="pb-0">
          <div className="flex flex-col gap-1">
            <span className="text-sm font-medium text-foreground">
              域名列表
            </span>
            <span className="text-xs text-default-500">
              展示的是最近一次检查的记录；启用后按设定间隔自动检查，也可手动立即检查。
            </span>
          </div>
        </CardHeader>
        <CardBody>
          <Table
            aria-label="DDNS 域名列表"
            classNames={{
              wrapper: "shadow-none",
              th: "bg-gray-50 dark:bg-gray-800 text-gray-700 dark:text-gray-300 font-medium",
            }}
          >
            <TableHeader>
              <TableColumn>域名</TableColumn>
              <TableColumn>已保存主地址</TableColumn>
              <TableColumn>解析到的全部地址</TableColumn>
              <TableColumn>检查间隔</TableColumn>
              <TableColumn>自动检查</TableColumn>
              <TableColumn>最近检查</TableColumn>
              <TableColumn>最近变更</TableColumn>
              <TableColumn>状态</TableColumn>
              <TableColumn>操作</TableColumn>
            </TableHeader>
            <TableBody
              emptyContent={loading ? "加载中..." : "还没有配置 DDNS 域名"}
              isLoading={loading}
              items={records}
              loadingContent={<Spinner size="sm" />}
            >
              {(record) => {
                const status = deriveStatus(record, now);
                const ips = parseIpList(record.resolvedIps);

                return (
                  <TableRow key={record.id}>
                    <TableCell>
                      <span className="font-medium text-foreground">
                        {record.domain}
                      </span>
                    </TableCell>
                    <TableCell>
                      {record.primaryIp ? (
                        <span className="font-mono text-xs text-foreground">
                          {record.primaryIp}
                        </span>
                      ) : (
                        <span className="text-xs text-warning">未下发</span>
                      )}
                    </TableCell>
                    <TableCell>
                      {ips.length === 0 ? (
                        <span className="text-xs text-default-400">无</span>
                      ) : (
                        <span className="font-mono text-xs text-default-600">
                          {ips.join(" / ")}
                        </span>
                      )}
                    </TableCell>
                    <TableCell>
                      <span className="text-xs">
                        {formatInterval(record.intervalSeconds)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <Chip
                        color={record.enabled === 0 ? "default" : "primary"}
                        size="sm"
                        variant="flat"
                      >
                        {record.enabled === 0 ? "暂停" : "启用"}
                      </Chip>
                    </TableCell>
                    <TableCell>
                      <span className="text-xs">
                        {formatTimestamp(record.lastCheckTime)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <span className="text-xs">
                        {formatTimestamp(record.lastChangeTime)}
                      </span>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-col gap-1 max-w-[260px]">
                        <Chip color={status.color} size="sm" variant="flat">
                          {status.label}
                        </Chip>
                        {record.lastError ? (
                          <span
                            className="text-[11px] text-danger break-all"
                            title={status.detail}
                          >
                            {record.lastError}
                          </span>
                        ) : (
                          <span className="text-[11px] text-default-500">
                            {status.detail}
                          </span>
                        )}
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1">
                        <Button
                          color="primary"
                          isLoading={checkingId === record.id}
                          size="sm"
                          variant="flat"
                          onPress={() => handleCheck(record)}
                        >
                          立即检查
                        </Button>
                        <Button
                          size="sm"
                          variant="light"
                          onPress={() => openEdit(record)}
                        >
                          编辑
                        </Button>
                        <Button
                          color="danger"
                          size="sm"
                          variant="light"
                          onPress={() => {
                            setPendingDelete(record);
                            deleteDisclosure.onOpen();
                          }}
                        >
                          删除
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                );
              }}
            </TableBody>
          </Table>
        </CardBody>
      </Card>

      <Modal
        isOpen={createDisclosure.isOpen}
        onClose={createDisclosure.onClose}
      >
        <ModalContent>
          <ModalHeader>新增 DDNS 域名</ModalHeader>
          <ModalBody>
            <Input
              isRequired
              description="保存后不可修改；需要换域名请删除后重建"
              label="域名"
              placeholder="ddns.example.com"
              value={form.domain}
              onChange={(e) => setForm({ ...form, domain: e.target.value })}
            />
            <Input
              description="10 - 3600 秒"
              label="检查间隔（秒）"
              value={form.interval}
              onChange={(e) => setForm({ ...form, interval: e.target.value })}
            />
            <Switch
              isSelected={form.enabled}
              onValueChange={(v) => setForm({ ...form, enabled: v })}
            >
              启用自动检查
            </Switch>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={createDisclosure.onClose}>
              取消
            </Button>
            <Button color="primary" isLoading={saving} onPress={handleCreate}>
              创建
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal isOpen={editDisclosure.isOpen} onClose={editDisclosure.onClose}>
        <ModalContent>
          <ModalHeader>编辑 {editing?.domain}</ModalHeader>
          <ModalBody>
            <Input
              isReadOnly
              description="域名创建后不可修改"
              label="域名"
              value={editing?.domain || ""}
            />
            <Input
              description="10 - 3600 秒"
              label="检查间隔（秒）"
              value={editInterval}
              onChange={(e) => setEditInterval(e.target.value)}
            />
            <Switch isSelected={editEnabled} onValueChange={setEditEnabled}>
              启用自动检查
            </Switch>
            {editing?.lastError && (
              <Alert
                color="danger"
                description={editing.lastError}
                title="最近一次检查失败"
                variant="flat"
              />
            )}
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={editDisclosure.onClose}>
              取消
            </Button>
            <Button color="primary" isLoading={saving} onPress={handleUpdate}>
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      <Modal
        isOpen={deleteDisclosure.isOpen}
        onClose={deleteDisclosure.onClose}
      >
        <ModalContent>
          <ModalHeader>删除 {pendingDelete?.domain}</ModalHeader>
          <ModalBody>
            <p className="text-sm text-default-600">
              删除后节点会尽量回落到域名原文；节点离线时回落结果不会即时确认，以返回提示为准。
            </p>
          </ModalBody>
          <ModalFooter>
            <Button variant="light" onPress={deleteDisclosure.onClose}>
              取消
            </Button>
            <Button
              color="danger"
              isLoading={deletingId === pendingDelete?.id}
              onPress={handleDelete}
            >
              删除
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
