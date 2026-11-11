<div align="center">

# Aura Panel

Distributed Port Forwarding & Multi-Hop Relay Tunnel Management Panel

[![GitHub Release](https://img.shields.io/github/v/release/sheying2013/aura-panel?style=flat-square)](https://github.com/sheying2013/aura-panel/releases)
[![License](https://img.shields.io/badge/license-MIT-blue.svg?style=flat-square)](LICENSE)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?style=flat-square&logo=docker&logoColor=white)](#quick-start)

[English](./README_EN.md) · [简体中文](./README.md)

</div>

---

## Overview

**Aura Panel** is a distributed port forwarding and multi-hop relay tunnel management platform. It supports TCP/UDP traffic forwarding, multi-hop relay chains, single-port multiplexing, real-time traffic statistics, rate limiting, and a clean Web-based management console.

Designed for managing multi-server traffic relay, port mapping, and network topology orchestration.

---

## Features

- **Protocols**: Supports TCP and UDP port forwarding, with simultaneous TCP+UDP dual-stack listening on the same port.
- **Multi-Hop Relay**: Flexible multi-stage relay chains (`Ingress -> Relay -> Egress`) with encrypted transport.
- **Port Multiplexing**: Single-port multiplexing to optimize public IP/port utilization.
- **Node Management**: Agent connectivity and an administrator dashboard health summary. The control channel is probed every 12 hours by default; results older than 24 hours are shown as unknown. This does not verify forwarding traffic.
- **Traffic Accounting & Rate Limiting**: Real-time ingress/egress bandwidth and volume tracking with user and tunnel quota enforcement.
- **Dynamic DNS (DDNS)**: Administrators can manage target domains and apply address changes to the affected ingress nodes. Unconfirmed updates or rollbacks are reported; listener recreation can interrupt traffic.
- **Target Selection**: Round-robin, random, and ordered selection strategies.
- **Access Control**: IP CIDR black/whitelisting and port filtering.
- **Rule Orchestration & Backup**: Batch port range mapping, rule toggling, and configuration backup/export (JSON, TXT batch, and CSV formats).
- **Web Console**: Management interface for nodes, tunnels, forwarding rules, and user access control.

---

## Quick Start

### One-Click Docker Installation (Recommended)

Run the following command to automatically detect environment, set up Docker, and launch Aura Panel:

```bash
curl -fsSL https://raw.githubusercontent.com/sheying2013/aura-panel/main/install.sh | bash
```

After installation, use the CLI shortcut to manage the service:
```bash
aura
```

---

### Manual Docker Compose

1. Create a directory and configure `docker-compose.yml`:

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

2. Start the services:

```bash
docker compose up -d
```

3. Open the Web Console:
- URL: `http://<SERVER_IP>:6366`
- Default Username: `admin`
- Default Password: `admin`

> Please change the default password after your first login.

Node health checks are scheduled independently of page refreshes. An older agent may rewrite `gost.json` when receiving a probe; an agent containing the probe fix no longer performs that write.

---

## License

This project is licensed under the [MIT License](LICENSE).
