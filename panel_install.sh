#!/bin/bash
# ==============================================================================
# Aura Panel - Docker 一键安装与运维管理脚本
# 项目地址: https://github.com/sheying2013/aura-panel
# 支持系统: Debian, Ubuntu, CentOS, RHEL, Rocky Linux, AlmaLinux, Alpine, macOS
# 支持架构: AMD64 (x86_64), ARM64 (aarch64)
# ==============================================================================

set -e
export LANG=en_US.UTF-8
export LC_ALL=C

INSTALL_DIR="/opt/aura-panel"
REPO_OWNER="sheying2013"
IMAGE_BACKEND="ghcr.io/${REPO_OWNER}/aura-panel-backend:latest"
IMAGE_FRONTEND="ghcr.io/${REPO_OWNER}/aura-panel-frontend:latest"
GITHUB_RAW="https://raw.githubusercontent.com/${REPO_OWNER}/aura-panel/main"

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

# 权限检查
check_root() {
    if [[ $EUID -ne 0 ]]; then
        error "此脚本必须以 root 权限运行。请使用 sudo bash $0 或切换至 root 用户。"
        exit 1
    fi
}

# 架构检测
get_arch() {
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

# 获取公网 IP
get_public_ip() {
    local ip
    ip=$(curl -s4 --max-time 3 https://api.ipify.org 2>/dev/null || \
        curl -s4 --max-time 3 https://ip.sb 2>/dev/null || \
        curl -s4 --max-time 3 https://ifconfig.me 2>/dev/null || \
        hostname -I 2>/dev/null | awk '{print $1}' || \
        echo "127.0.0.1")
    echo "$ip"
}

# 生成随机安全字符串
generate_secret() {
    LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c32
}

# 检查端口占用
check_port() {
    local port=$1
    if command -v ss &>/dev/null; then
        ss -tuln | grep -q ":$port "
    elif command -v netstat &>/dev/null; then
        netstat -tuln | grep -q ":$port "
    elif command -v lsof &>/dev/null; then
        lsof -i :"$port" &>/dev/null
    else
        return 1
    fi
}

# 检测并安装 Docker
check_and_install_docker() {
    if command -v docker &>/dev/null; then
        if docker compose version &>/dev/null; then
            DOCKER_CMD="docker compose"
            success "检测到 Docker 环境正常: $(docker --version) ($(docker compose version))"
            return 0
        elif command -v docker-compose &>/dev/null; then
            DOCKER_CMD="docker-compose"
            success "检测到 Docker 及 docker-compose 正常: $(docker --version)"
            return 0
        fi
    fi

    warn "未检测到完整的 Docker / Docker Compose 环境。"
    read -r -p "是否立即自动安装官方 Docker 环境？[Y/n]: " install_confirm
    install_confirm=${install_confirm:-Y}
    if [[ "$install_confirm" =~ ^[Yy]$ ]]; then
        info "正在自动安装 Docker..."
        if check_in_china; then
            info "检测到境内网络，使用阿里云加速镜像安装 Docker..."
            curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
        else
            curl -fsSL https://get.docker.com | bash
        fi
        systemctl enable --now docker 2>/dev/null || true
        
        # 验证 docker compose
        if docker compose version &>/dev/null; then
            DOCKER_CMD="docker compose"
        elif command -v docker-compose &>/dev/null; then
            DOCKER_CMD="docker-compose"
        else
            info "安装 docker-compose-plugin..."
            if command -v apt &>/dev/null; then
                apt update && apt install -y docker-compose-plugin
            elif command -v yum &>/dev/null; then
                yum install -y docker-compose-plugin
            elif command -v dnf &>/dev/null; then
                dnf install -y docker-compose-plugin
            fi
            DOCKER_CMD="docker compose"
        fi
        success "Docker 环境自动安装完成！"
    else
        error "缺少 Docker 环境，安装已中止。请手动安装 Docker 后再运行此脚本。"
        exit 1
    fi
}

# 检测系统是否支持 IPv6
check_ipv6_support() {
    if ip -6 addr show 2>/dev/null | grep -v "scope link" | grep -q "inet6"; then
        return 0
    elif ifconfig 2>/dev/null | grep -v "fe80:" | grep -q "inet6"; then
        return 0
    fi
    return 1
}

# 配置 Docker IPv6 支持
configure_docker_ipv6() {
    local docker_config="/etc/docker/daemon.json"
    if [[ "$(uname -s)" == "Darwin" ]]; then
        return 0
    fi
    mkdir -p /etc/docker
    if [[ -f "$docker_config" ]]; then
        if grep -q '"ipv6"' "$docker_config"; then
            return 0
        fi
        if command -v jq &>/dev/null; then
            jq '. + {"ipv6": true, "fixed-cidr-v6": "fd00::/80"}' "$docker_config" > /tmp/daemon.json && mv /tmp/daemon.json "$docker_config"
        else
            sed -i 's/^{$/{\n  "ipv6": true,\n  "fixed-cidr-v6": "fd00::\/80",/' "$docker_config"
        fi
    else
        cat > "$docker_config" <<EOF
{
  "ipv6": true,
  "fixed-cidr-v6": "fd00::/80"
}
EOF
    fi
    systemctl restart docker 2>/dev/null || true
    sleep 3
}

# 生成 docker-compose.yml
generate_compose_file() {
    local compose_path="$INSTALL_DIR/docker-compose.yml"
    local use_ipv6=$1

    if [[ "$use_ipv6" == "true" ]]; then
        cat > "$compose_path" <<EOF
services:
  backend:
    image: ${IMAGE_BACKEND}
    container_name: aura-backend
    restart: unless-stopped
    environment:
      DB_PATH: /app/data/gost.db
      JWT_SECRET: ${JWT_SECRET}
      LOG_DIR: /app/logs
      APP_CORS_ALLOWED_ORIGIN_PATTERNS: "*"
      JAVA_OPTS: "-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
    ports:
      - "${BACKEND_PORT}:6365"
    volumes:
      - ./logs:/app/logs
      - ./data:/app/data
    networks:
      - aura-network
    stop_grace_period: 30s
    stop_signal: SIGTERM
    healthcheck:
      test: ["CMD", "sh", "-c", "wget --no-verbose --tries=1 --spider http://localhost:6365/flow/test || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 30s

  frontend:
    image: ${IMAGE_FRONTEND}
    container_name: aura-frontend
    restart: unless-stopped
    ports:
      - "${FRONTEND_PORT}:80"
    depends_on:
      backend:
        condition: service_healthy
    networks:
      - aura-network

networks:
  aura-network:
    name: aura-network
    driver: bridge
    enable_ipv6: true
    ipam:
      config:
        - subnet: 172.20.0.0/16
        - subnet: fd00:dead:beef::/48
EOF
    else
        cat > "$compose_path" <<EOF
services:
  backend:
    image: ${IMAGE_BACKEND}
    container_name: aura-backend
    restart: unless-stopped
    environment:
      DB_PATH: /app/data/gost.db
      JWT_SECRET: ${JWT_SECRET}
      LOG_DIR: /app/logs
      APP_CORS_ALLOWED_ORIGIN_PATTERNS: "*"
      JAVA_OPTS: "-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
    ports:
      - "${BACKEND_PORT}:6365"
    volumes:
      - ./logs:/app/logs
      - ./data:/app/data
    networks:
      - aura-network
    stop_grace_period: 30s
    stop_signal: SIGTERM
    healthcheck:
      test: ["CMD", "sh", "-c", "wget --no-verbose --tries=1 --spider http://localhost:6365/flow/test || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 30s

  frontend:
    image: ${IMAGE_FRONTEND}
    container_name: aura-frontend
    restart: unless-stopped
    ports:
      - "${FRONTEND_PORT}:80"
    depends_on:
      backend:
        condition: service_healthy
    networks:
      - aura-network

networks:
  aura-network:
    name: aura-network
    driver: bridge
    ipam:
      config:
        - subnet: 172.20.0.0/16
EOF
    fi
}

# 创建快捷管理命令 aura
install_cli_shortcut() {
    local cli_path="/usr/local/bin/aura"
    cat > "$cli_path" <<EOF
#!/bin/bash
exec bash "$INSTALL_DIR/panel_install.sh" "\$@"
EOF
    chmod +x "$cli_path"
}

# 安装面板
install_panel() {
    echo -e "${BOLD}${CYAN}========================================================${RESET}"
    echo -e "${BOLD}${CYAN}        Aura Panel - Docker 傻瓜式一键快速安装        ${RESET}"
    echo -e "${BOLD}${CYAN}========================================================${RESET}"

    check_and_install_docker

    mkdir -p "$INSTALL_DIR" "$INSTALL_DIR/data" "$INSTALL_DIR/logs"
    cd "$INSTALL_DIR"

    # 获取前端端口
    while true; do
        read -r -p "请输入 Web 控制台前端访问端口 [默认: 6366]: " input_frontend_port
        FRONTEND_PORT=${input_frontend_port:-6366}
        if [[ ! "$FRONTEND_PORT" =~ ^[0-9]+$ ]] || [ "$FRONTEND_PORT" -lt 1 ] || [ "$FRONTEND_PORT" -gt 65535 ]; then
            warn "端口号不合法，请输入 1-65535 之间的整数。"
            continue
        fi
        if check_port "$FRONTEND_PORT"; then
            warn "端口 $FRONTEND_PORT 已被其他进程占用，请重新输入可用端口！"
        else
            break
        fi
    done

    # 获取后端端口
    while true; do
        read -r -p "请输入 后端 API 通信端口 [默认: 6365]: " input_backend_port
        BACKEND_PORT=${input_backend_port:-6365}
        if [[ ! "$BACKEND_PORT" =~ ^[0-9]+$ ]] || [ "$BACKEND_PORT" -lt 1 ] || [ "$BACKEND_PORT" -gt 65535 ]; then
            warn "端口号不合法，请输入 1-65535 之间的整数。"
            continue
        fi
        if [ "$BACKEND_PORT" -eq "$FRONTEND_PORT" ]; then
            warn "后端端口不能与前端端口相同，请重新输入！"
            continue
        fi
        if check_port "$BACKEND_PORT"; then
            warn "端口 $BACKEND_PORT 已被其他进程占用，请重新输入可用端口！"
        else
            break
        fi
    done

    JWT_SECRET=$(generate_secret)

    # 镜像拉取加速检测
    local final_backend="$IMAGE_BACKEND"
    local final_frontend="$IMAGE_FRONTEND"
    if check_in_china; then
        info "检测到位于境内网络，支持配置 DaoCloud 镜像加速代理以提升拉取速度。"
        read -r -p "是否使用 ghcr.m.daocloud.io 镜像加速？[Y/n]: " mirror_confirm
        mirror_confirm=${mirror_confirm:-Y}
        if [[ "$mirror_confirm" =~ ^[Yy]$ ]]; then
            final_backend="ghcr.m.daocloud.io/${REPO_OWNER}/aura-panel-backend:latest"
            final_frontend="ghcr.m.daocloud.io/${REPO_OWNER}/aura-panel-frontend:latest"
        fi
    fi

    # 写入 .env 文件
    cat > "$INSTALL_DIR/.env" <<EOF
IMAGE_BACKEND=$final_backend
IMAGE_FRONTEND=$final_frontend
FRONTEND_PORT=$FRONTEND_PORT
BACKEND_PORT=$BACKEND_PORT
JWT_SECRET=$JWT_SECRET
EOF

    # IPv6 检测与生成 Compose 文件
    local use_ipv6="false"
    if check_ipv6_support; then
        info "检测到系统支持 IPv6，正在自动启用 IPv6 网络配置..."
        configure_docker_ipv6
        use_ipv6="true"
    fi

    info "正在生成 Docker Compose 编排文件..."
    generate_compose_file "$use_ipv6"

    # 复制自身到安装目录供后续 aura 运维复用
    cp -f "$0" "$INSTALL_DIR/panel_install.sh" 2>/dev/null || true
    chmod +x "$INSTALL_DIR/panel_install.sh"
    install_cli_shortcut

    info "正在拉取容器镜像并启动服务 (首次拉取需稍候)..."
    $DOCKER_CMD pull
    $DOCKER_CMD up -d

    info "正在等待后端服务完成启动与数据库就绪..."
    local healthy=false
    for i in {1..40}; do
        local status
        status=$(docker inspect -f '{{.State.Health.Status}}' aura-backend 2>/dev/null || echo "starting")
        if [[ "$status" == "healthy" ]]; then
            healthy=true
            break
        fi
        sleep 2
        echo -n "."
    done
    echo ""

    local server_ip
    server_ip=$(get_public_ip)

    echo ""
    echo -e "${BOLD}${GREEN}========================================================${RESET}"
    echo -e "${BOLD}${GREEN}              🎉 Aura Panel 部署成功！                 ${RESET}"
    echo -e "${BOLD}${GREEN}========================================================${RESET}"
    echo -e "${BOLD}🌐 Web 控制台访问地址 : ${CYAN}http://${server_ip}:${FRONTEND_PORT}${RESET}"
    echo -e "${BOLD}🔑 默认管理员账号     : ${YELLOW}admin${RESET}"
    echo -e "${BOLD}🔒 默认管理员密码     : ${YELLOW}admin${RESET}"
    echo -e "${BOLD}⚠️  安全提醒           : 首次登录后请立即在「个人中心」修改初始密码！"
    echo ""
    echo -e "${BOLD}🛠️ 运维管理快捷命令   : ${CYAN}aura${RESET}  (随时在终端输入 aura 查看菜单)"
    echo -e "${BOLD}📂 数据持久化目录     : ${INSTALL_DIR}/data (数据库 gost.db)"
    echo -e "${BOLD}📝 运行日志存储目录   : ${INSTALL_DIR}/logs"
    echo -e "${BOLD}${GREEN}========================================================${RESET}"
}

# 启动面板
start_panel() {
    cd "$INSTALL_DIR"
    check_and_install_docker
    info "正在启动 Aura Panel 服务..."
    $DOCKER_CMD up -d
    success "服务已启动！"
}

# 停止面板
stop_panel() {
    cd "$INSTALL_DIR"
    check_and_install_docker
    info "正在停止 Aura Panel 服务..."
    $DOCKER_CMD down
    success "服务已安全停止！"
}

# 重启面板
restart_panel() {
    cd "$INSTALL_DIR"
    check_and_install_docker
    info "正在重启 Aura Panel 服务..."
    $DOCKER_CMD restart
    success "服务已完成重启！"
}

# 查看状态
status_panel() {
    cd "$INSTALL_DIR"
    check_and_install_docker
    info "当前容器运行状态："
    $DOCKER_CMD ps
}

# 查看日志
logs_panel() {
    cd "$INSTALL_DIR"
    check_and_install_docker
    echo "1. 查看后端日志 (Backend)"
    echo "2. 查看前端日志 (Frontend)"
    echo "3. 查看全部日志"
    read -r -p "请选择日志服务 [1-3]: " log_choice
    case "$log_choice" in
        1) $DOCKER_CMD logs -f --tail=100 backend ;;
        2) $DOCKER_CMD logs -f --tail=100 frontend ;;
        *) $DOCKER_CMD logs -f --tail=100 ;;
    esac
}

# 更新面板
update_panel() {
    cd "$INSTALL_DIR"
    check_and_install_docker
    info "正在检查并拉取最新镜像..."
    $DOCKER_CMD pull
    info "正在重启服务应用最新更新..."
    $DOCKER_CMD up -d
    success "Aura Panel 已成功升级到最新版本！"
}

# 重置密码
reset_password() {
    local db_file="$INSTALL_DIR/data/gost.db"
    local changed
    if [[ ! -f "$db_file" ]]; then
        error "未找到数据库文件 ${db_file}，请确认服务已启动。"
        return 1
    fi
    if ! command -v sqlite3 >/dev/null 2>&1; then
        error "系统未安装 sqlite3，请先安装后重试。"
        return 1
    fi
    # pwd 保存 MD5；递增 token_version，使重置前的登录令牌失效。
    # 仅修改初始管理员，避免误改其他管理员或普通用户。
    if ! changed=$(sqlite3 -bail -cmd '.timeout 5000' "$db_file"         "BEGIN IMMEDIATE; UPDATE user SET pwd = '21232f297a57a5a743894a0e4a801fc3', token_version = COALESCE(token_version, 0) + 1 WHERE id = 1 AND role_id = 0; SELECT changes(); COMMIT;"); then
        error "密码重置失败，数据库未完成更新。"
        return 1
    fi
    if [[ "$changed" != "1" ]]; then
        error "未找到 ID 为 1 的管理员，密码未重置。"
        return 1
    fi
    success "初始管理员密码已重置为 admin，请立即登录并修改密码。"
}

# 彻底卸载面板
uninstall_panel() {
    cd "$INSTALL_DIR" 2>/dev/null || true
    check_and_install_docker
    echo -e "${RED}${BOLD}⚠️  警告: 此操作将停止并删除 Aura Panel 所有容器、镜像以及数据库配置！${RESET}"
    read -r -p "确认彻底卸载 Aura Panel 吗？[y/N]: " confirm
    if [[ "$confirm" =~ ^[Yy]$ ]]; then
        info "正在停止并清理容器网络..."
        $DOCKER_CMD down -v --remove-orphans 2>/dev/null || true
        rm -rf "$INSTALL_DIR"
        rm -f /usr/local/bin/aura
        success "Aura Panel 已经彻底卸载完毕。"
    else
        info "已取消卸载操作。"
    fi
}

# 主菜单交互
show_menu() {
    clear
    echo -e "${BOLD}${CYAN}========================================================${RESET}"
    echo -e "${BOLD}${CYAN}             Aura Panel 运维管理控制台                  ${RESET}"
    echo -e "${BOLD}${CYAN}   分布式端口转发与多跳隧道中继管理系统 | v2.1.0        ${RESET}"
    echo -e "${BOLD}${CYAN}========================================================${RESET}"
    echo -e " 1. 安装 Aura Panel (Docker 傻瓜式一键安装)"
    echo -e " 2. 启动服务 (Start)"
    echo -e " 3. 停止服务 (Stop)"
    echo -e " 4. 重启服务 (Restart)"
    echo -e " 5. 查看运行状态 (Status)"
    echo -e " 6. 查看实时日志 (Logs)"
    echo -e " 7. 一键平滑升级 (Upgrade)"
    echo -e " 8. 重置管理员密码 (Reset Password)"
    echo -e " 9. 彻底卸载面板 (Uninstall)"
    echo -e " 0. 退出管理控制台"
    echo -e "${BOLD}${CYAN}========================================================${RESET}"
    read -r -p "请输入选项 [0-9]: " choice
    case "$choice" in
        1) install_panel ;;
        2) start_panel ;;
        3) stop_panel ;;
        4) restart_panel ;;
        5) status_panel ;;
        6) logs_panel ;;
        7) update_panel ;;
        8) reset_password ;;
        9) uninstall_panel ;;
        0) exit 0 ;;
        *) error "无效选项，请重新输入"; sleep 1; show_menu ;;
    esac
}

# 命令行入口
check_root

case "$1" in
    install|--install)
        install_panel
        ;;
    start)
        start_panel
        ;;
    stop)
        stop_panel
        ;;
    restart)
        restart_panel
        ;;
    status)
        status_panel
        ;;
    logs)
        logs_panel
        ;;
    update|upgrade)
        update_panel
        ;;
    uninstall)
        uninstall_panel
        ;;
    *)
        if [[ ! -f "$INSTALL_DIR/docker-compose.yml" ]]; then
            # 尚未安装，直接进入傻瓜式安装流程
            install_panel
        else
            show_menu
        fi
        ;;
esac
