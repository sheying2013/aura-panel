<div align="center">

# Aura Panel

分布式端口转发与多跳隧道中继管理系统

[![GitHub Release](https://img.shields.io/github/v/release/sheying2013/aura-panel?style=flat-square)](https://github.com/sheying2013/aura-panel/releases)
[![License](https://img.shields.io/badge/license-MIT-blue.svg?style=flat-square)](LICENSE)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?style=flat-square&logo=docker&logoColor=white)](#快速开始)

[English](./README_EN.md) · [简体中文](./README.md)

</div>

---

## 项目简介

**Aura Panel** 是一套分布式端口转发与隧道中继管理系统。支持 TCP/UDP 流量转发、多跳链式中继与单端口复用，提供节点状态监控、流量统计、带宽限速与 Web 管理面板。

适用于多服务器流量中继编排、公网端口映射与链路管理。

---

## 功能特性

- **协议转发**：支持 TCP 与 UDP 端口转发，支持同端口 TCP+UDP 监听与单端口复用。
- **多跳中继**：支持自由编排多节点链式隧道（入口 -> 中继 -> 出口），支持链路端到端加密传输。
- **节点管理**：分布式 Agent 节点接入，支持实时心跳、在线状态检测与故障自动下线。
- **流量统计与限速**：实时统计节点与隧道上行/下行流量，支持按用户和隧道配置带宽限额。
- **动态解析 (DDNS)**：支持目标域名动态解析与 IP 变更加载。
- **负载均衡与主备**：支持加权轮询 (SWRR)、活跃连接调度与主备故障自动切换。
- **安全与访问控制**：支持 IP CIDR 黑白名单与敏感端口过滤。
- **规则编排与备份**：支持端口段批量映射生成、规则一键启停，支持标准 JSON 备份、通用 TXT 批量格式与 CSV 资产导出。
- **Web 控制台**：提供直观的 Web 管理界面，管理节点、隧道、转发规则与系统用户。

---

## 快速开始

### Docker 一键傻瓜式安装 (推荐)

运行以下命令，即可全自动检测环境、配置 Docker 并拉取启动 Aura Panel 控制台：

```bash
# 境外服务器 / 官方直连
curl -fsSL https://raw.githubusercontent.com/sheying2013/aura-panel/main/install.sh | bash

# 大陆境内服务器加速
curl -fsSL https://ghfast.top/https://raw.githubusercontent.com/sheying2013/aura-panel/main/install.sh | bash
```

安装完成后，可通过控制台快捷运维命令管理面板状态与服务：
```bash
aura
```

---

### Docker Compose 手动部署

1. 创建目录并保存 `docker-compose.yml`：

```yaml
services:
  backend:
    image: ghcr.io/sheying2013/aura-panel-backend:latest
    container_name: aura-backend
    environment:
      DB_PATH: /app/data/gost.db
      LOG_DIR: /app/logs
      JWT_SECRET: ${JWT_SECRET:-aura_secret_key_change_me}
      APP_CORS_ALLOWED_ORIGIN_PATTERNS: "*"
    volumes:
      - aura-data:/app/data
      - aura-logs:/app/logs
    networks:
      - aura-net
    ports:
      - "6365:6365"
    restart: unless-stopped

  frontend:
    image: ghcr.io/sheying2013/aura-panel-frontend:latest
    container_name: aura-frontend
    ports:
      - "6366:80"
    depends_on:
      - backend
    networks:
      - aura-net
    restart: unless-stopped

volumes:
  aura-data:
  aura-logs:

networks:
  aura-net:
    driver: bridge
```

2. 启动服务：

```bash
docker compose up -d
```

3. 访问 Web 管理面板：
- 地址：`http://<服务器IP>:6366`
- 默认账号：`admin`
- 默认密码：`admin`

> 首次登录后请前往个人中心修改初始密码。

---

## 节点 Agent 接入

在需要作为转发或中继节点的服务器上运行 Agent：

```bash
# 启动 gost 转发节点并连接至主控
./gost-agent -L :<中继端口>
```

亦可在 Web 管理面板的「节点管理」页面直接获取对应系统的一键接入命令。

---

## 开源协议

本项目采用 [MIT License](LICENSE) 许可协议。
