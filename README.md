# GitMob

**Android 原生 GitHub 客户端 · 完整仓库管理 · 本地 Git 操作 · 零依赖纯 Java 实现**

手机端 GitHub 管理工具，基于 OAuth 2.0 安全认证，支持远程仓库完整管理与本地 JGit 操作，无需 Root 或外部 git 二进制文件。

[![Build APK](https://github.com/xiaobaiweinuli/GitMob-Android/actions/workflows/build.yml/badge.svg)](https://github.com/xiaobaiweinuli/GitMob-Android/actions/workflows/build.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-2026.03-4285F4?logo=android)](https://developer.android.com/jetpack/compose)
[![Material3](https://img.shields.io/badge/Material_3-Dynamic-6750A4?logo=android)](https://m3.material.io)
[![License](https://img.shields.io/badge/License-MIT-green)](LICENSE)

## 核心特性

### 远程仓库管理
- **OAuth 2.0 安全认证** — Cloudflare Worker 中转，client_secret 永不暴露在客户端
- **多账号管理** — 支持账号切换与新增，DataStore 持久化存储
- **仓库操作** — 搜索、筛选（公开/私有）、Star/Unstar、语言标签
- **文件管理** — 文件树浏览、在线编辑/删除、提交、历史记录与 diff 对比
- **提交历史** — 完整 commit 列表、逐文件 diff、支持 revert
- **分支管理** — 创建、切换、删除、重命名、设置默认分支
- **Pull Requests** — PR 列表、分支信息展示
- **Issues** — 多维度筛选（状态/标签/作者/排序）、创建 Issue（支持 YAML Form 模板）
- **GitHub Actions** — Workflow 列表、手动触发、运行日志、产物下载
- **Releases** — 发行版管理、Asset 下载（带进度通知，自动处理重定向）
- **仓库订阅** — Watch/Unwatch，支持 Ignore/Participating/Releases 粒度
- **创建仓库** — 支持私有/公开，可自动初始化 README

### 本地 Git 操作
基于 JGit 6.10 纯 Java 实现，无需外部 git 二进制：
- `clone` — 带 token 认证的远程克隆
- `init` — 初始化本地 Git 项目
- `add / commit` — 暂存与提交（支持自定义作者）
- `push / pull` — 普通与强制推拉（force push / reset --hard）
- `branch` — 完整分支操作
- `diff / log` — 工作区变更、提交历史、逐文件 patch
- **冲突检测** — fetch 后自动比较本地/远程差异并提示

### 文件选择器
- 普通权限 + Root 双模式
- 书签系统（内置常用目录 + 自定义书签）
- 多种排序方式（名称/日期/大小/类型）
- 跨 Tab 状态保留（路径与滚动位置）
- 完整支持含空格的目录名

### 用户体验
- Material 3 动态主题（浅色/深色/跟随系统）
- 崩溃日志本地捕获与导出
- GitHub Actions 自动构建签名 APK
- 推送 tag 自动发布 Release

## 快速开始

### 前置要求
- Android Studio Ladybug 或更高版本
- JDK 17+
- Android SDK 26+（目标 SDK 36）
- GitHub 账号

### 1. 创建 GitHub OAuth App

访问 [GitHub Developer Settings](https://github.com/settings/developers) 创建 OAuth App：

| 字段 | 值 |
|------|-----|
| Application name | GitMob |
| Homepage URL | `https://your-worker-domain.com` |
| Authorization callback URL | `https://your-worker-domain.com/callback` |

保存 **Client ID** 和 **Client Secret**。

### 2. 部署 Cloudflare Worker

```bash
cd cf-worker
npm install

# 配置环境变量（加密存储）
npx wrangler secret put GITHUB_CLIENT_ID
npx wrangler secret put GITHUB_CLIENT_SECRET

# 部署到 Cloudflare
npm run deploy
```

可选：在 Cloudflare Dashboard 绑定自定义域名。

### 3. 配置 GitHub Actions

在仓库 Settings → Secrets and variables → Actions 添加：

| Secret 名称 | 说明 |
|------------|------|
| `OAUTH_CLIENT_ID` | GitHub OAuth App Client ID |
| `OAUTH_CALLBACK_URL` | Worker 基础 URL（不含 `/callback`） |
| `KEYSTORE_BASE64` | Keystore 文件的 base64 编码 |
| `KEYSTORE_PASSWORD` | Keystore 密码 |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key 密码 |

> 注意：Secret 名称不能以 `GITHUB_` 开头（系统保留前缀）

### 4. 本地开发

修改 `app/build.gradle.kts` 中的 OAuth 配置：

```kotlin
buildConfigField("String", "GITHUB_CLIENT_ID",  "\"your_client_id\"")
buildConfigField("String", "OAUTH_REDIRECT_URI", "\"https://your-worker-domain.com/callback\"")
```

构建并安装：

```bash
./gradlew assembleDebug
./gradlew installDebug
```
或者
```bash
gradle assembleDebug
gradle installDebug
```
### 5. 生成签名 Keystore

```bash
keytool -genkey -v \
  -keystore gitmob-release.jks \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -alias gitmob

# 转换为 base64 供 GitHub Actions 使用
base64 -w 0 gitmob-release.jks
```

### 6. 触发构建

- **手动构建**：Actions → Build & Release APK → Run workflow
- **自动发布**：推送 tag（如 `v1.0.0`）自动构建并创建 Release

## OAuth 认证流程

GitMob 采用安全的 OAuth 2.0 认证流程，通过 Cloudflare Worker 中转，确保 client_secret 永不暴露在客户端：

```
1. Android App 打开 Custom Tab
   ↓
2. https://your-worker.com/auth
   ↓ 302 重定向
3. github.com/login/oauth/authorize（用户授权）
   ↓ 带 code 回调
4. https://your-worker.com/callback
   ↓ Worker 使用 client_secret 换取 access_token
5. gitmob://oauth?token=xxx（Deep Link 唤起 App）
   ↓
6. DataStore 持久化存储 → 所有 API 请求携带 Bearer token
```

### 注销机制

- **退出登录**：撤销当前 token（`DELETE /token`），授权记录保留，下次可快速重新登录
- **取消授权**：删除 OAuth Grant（`DELETE /grant`），彻底清除授权，下次需重新完整授权

### Worker API 路由

| 路径 | 方法 | 功能 |
|------|------|------|
| `/` | GET | App 落地页（APK 下载、功能介绍） |
| `/auth` | GET | 跳转 GitHub OAuth（`?force=1` 强制重授权） |
| `/callback` | GET | code → token，HTML 唤起 App 深链接 |
| `/health` | GET | 健康检查 `{"ok": true}` |
| `/token` | DELETE | 撤销当前 token |
| `/grant` | DELETE | 删除 OAuth grant（彻底注销） |

## 技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin 2.3.0 |
| UI 框架 | Jetpack Compose BOM 2026.03.00 · Material 3 |
| 架构组件 | Lifecycle 2.9.0 · ViewModel · Navigation Compose 2.9.7 · DataStore 1.2.1 |
| 网络 | Retrofit 2.11.0 · OkHttp 4.12.0 · Gson 2.11.0 |
| 本地 Git | JGit 6.10.0（纯 Java 实现，无需外部 git） |
| 图片加载 | Coil 3.4.0（支持 SVG、OkHttp 网络层） |
| 数据解析 | Jackson 2.17.2（YAML）· Gson 2.11.0（JSON） |
| Markdown | Flexmark 0.64.8 |
| 协程 | Kotlinx Coroutines 1.9.0 |
| 其他 | Material Components 1.12.0 · Browser 1.8.0 · SplashScreen 1.0.1 |
| 后端 | Cloudflare Workers（TypeScript） |
| 构建工具 | Gradle 8.13.0 · AGP 8.13.2 |

## 项目结构

```
GitMob-Android/
├── app/src/main/java/com/gitmob/android/
│   ├── GitMobApp.kt        # Application 入口
│   ├── MainActivity.kt     # 主 Activity
│   │
│   ├── api/                # 网络层
│   │   ├── ApiClient.kt           # Retrofit 客户端配置
│   │   ├── GitHubApi.kt           # GitHub REST API 接口定义
│   │   ├── GitHubModels.kt        # API 数据模型
│   │   └── GraphQLClient.kt       # GraphQL 客户端
│   │
│   ├── auth/               # 认证与授权
│   │   ├── OAuthManager.kt        # OAuth 2.0 认证管理
│   │   ├── TokenStorage.kt        # Token 持久化存储
│   │   ├── AccountStore.kt        # 多账号管理
│   │   └── RootManager.kt         # Root 权限管理
│   │
│   ├── data/               # 数据层
│   │   ├── RepoRepository.kt      # 仓库数据仓库
│   │   └── ThemePreference.kt     # 主题偏好设置
│   │
│   ├── local/              # 本地 Git 操作
│   │   ├── GitRunner.kt           # JGit 操作封装
│   │   ├── LocalRepo.kt           # 本地仓库模型
│   │   └── LocalRepoStorage.kt    # 本地仓库存储管理
│   │
│   ├── ui/                 # UI 层（Jetpack Compose）
│   │   ├── common/                # 通用组件
│   │   │   ├── Components.kt
│   │   │   └── GmWebView.kt
│   │   │
│   │   ├── login/                 # 登录页面
│   │   │   ├── LoginScreen.kt
│   │   │   └── LoginViewModel.kt
│   │   │
│   │   ├── repos/                 # 仓库列表
│   │   │   ├── RepoListScreen.kt
│   │   │   ├── RepoListViewModel.kt
│   │   │   ├── RepoFilterComponents.kt
│   │   │   ├── StarListComponents.kt
│   │   │   └── StarListViewModel.kt
│   │   │
│   │   ├── repo/                  # 仓库详情
│   │   │   ├── RepoDetailScreen.kt
│   │   │   ├── RepoDetailViewModel.kt
│   │   │   ├── RepoDetailState.kt
│   │   │   ├── CommitComponents.kt
│   │   │   ├── BranchComponents.kt
│   │   │   ├── ActionsComponents.kt
│   │   │   ├── IssuesComponents.kt
│   │   │   ├── IssueDetailScreen.kt
│   │   │   ├── ReleasesComponents.kt
│   │   │   ├── DiffComponents.kt
│   │   │   ├── EditFileScreen.kt
│   │   │   ├── UploadComponents.kt
│   │   │   ├── WatchComponents.kt
│   │   │   ├── RepoDialogs.kt
│   │   │   └── RepoPermission.kt
│   │   │
│   │   ├── local/                 # 本地仓库管理
│   │   │   ├── LocalRepoListScreen.kt
│   │   │   ├── LocalRepoDetailScreen.kt
│   │   │   ├── LocalRepoViewModel.kt
│   │   │   └── GitOperationSheet.kt
│   │   │
│   │   ├── create/                # 创建仓库
│   │   │   └── CreateRepoScreen.kt
│   │   │
│   │   ├── filepicker/            # 文件选择器
│   │   │   └── FilePickerScreen.kt
│   │   │
│   │   ├── settings/              # 设置页面
│   │   │   └── SettingsScreen.kt
│   │   │
│   │   ├── nav/                   # 导航
│   │   │   └── NavGraph.kt
│   │   │
│   │   └── theme/                 # 主题系统
│   │       ├── Theme.kt
│   │       ├── Color.kt
│   │       ├── GmColors.kt
│   │       └── Type.kt
│   │
│   └── util/               # 工具类
│       ├── DownloadManager.kt     # 下载管理器
│       ├── DownloadReceiver.kt    # 下载广播接收器
│       ├── CrashHandler.kt        # 崩溃日志处理
│       ├── LogManager.kt          # 日志管理
│       ├── MarkdownUtils.kt       # Markdown 渲染工具
│       └── GitHubUrlParser.kt     # GitHub URL 解析
│
├── cf-worker/              # Cloudflare Worker（OAuth 中转）
│   ├── src/index.ts               # Worker 主逻辑
│   ├── package.json               # 依赖配置
│   ├── tsconfig.json              # TypeScript 配置
│   └── wrangler.toml              # Cloudflare 部署配置
│
└── .github/workflows/      # CI/CD 配置
```

## 待优化功能

以下是项目当前的已知需要优化的地方，详细内容请查看 [TODO.md](TODO.md)。

### 功能增强

1. **GitHub的搜索功能**

2. **仓库详情页交互优化**

3. **PR 功能完善**

4. **本地仓库管理增强**

5. **启动速度优化**

6. **Token 加密存储**

7. **Git 底层改造**


## 许可证

本项目采用 Apache 2.0 许可证。详见 [LICENSE](LICENSE) 文件。

## 致谢

- [JGit](https://www.eclipse.org/jgit/) - 纯 Java Git 实现
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - 现代化 Android UI 工具包
- [Cloudflare Workers](https://workers.cloudflare.com/) - 边缘计算平台
- [Material Design 3](https://m3.material.io/) - Google 设计系统

---

**GitMob** - 让 GitHub 管理触手可及

如有问题或建议，欢迎在 [Issues](https://github.com/xiaobaiweinuli/GitMob-Android/issues) 中反馈。
