# GitMob — Mobile Git Manager for Termux | Android Git 可视化操作工具

**在手机上用浏览器可视化管理本地 Git 仓库，支持推送、拉取、提交、SSH 管理，零 Root 需求。**

## 特性

- 🚀 **推送 / 强制推送** — 一键 `push` 或 `push --force`
- ⬇️ **拉取 / 强制拉取** — `pull --rebase` 或 `reset --hard` 覆盖本地
- 📝 **可视化提交** — 查看 diff、编辑提交信息、一键 `stage all + commit`
- 🔑 **SSH 全流程管理** — 生成 Ed25519 Key、复制公钥、测试 GitHub 连接、写入 `~/.ssh/config`
- 📂 **仓库初始化推送** — 对空目录执行 `git init` + 设置 remote + 首次 `push -u`
- 📋 **提交历史 & Diff** — 查看 git log 与文件级变更高亮
- 📱 **PWA 支持** — 添加到手机桌面，全屏运行
- 🌐 **纯 localhost** — 数据不出设备，私有安全

## 安装

```bash
# 在 Termux 中执行
git clone https://github.com/yourname/gitmob.git
cd gitmob
bash install.sh
```

## 使用

```bash
# 启动服务
gitmob

# 在手机浏览器访问
# http://localhost:3000
```

## 技术栈

- **运行时**: Node.js (Termux)
- **后端**: Hono + @hono/node-server
- **Git 操作**: simple-git
- **前端**: Vanilla JS + CSS (无构建步骤)
- **PWA**: Service Worker + Web Manifest

## 目录结构

```
gitmob/
├── server/
│   ├── index.js          # 服务入口 (Hono)
│   ├── routes/
│   │   ├── repos.js      # 仓库 CRUD
│   │   ├── git.js        # push/pull/commit/log/diff
│   │   └── ssh.js        # SSH 密钥管理
│   └── lib/
│       ├── git-ops.js    # simple-git 封装
│       └── ssh-manager.js
├── public/
│   ├── index.html        # 单页 PWA 应用
│   ├── manifest.json
│   └── sw.js
└── install.sh            # Termux 一键安装
```

---
