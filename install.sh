#!/data/data/com.termux/files/usr/bin/bash
# GitMob - Termux 一键安装脚本
# 用法: bash install.sh

set -e

CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
RESET='\033[0m'

echo ""
echo -e "${CYAN}  ██████╗ ██╗████████╗███╗   ███╗ ██████╗ ██████╗ ${RESET}"
echo -e "${CYAN}  ██╔════╝ ██║╚══██╔══╝████╗ ████║██╔═══██╗██╔══██╗${RESET}"
echo -e "${CYAN}  ██║  ███╗██║   ██║   ██╔████╔██║██║   ██║██████╔╝${RESET}"
echo -e "${CYAN}  ██║   ██║██║   ██║   ██║╚██╔╝██║██║   ██║██╔══██╗${RESET}"
echo -e "${CYAN}  ╚██████╔╝██║   ██║   ██║ ╚═╝ ██║╚██████╔╝██████╔╝${RESET}"
echo -e "${CYAN}   ╚═════╝ ╚═╝   ╚═╝   ╚═╝     ╚═╝ ╚═════╝ ╚═════╝ ${RESET}"
echo ""
echo -e "${CYAN}  Mobile Git Manager for Termux${RESET}"
echo ""

step() { echo -e "${YELLOW}▶ $1${RESET}"; }
ok()   { echo -e "${GREEN}✓ $1${RESET}"; }
err()  { echo -e "${RED}✗ $1${RESET}"; exit 1; }

# 1. 检查 Termux 环境
step "检查环境..."
if [ -z "$TERMUX_VERSION" ] && [ ! -d "/data/data/com.termux" ]; then
  echo -e "${YELLOW}⚠ 未检测到 Termux 环境，继续可能失败${RESET}"
fi
ok "环境检查完成"

# 2. 更新包列表
step "更新包列表..."
pkg update -y -q 2>/dev/null || true
ok "包列表已更新"

# 3. 安装依赖
step "安装 Node.js 和 Git..."
pkg install -y nodejs git openssh 2>/dev/null
ok "依赖安装完成 (node: $(node -v), git: $(git --version | head -1))"

# 4. 安装 npm 依赖
step "安装项目依赖..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
npm install --prefer-offline 2>&1 | tail -3
ok "npm 依赖安装完成"

# 5. 检查 SSH Key
step "检查 SSH 配置..."
SSH_DIR="$HOME/.ssh"
if [ ! -d "$SSH_DIR" ]; then
  mkdir -p "$SSH_DIR"
  chmod 700 "$SSH_DIR"
fi

HAS_KEY=false
if ls "$SSH_DIR"/*.pub 2>/dev/null | grep -q .; then
  HAS_KEY=true
  ok "检测到已有 SSH Key"
else
  echo -e "${YELLOW}  未发现 SSH Key，可在 GitMob 界面中生成${RESET}"
fi

# 6. 检查 git 全局配置
step "检查 Git 全局配置..."
GIT_NAME=$(git config --global user.name 2>/dev/null || echo "")
GIT_EMAIL=$(git config --global user.email 2>/dev/null || echo "")

if [ -z "$GIT_NAME" ]; then
  read -p "  输入 Git 用户名: " GIT_NAME_INPUT
  if [ -n "$GIT_NAME_INPUT" ]; then
    git config --global user.name "$GIT_NAME_INPUT"
    ok "Git 用户名已设置: $GIT_NAME_INPUT"
  fi
else
  ok "Git 用户名: $GIT_NAME"
fi

if [ -z "$GIT_EMAIL" ]; then
  read -p "  输入 Git 邮箱: " GIT_EMAIL_INPUT
  if [ -n "$GIT_EMAIL_INPUT" ]; then
    git config --global user.email "$GIT_EMAIL_INPUT"
    ok "Git 邮箱已设置: $GIT_EMAIL_INPUT"
  fi
else
  ok "Git 邮箱: $GIT_EMAIL"
fi

# 7. 创建启动脚本
step "创建快捷启动命令..."
BIN_DIR="$HOME/.local/bin"
mkdir -p "$BIN_DIR"

# 获取当前项目所在目录的绝对路径
# 这样即使关闭 Termux 再进入，gitmob 也能找到正确位置
INSTALL_DIR=$(pwd)

cat > "$BIN_DIR/gitmob" << SCRIPT
#!/data/data/com.termux/files/usr/bin/bash
# 自动生成的启动脚本
cd "$INSTALL_DIR"
node server/index.js
SCRIPT

chmod +x "$BIN_DIR/gitmob"

# 确保 PATH 包含 ~/.local/bin (同时适配 bash 和 zsh)
for rc_file in "$HOME/.bashrc" "$HOME/.zshrc"; do
  if [ -f "$rc_file" ]; then
    if ! grep -q "$BIN_DIR" "$rc_file"; then
      echo "export PATH=\"\$HOME/.local/bin:\$PATH\"" >> "$rc_file"
      echo "已更新 $rc_file"
    fi
  fi
done

# 提示用户手动刷新或重启
ok "已创建 'gitmob' 启动命令"
echo "提示：请执行 'source ~/.bashrc' 或重启 Termux 使命令生效。"


# 8. 完成
echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo -e "${GREEN}  安装完成！${RESET}"
echo ""
echo -e "  启动命令:  ${CYAN}gitmob${RESET}  或  ${CYAN}npm start${RESET}"
echo -e "  访问地址:  ${CYAN}http://localhost:5493${RESET}"
echo ""
echo -e "  第一次使用："
echo -e "  1. 运行 ${CYAN}gitmob${RESET} 启动服务"
echo -e "  2. 在手机浏览器打开 ${CYAN}http://localhost:5493${RESET}"
echo -e "  3. 在 SSH 管理页面生成/配置密钥"
echo -e "  4. 添加本地仓库路径开始使用"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RESET}"
echo ""
