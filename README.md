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
- **Token 登录支持** — 支持使用 Personal Access Token 直接登录，自动识别 Token 类型
- **多账号管理** — 支持账号切换与新增，DataStore 持久化存储
- **仓库操作** — 搜索、筛选（公开/私有）、Star/Unstar、语言标签、归档/取消归档
- **文件管理** — 文件树浏览、在线编辑/删除、提交、历史记录与 diff 对比
- **提交历史** — 完整 commit 列表、逐文件 diff、支持 revert
- **分支管理** — 创建、切换、删除、重命名、设置默认分支
- **Pull Requests** — PR 列表、分支信息展示
- **Issues** — 多维度筛选（状态/标签/作者/排序）、创建 Issue（支持 YAML Form 模板）
- **GitHub Actions** — Workflow 列表、手动触发、运行日志、产物下载
- **Releases** — 发行版管理、Asset 下载（带进度通知，自动处理重定向）
- **仓库订阅** — Watch/Unwatch，支持 Ignore/Participating/Releases 粒度
- **创建仓库** — 支持私有/公开，可自动初始化 README
- **讨论管理** — 仓库讨论列表与详情查看

### 个人主页
- **用户资料展示** — 头像、名称、登录名、简介、公司、位置、博客、Twitter 等信息
- **关注者/关注列表** — 查看用户的关注者和关注的人
- **仓库统计** — 展示用户的仓库、组织、星标数量
- **置顶仓库** — 展示用户置顶的仓库
- **收藏夹** — 支持仓库收藏分组管理，可创建多个收藏夹分组

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
- **搜索功能** — 支持仓库、用户、组织搜索
- **导航系统** — 底部标签导航（主页、远程、本地、设置），支持用户主页和仓库详情导航

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

## Token 登录

GitMob 也支持使用 GitHub Personal Access Token 直接登录，无需 OAuth 授权流程：

### Token 权限要求

Token 必须包含以下权限：
- `repo` - 仓库读写权限
- `workflow` - GitHub Actions 工作流权限
- `user` - 用户信息权限
- `notifications` - 通知权限
- `delete_repo` - 删除仓库权限

### 创建 Token

1. 访问 [GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)](https://github.com/settings/tokens)
2. 点击 "Generate new token" → "Generate new token (classic)"
3. 勾选上述所有权限
4. 点击 "Generate token"
5. 复制生成的 token（仅显示一次）

### 使用 Token 登录

1. 在登录页面点击 "使用 Token 登录" 按钮
2. 粘贴生成的 Personal Access Token
3. 点击 "登录"，App 会自动验证 Token 有效性和权限范围
4. 验证成功后即可使用

### Token 类型识别

GitMob 会自动识别 Token 的类型，为不同类型的 Token 提供相应的功能支持：

| Token 前缀 | Token 类型 | 说明 |
|-----------|-----------|------|
| `gho_` | OAuth Token | OAuth 2.0 授权流程获取的访问令牌 |
| `ghp_` | Classic Personal Access Token | 经典个人访问令牌（全功能） |
| `github_pat_` | Fine-grained Personal Access Token | 细粒度个人访问令牌（推荐） |
| 无前缀 | Legacy Token | 旧版 GitHub Token（已不推荐） |

### 账号区分

- OAuth 登录的账号在账号列表中显示为普通账号
- Token 登录的账号在账号列表中会显示 "Token" 标签
- Token 登录的账号在设置页面仅显示 "移除账号" 选项，不显示 OAuth 相关的注销操作

### 归档功能

GitMob 支持仓库归档和取消归档操作：

**功能说明：**
- 归档仓库后，仓库将变为只读状态，无法编辑、提交、接受 PR 或打开 Issue
- 取消归档后，仓库恢复正常读写状态
- 只有仓库所有者或具有管理员权限的用户可以操作归档功能

**操作步骤：**
1. 进入仓库详情页
2. 点击右上角的设置按钮（齿轮图标）
3. 在下拉菜单中选择「归档」或「取消归档」（根据当前状态动态显示）
4. 在确认对话框中确认操作
5. 操作成功后，仓库状态将立即更新

**视觉标识：**
- 已归档的仓库在仓库列表卡片上会显示「已归档」标签
- 已归档的仓库在仓库详情页会显示「已归档」标签（在「私有」标签旁边）
- 已归档的仓库在收藏夹卡片上也会显示「已归档」标签

### 仓库信息编辑

GitMob 支持编辑仓库信息，包括 About 描述、Website 链接和 Topics 标签：

**功能说明：**
- 支持在仓库列表卡片和仓库详情页中编辑仓库信息
- 编辑对话框会自动填充已有的 About、Website 和 Topics 内容
- Topics 支持使用空格分隔多个标签

**操作步骤：**
1. 在仓库列表卡片或仓库详情页点击菜单按钮
2. 选择「编辑信息」
3. 修改 About 描述、Website 链接或 Topics 标签
4. 点击「保存」确认修改

### 收藏夹管理

GitMob 支持仓库收藏分组管理，可创建多个收藏夹分组：

**功能说明：**
- 支持创建、编辑、删除收藏夹分组
- 支持将仓库添加到不同的收藏夹分组
- 支持收藏夹分组排序
- 收藏卡片显示仓库完整信息：名称、描述、语言、星标、复刻、私有/归档状态、Topics、Website
- Website 链接可直接点击打开
- 私有和归档状态与名称水平排列
- Topics 在星标和复刻下方换行显示，支持横向滚动

**数据持久化：**
- 收藏数据本地持久化存储
- 支持收藏数据导入导出
- 仓库信息自动更新（访问收藏仓库详情时自动同步最新数据）

### 重复登录检测

GitMob 支持智能检测同一用户的重复登录场景，并提供友好的选择：

1. **Token 登录 → 检测 OAuth 账号**
   - 如果检测到已有同一用户的 OAuth 账号，会询问是否撤销原 OAuth Token
   - 提供三个选项：撤销并使用 Token、保留 OAuth 继续、取消
   
2. **OAuth 登录 → 检测 Token 账号**
   - 如果检测到已有同一用户的 Token 账号，会提示 Token 无法自动撤销
   - 提供两个选项：使用 OAuth（保留 Token）、保持 Token（撤销 OAuth）
   
3. **OAuth 登录 → 检测旧 OAuth 账号**
   - 如果检测到已有同一用户的旧 OAuth 账号，会询问是否替换
   - 提供两个选项：使用新 OAuth（撤销旧 OAuth）、保持旧 OAuth（撤销新 OAuth）
   
4. **相同 Token 检测**
   - 如果输入的 Token 已存在于账号列表中，直接切换账号，无需弹窗

### 注销机制

- **退出登录**：撤销当前 token（`DELETE /token`），授权记录保留，下次可快速重新登录
- **取消授权**：删除 OAuth Grant（`DELETE /grant`），彻底清除授权，下次需重新完整授权

### 关于卸载 App

由于 Android 架构限制，卸载 App 时无法执行任何代码来撤销 token。如果需要完全撤销授权，可以：

1. 在 GitHub 设置中手动撤销：[https://github.com/settings/connections/applications/Ov23liP9mC2HXALHsFpk](https://github.com/settings/connections/applications/Ov23liP9mC2HXALHsFpk)
2. 或在 App 的「关于」页面点击「GitHub 授权管理」跳转

GitHub OAuth token 长期有效（默认闲置 1 年才自动失效），但只有拥有该 token 的 App 才能使用。

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

GitMob 采用 **分层架构**，遵循 Clean Architecture 原则，代码结构清晰、职责分明。

```
GitMob-Android/
├── app/src/main/java/com/gitmob/android/
│   ├── GitMobApp.kt        # Application 入口
│   │                     - 初始化 TokenStorage、ApiClient、Coil、NetworkMonitor
│   │                     - Root 权限自动恢复
│   │                     - 应用生命周期监听（后台→前台时重建 OkHttp）
│   │                     - 启动时检测更新
│   ├── MainActivity.kt     # 主 Activity
│   │                     - 处理 OAuth 深链接回调
│   │                     - 处理 GitHub 链接跳转
│   │                     - 主题切换
│   │
│   ├── api/                # 网络层（API 访问）
│   │   ├── ApiClient.kt           # Retrofit 客户端配置
│   │                     - OkHttpClient 构建（含重试、认证、日志拦截器）
│   │                     - 全局 401 Token 失效事件
│   │                     - 网络层自动重试机制
│   │   ├── GitHubApi.kt           # GitHub REST API 接口定义
│   │                     - User、Repo、Contents、Commits、Branches、PR、Issues、Actions、Releases 等
│   │   ├── GitHubModels.kt        # API 数据模型
│   │                     - 所有 GitHub API 响应的数据类定义
│   │   └── GraphQLClient.kt       # GraphQL 客户端
│   │                     - 查询用户仓库
│   │                     - 使用 createCommitOnBranch mutation 删除文件
│   │                     - 获取分支最新 commit OID
│   │
│   ├── auth/               # 认证与授权层
│   │   ├── AccountStore.kt        # 多账号管理
│   │                     - 账号增删改查
│   │                     - DataStore 持久化
│   │   ├── OAuthManager.kt        # OAuth 2.0 认证管理
│   │                     - Custom Tab 打开授权页面
│   │   ├── TokenLoginManager.kt   # Token 登录验证与权限检查
│   │                     - Token 类型识别（gho_/ghp_/github_pat_）
│   │                     - 权限范围验证
│   │   ├── RootManager.kt         # Root 权限管理
│   │                     - su 执行模式探测
│   │                     - Root 权限请求
│   │   └── TokenStorage.kt        # Token 持久化存储（EncryptedPreferences）
│   │                     - Token、主题、Root 开关、账号等状态存储
│   │
│   ├── data/               # 数据层（Repository 模式）
│   │   ├── FavoritesManager.kt    # 收藏夹管理（支持导出导入）
│   │                     - 收藏夹分组创建/编辑/删除
│   │                     - 收藏数据导入导出
│   │   ├── RepoRepository.kt      # 仓库数据仓库
│   │                     - 多级缓存策略（内存缓存 + TTL）
│   │                     - 增量刷新机制
│   │                     - 文件删除（GraphQL）
│   │                     - 文件创建/编辑
│   │   └── RepoUpdateEventBus.kt # 仓库更新事件总线
│   │                     - PR、Issue 更新事件通知
│   │
│   ├── local/              # 本地 Git 操作层
│   │   ├── GitRunner.kt           # JGit 操作封装
│   │                     - clone、init、add、commit、push、pull、branch、diff、log
│   │   ├── LocalRepo.kt           # 本地仓库模型
│   │   └── LocalRepoStorage.kt    # 本地仓库存储管理
│   │
│   ├── ui/                 # UI 层（Jetpack Compose）
│   │   ├── common/                # 通用组件
│   │   │   ├── Components.kt      - Button、Card、Dialog、Loading、Error 等通用组件
│   │   │   └── GmWebView.kt       - 自定义 WebView 组件
│   │   │
│   │   ├── create/                # 创建仓库页面
│   │   │   └── CreateRepoScreen.kt
│   │   │
│   │   ├── filepicker/            # 文件选择器
│   │   │   └── FilePickerScreen.kt - 普通权限 + Root 双模式
│   │   │                             - 书签系统
│   │   │                             - 多种排序方式
│   │   │
│   │   ├── home/                  # 个人主页
│   │   │   ├── HomeScreen.kt
│   │   │   └── HomeViewModel.kt
│   │   │
│   │   ├── local/                 # 本地仓库管理
│   │   │   ├── GitOperationSheet.kt - Git 操作弹窗
│   │   │   ├── LocalRepoDetailScreen.kt
│   │   │   ├── LocalRepoListScreen.kt
│   │   │   └── LocalRepoViewModel.kt
│   │   │
│   │   ├── login/                 # 登录页面
│   │   │   ├── LoginScreen.kt     - OAuth 登录 + Token 登录
│   │   │   └── LoginViewModel.kt
│   │   │
│   │   ├── nav/                   # 导航系统
│   │   │   └── NavGraph.kt        - App 导航图定义
│   │   │                             - 三态初始化状态机（Loading/NeedsLogin/Ready）
│   │   │                             - 账号自动恢复
│   │   │                             - GitHub 链接解析跳转
│   │   │
│   │   ├── repo/                  # 仓库详情页（核心功能）
│   │   │   ├── ActionsComponents.kt    - GitHub Actions 组件
│   │   │   ├── BranchComponents.kt     - 分支管理组件
│   │   │   ├── CommitComponents.kt     - 提交历史组件
│   │   │   ├── DiffComponents.kt       - 代码对比组件
│   │   │   ├── DiscussionDetailScreen.kt - 讨论详情页
│   │   │   ├── DiscussionDetailViewModel.kt
│   │   │   ├── DiscussionsComponents.kt - 讨论列表组件
│   │   │   ├── EditFileScreen.kt       - 文件编辑页
│   │   │   ├── IssueDetailScreen.kt    - Issue 详情页
│   │   │   ├── IssueDetailViewModel.kt
│   │   │   ├── IssuesComponents.kt     - Issue 列表组件
│   │   │   ├── PRComponents.kt         - PR 列表组件
│   │   │   ├── PRDetailViewModel.kt    - PR 详情 ViewModel
│   │   │   ├── ReleasesComponents.kt   - Releases 组件
│   │   │   ├── RepoDetailScreen.kt     - 仓库详情主页面
│   │   │   ├── RepoDetailState.kt      - 仓库详情状态定义
│   │   │   ├── RepoDetailViewModel.kt  - 仓库详情 ViewModel
│   │   │                             - 文件浏览、编辑、删除
│   │   │                             - 上传文件/文件夹（含重复检测）
│   │   │                             - 删除 toast 动态显示（按文件类型）
│   │   │   ├── RepoDialogs.kt          - 仓库相关对话框
│   │   │   ├── RepoPermission.kt       - 仓库权限检查
│   │   │   ├── UploadComponents.kt     - 上传组件
│   │   │                             - UploadSourceSheet（选择文件/文件夹）
│   │   │                             - UploadReviewSheet（预览确认，含重复检测）
│   │   │                             - UploadProgressDialog（上传进度）
│   │   │                             - 滑动优化（禁用手势、消费垂直滚动）
│   │   │   └── WatchComponents.kt      - 仓库订阅组件
│   │   │
│   │   ├── repos/                 # 仓库列表页
│   │   │   ├── RepoFilterComponents.kt - 仓库筛选组件
│   │   │   ├── RepoFilterModels.kt     - 筛选模型
│   │   │   ├── RepoListScreen.kt       - 仓库列表主页面
│   │   │   ├── RepoListViewModel.kt     - 仓库列表 ViewModel
│   │   │   ├── StarListComponents.kt    - 星标列表组件
│   │   │   ├── StarListModels.kt        - 星标模型
│   │   │   └── StarListViewModel.kt    - 星标 ViewModel
│   │   │
│   │   ├── search/                # 搜索功能
│   │   │   ├── SearchScreen.kt       - 搜索页面
│   │   │   └── SearchViewModel.kt    - 搜索 ViewModel
│   │   │
│   │   ├── settings/              # 设置页面
│   │   │   └── SettingsScreen.kt
│   │   │
│   │   ├── theme/                 # 主题系统
│   │   │   ├── Color.kt            - 颜色定义
│   │   │   ├── GmColors.kt         - 颜色主题包装
│   │   │   ├── Theme.kt            - Material 3 主题
│   │   │   └── Type.kt             - 字体样式
│   │   │
│   │   └── update/                # 更新功能
│   │       └── UpdateDialog.kt     - 更新对话框
│   │
│   └── util/               # 工具类
│       ├── CrashHandler.kt        # 崩溃日志处理
│       ├── DownloadForegroundService.kt # 下载前台服务
│       ├── DownloadManager.kt     # 下载管理器
│       ├── DownloadReceiver.kt    # 下载广播接收器
│       ├── EncryptedPreferences.kt # 加密 SharedPreferences
│       ├── GitHubUrlParser.kt     # GitHub URL 解析
│       ├── LanguageManager.kt     # 语言管理（从 GitHub Linguist 获取）
│       ├── LogManager.kt          # 日志管理
│       ├── MarkdownUtils.kt       # Markdown 渲染工具
│       ├── NetworkMonitor.kt        # 网络状态监听
│       └── UpdateManager.kt        # 更新检测与下载
│
├── cf-worker/              # Cloudflare Worker（OAuth 中转）
│   ├── src/index.ts               # Worker 主逻辑
│   ├── package.json               # 依赖配置
│   ├── tsconfig.json              # TypeScript 配置
│   └── wrangler.toml              # Cloudflare 部署配置
│
└── .github/workflows/      # CI/CD 配置
```

### 核心设计模式

1. **MVVM 架构**：所有 UI 页面都有对应的 ViewModel 管理状态和业务逻辑
2. **Repository 模式**：`RepoRepository` 统一管理数据访问，包含多级缓存策略
3. **事件总线**：`RepoUpdateEventBus` 用于组件间通信（PR、Issue 更新通知）
4. **状态管理**：使用 Kotlin Coroutines Flow 管理状态，Compose 自动响应状态变化
5. **依赖注入**：通过构造函数传递依赖，ViewModel 使用 SavedStateHandle

### 关键技术亮点

| 特性 | 说明 |
|------|------|
| **多级缓存** | 内存缓存 + TTL 过期策略，减少网络请求 |
| **增量刷新** | 只拉取第一页新数据，按唯一 key 去重合并，避免 UI 闪烁 |
| **网络重试** | OkHttp 拦截器实现智能重试（网络异常、5xx 错误、HTTP/2 连接问题） |
| **Token 失效** | 全局 401 监听，自动清除授权并跳转登录 |
| **GraphQL 删除** | 使用 createCommitOnBranch mutation 实现单次 commit 删除文件 |

## 待优化功能

以下是项目当前的已知需要优化的地方，详细内容请查看 [TODO.md](TODO.md)。

### 功能增强

1.  **仓库详情页交互优化**

2. **本地仓库管理增强**

3. **启动速度优化**

4. **Token 加密存储**

5. **Git 底层改造**


## 许可证

本项目采用 Apache 2.0 许可证。详见 [LICENSE](LICENSE) 文件。

## 致谢

- [JGit](https://www.eclipse.org/jgit/) - 纯 Java Git 实现
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - 现代化 Android UI 工具包
- [Cloudflare Workers](https://workers.cloudflare.com/) - 边缘计算平台
- [Material Design 3](https://m3.material.io/) - Google 设计系统
- [GitHub Linguist](https://github.com/github-linguist/linguist) - 编程语言识别和颜色数据
- [GitHub Markdown风格的CSS](https://github.com/sindresorhus/github-markdown-css) - Markdown 渲染样式表
- [Flexmark](https://github.com/vsch/flexmark-java) - Markdown 解析库
- [Jackson](https://github.com/FasterXML/jackson) - JSON/YAML 解析库
- [Gson](https://github.com/google/gson) - JSON 解析库


---

**GitMob** - 让 GitHub 管理触手可及

如有问题或建议，欢迎在 [Issues](https://github.com/xiaobaiweinuli/GitMob-Android/issues) 中反馈。
