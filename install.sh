#!/bin/bash
# ==============================================================================
# Aura Panel & Agent 统一安装与管理脚本
# 项目地址: https://github.com/sheying2013/aura-panel
# 支持系统: Debian, Ubuntu, CentOS, RHEL, Rocky Linux, AlmaLinux, Alpine, macOS
# 支持架构: AMD64 (x86_64), ARM64 (aarch64)
# ==============================================================================

set -e
export LANG=en_US.UTF-8
export LC_ALL=C

INSTALL_DIR="/opt/aura-panel"
AGENT_INSTALL_DIR="/etc/aura_agent"
LEGACY_AGENT_DIR="/etc/flux_agent"
REPO_OWNER="sheying2013"
VERSION="v2.1.1"

# 颜色定义
RED="\033[31m"
GREEN="\033[32m"
YELLOW="\033[33m"
BLUE="\033[34m"
CYAN="\033[36m"
BOLD="\033[1m"
RESET="\033[0m"

info() { echo -e "${BLUE}[INFO]${RESET} $*"; }
success() { echo -e "${GREEN}[SUCCESS]${RESET} $*"; }
warn() { echo -e "${YELLOW}[WARN]${RESET} $*"; }
error() { echo -e "${RED}[ERROR]${RESET} $*"; }

# 架构检测
get_architecture() {
    local arch
    arch=$(uname -m)
    case "$arch" in
        x86_64) echo "amd64" ;;
        aarch64|arm64) echo "arm64" ;;
        *) echo "amd64" ;;
    esac
}

# 国内网络检测
check_in_china() {
    local country
    country=$(curl -s --max-time 3 https://ipinfo.io/country 2>/dev/null || echo "UNKNOWN")
    if [[ "$country" == "CN" ]]; then
        return 0
    fi
    return 1
}

# 构建 Agent 下载地址
build_agent_download_url() {
    local arch
    arch=$(get_architecture)
    local url="https://github.com/${REPO_OWNER}/aura-panel/releases/download/${VERSION}/gost-${arch}"
    if check_in_china; then
        url="https://ghfast.top/${url}"
    fi
    echo "$url"
}

# 安装 Agent 节点
install_agent() {
    echo -e "${BOLD}${CYAN}========================================================${RESET}"
    echo -e "${BOLD}${CYAN}             Aura Agent 转发节点一键安装               ${RESET}"
    echo -e "${BOLD}${CYAN}========================================================${RESET}"

    if [[ -z "$SERVER_ADDR" || -z "$SECRET" ]]; then
        echo "请输入节点对接参数："
        if [[ -z "$SERVER_ADDR" ]]; then
            read -r -p "Web 控制台服务端地址 (如 1.2.3.4:6365): " SERVER_ADDR
        fi
        if [[ -z "$SECRET" ]]; then
            read -r -p "节点对接密钥 (Secret): " SECRET
        fi
        if [[ -z "$SERVER_ADDR" || -z "$SECRET" ]]; then
            error "参数不完整，安装已取消。"
            exit 1
        fi
    fi

    mkdir -p "$AGENT_INSTALL_DIR"
    mkdir -p "$LEGACY_AGENT_DIR"

    # 停止已有服务
    if systemctl list-units --full -all 2>/dev/null | grep -Eq "aura[-_]agent|flux_agent"; then
        info "检测到既有 Agent 服务，正在停止..."
        systemctl stop aura-agent 2>/dev/null || true
        systemctl stop flux_agent 2>/dev/null || true
    fi

    local download_url
    download_url=$(build_agent_download_url)
    info "正在下载 Agent 核心程序: $download_url"

    curl -L --retry 3 "$download_url" -o "$AGENT_INSTALL_DIR/aura_agent"
    if [[ ! -f "$AGENT_INSTALL_DIR/aura_agent" || ! -s "$AGENT_INSTALL_DIR/aura_agent" ]]; then
        error "Agent 下载失败，请检查网络或发布包地址。"
        exit 1
    fi
    chmod +x "$AGENT_INSTALL_DIR/aura_agent"
    # 兼容软链接
    ln -sf "$AGENT_INSTALL_DIR/aura_agent" "$LEGACY_AGENT_DIR/flux_agent" 2>/dev/null || true

    # 写入配置文件
    cat > "$AGENT_INSTALL_DIR/config.json" <<EOF
{
  "addr": "$SERVER_ADDR",
  "secret": "$SECRET"
}
EOF
    chmod 600 "$AGENT_INSTALL_DIR/config.json"
    cp -f "$AGENT_INSTALL_DIR/config.json" "$LEGACY_AGENT_DIR/config.json" 2>/dev/null || true

    if [[ ! -f "$AGENT_INSTALL_DIR/gost.json" ]]; then
        echo "{}" > "$AGENT_INSTALL_DIR/gost.json"
        chmod 600 "$AGENT_INSTALL_DIR/gost.json"
    fi

    # 配置 systemd 服务
    local service_file="/etc/systemd/system/aura-agent.service"
    cat > "$service_file" <<EOF
[Unit]
Description=Aura Agent Forwarding Service
After=network.target

[Service]
WorkingDirectory=$AGENT_INSTALL_DIR
ExecStart=$AGENT_INSTALL_DIR/aura_agent
Restart=always
RestartSec=5
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

    # 兼容原有 flux_agent 服务名
    ln -sf "$service_file" /etc/systemd/system/flux_agent.service 2>/dev/null || true

    systemctl daemon-reload
    systemctl enable aura-agent 2>/dev/null || true
    systemctl restart aura-agent

    sleep 2
    if systemctl is-active --quiet aura-agent; then
        echo ""
        success "🎉 Aura Agent 节点安装完成并已成功接入！"
        info "对接服务器: $SERVER_ADDR"
        info "配置目录  : $AGENT_INSTALL_DIR"
        info "服务状态  : $(systemctl is-active aura-agent)"
    else
        warn "Agent 服务启动中或连接未就绪，可通过以下命令查看实时日志："
        echo "journalctl -u aura-agent -f"
    fi
}

# 转发给 panel_install.sh 处理面板安装与运维
dispatch_panel() {
    local script_dir
    script_dir="$(cd "$(dirname "$0")" && pwd)"
    if [[ -f "$script_dir/panel_install.sh" ]]; then
        exec bash "$script_dir/panel_install.sh" "$@"
    else
        # 远程单文件执行时从 GitHub 获取
        local panel_url="https://raw.githubusercontent.com/${REPO_OWNER}/aura-panel/main/panel_install.sh"
        if check_in_china; then
            panel_url="https://ghfast.top/${panel_url}"
        fi
        exec bash <(curl -fsSL "$panel_url") "$@"
    fi
}

# 命令行解析
SERVER_ADDR=""
SECRET=""

while getopts "a:s:p-:" opt; do
    case "$opt" in
        a) SERVER_ADDR="$OPTARG" ;;
        s) SECRET="$OPTARG" ;;
        p) dispatch_panel "install" ;;
        -)
            case "$OPTARG" in
                panel) dispatch_panel "install" ;;
                agent) install_agent ;;
                *) error "未知选项 --$OPTARG"; exit 1 ;;
            esac
            ;;
        *) error "无效参数"; exit 1 ;;
    esac
done

# 如果传递了 -a 或 -s，代表是通过后台命令调用的 Agent 安装
if [[ -n "$SERVER_ADDR" || -n "$SECRET" ]]; then
    install_agent
    exit 0
fi

# 如果未传递参数且为交互环境，提供清晰的选择菜单
if [[ -t 0 ]]; then
    echo -e "${BOLD}${CYAN}========================================================${RESET}"
    echo -e "${BOLD}${CYAN}            Aura Panel 全功能统一安装管理               ${RESET}"
    echo -e "${BOLD}${CYAN}   分布式端口转发与多跳隧道中继管理系统 | v2.1.1        ${RESET}"
    echo -e "${BOLD}${CYAN}========================================================${RESET}"
    echo -e " 1. 安装 Aura Panel Web 管理控制台 (Docker 傻瓜式一键安装)"
    echo -e " 2. 安装 / 对接 Agent 转发节点 (需先有面板并提供密钥)"
    echo -e " 3. 管理已安装的 Aura Panel 面板 (启动/停止/日志/升级/卸载)"
    echo -e " 0. 退出"
    echo -e "${BOLD}${CYAN}========================================================${RESET}"
    read -r -p "请选择安装组件 [1-3, 默认 1]: " menu_choice
    menu_choice=${menu_choice:-1}
    case "$menu_choice" in
        1) dispatch_panel "install" ;;
        2) install_agent ;;
        3) dispatch_panel ;;
        0) exit 0 ;;
        *) error "无效选项"; exit 1 ;;
    esac
else
    # 管道模式 (curl ... | bash) 默认执行面板一键安装
    dispatch_panel "install"
fi
