
/**
 * GitMob OAuth Worker
 * 路由:
 *   GET    /           → App 落地页（多语言，IP 自动切换）
 *   GET    /sitemap.xml → 动态生成 sitemap（含 hreflang）
 *   GET    /auth        → 跳转 GitHub OAuth 授权
 *   GET    /callback    → 接收 code，换 token，HTML + JS 唤起 App
 *   GET    /health      → 健康检查
 *   DELETE /token       → 撤销 Token
 *   DELETE /grant       → 删除授权 Grant
 */

export interface Env {
  GITHUB_CLIENT_ID: string;
  GITHUB_CLIENT_SECRET: string;
  ASSETS: Fetcher;
}

// ─── 常量 ────────────────────────────────────────────────────────────────────

const APP_SCHEME = "gitmob://oauth";
const REPO_URL   = "https://github.com/xiaobaiweinuli/GitMob-Android";
const SITE_URL   = "https://gitmob.16618888.xyz";
const SCOPES     = "repo,user,delete_repo,workflow,notifications";

// ─── 国际化类型 ──────────────────────────────────────────────────────────────

type Lang = "zh" | "en" | "ja" | "ko" | "es";
const LANGS: Lang[] = ["zh", "en", "ja", "ko", "es"];

interface I18nContent {
  pageTitle: string;
  metaDesc: string;
  h1Sub: string;       // subtitle line 1
  stack: string;       // tech stack line
  downloadBtn: string;
  repoBtn: string;
  footer: string;
  authSuccess: string;
  authFail: string;
  authSuccessMsg: string;
  authFailMsg: string;
  openAppBtn: string;
  openHint: string;
  features: Array<{ emoji: string; name: string; desc: string }>;
}

// ─── i18n 内容表 ─────────────────────────────────────────────────────────────

const i18n: Record<Lang, I18nContent> = {
  zh: {
    pageTitle: "GitMob — 手机端 GitHub 管理工具",
    metaDesc:  "GitMob 是采用 Kotlin + Jetpack Compose + Material 3 打造的纯原生 Android GitHub 客户端，支持仓库管理、文件浏览、PR/Issues、多账号等功能，完全开源免费。",
    h1Sub:     "手机端 GitHub 原生管理工具",
    stack:     "Kotlin · Jetpack Compose · Material 3",
    downloadBtn: "📥 下载 APK",
    repoBtn:   "GitHub 仓库",
    footer:    "纯原生 Android 应用（Jetpack Compose） · 完全开源",
    authSuccess: "授权成功",
    authFail:    "授权失败",
    authSuccessMsg: "授权成功！正在自动跳转回 GitMob…",
    authFailMsg:    "授权过程中出现错误，请返回 GitMob App 重试。",
    openAppBtn: "打开 GitMob",
    openHint:   "如果未自动跳转，请点击上方按钮手动打开 GitMob",
    features: [
      { emoji: "📦", name: "仓库管理",   desc: "搜索、筛选、星标" },
      { emoji: "📂", name: "文件浏览",   desc: "任意分支、路径" },
      { emoji: "🌳", name: "分支操作",   desc: "创建、切换、管理" },
      { emoji: "🔀", name: "PR / Issues", desc: "查看开放状态" },
      { emoji: "👤", name: "个人主页",   desc: "资料展示、关注列表" },
      { emoji: "⭐", name: "收藏夹",     desc: "仓库收藏、分组管理" },
      { emoji: "➕", name: "手动创建",   desc: "仓库、自动初始化" },
      { emoji: "🚀", name: "Actions",    desc: "工作流、日志查看" },
      { emoji: "👥", name: "多账号",     desc: "复制、切换管理" },
      { emoji: "📜", name: "提交历史",   desc: "commit 列表、diff 对比" },
      { emoji: "📤", name: "Releases",   desc: "发行版、产物下载" },
      { emoji: "💬", name: "讨论管理",   desc: "讨论列表与详情" },
      { emoji: "💻", name: "本地 Git",   desc: "clone、commit、push、pull" },
      { emoji: "📑", name: "文件选择器", desc: "书签、多种排序" },
      { emoji: "🎨", name: "Material 3", desc: "动态主题、极致体验" },
      { emoji: "🔎", name: "全局搜索",   desc: "仓库、用户、组织" },
    ],
  },

  en: {
    pageTitle: "GitMob — Native Android GitHub Client",
    metaDesc:  "GitMob is a native Android GitHub client built with Kotlin, Jetpack Compose & Material 3. Manage repos, browse files, handle PRs/Issues, multi-account and more. Fully open source.",
    h1Sub:     "Native GitHub Client for Android",
    stack:     "Kotlin · Jetpack Compose · Material 3",
    downloadBtn: "📥 Download APK",
    repoBtn:   "GitHub Repository",
    footer:    "Pure Native Android App (Jetpack Compose) · Fully Open Source",
    authSuccess: "Authorization Successful",
    authFail:    "Authorization Failed",
    authSuccessMsg: "Authorization successful! Redirecting back to GitMob…",
    authFailMsg:    "An error occurred during authorization. Please return to GitMob and try again.",
    openAppBtn: "Open GitMob",
    openHint:   "If not redirected automatically, tap the button above to open GitMob",
    features: [
      { emoji: "📦", name: "Repositories", desc: "Search, filter & star" },
      { emoji: "📂", name: "File Browser",  desc: "Any branch & path" },
      { emoji: "🌳", name: "Branches",      desc: "Create, switch, manage" },
      { emoji: "🔀", name: "PR / Issues",   desc: "View & manage" },
      { emoji: "👤", name: "Profile",       desc: "Info & followers" },
      { emoji: "⭐", name: "Favorites",     desc: "Group & organize repos" },
      { emoji: "➕", name: "Create Repo",   desc: "Auto initialize" },
      { emoji: "🚀", name: "Actions",       desc: "Workflow & logs" },
      { emoji: "👥", name: "Multi-Account", desc: "Switch & manage" },
      { emoji: "📜", name: "Commits",       desc: "History & diff" },
      { emoji: "📤", name: "Releases",      desc: "Download artifacts" },
      { emoji: "💬", name: "Discussions",   desc: "List & details" },
      { emoji: "💻", name: "Local Git",     desc: "clone, commit, push, pull" },
      { emoji: "📑", name: "File Picker",   desc: "Bookmarks & sorting" },
      { emoji: "🎨", name: "Material 3",    desc: "Dynamic theme" },
      { emoji: "🔎", name: "Search",        desc: "Repos, users, orgs" },
    ],
  },

  ja: {
    pageTitle: "GitMob — Android 向け GitHub ネイティブクライアント",
    metaDesc:  "GitMob は Kotlin・Jetpack Compose・Material 3 で構築された Android 向けネイティブ GitHub クライアントです。リポジトリ管理、ファイル閲覧、PR/Issues、マルチアカウントなど多機能。完全オープンソース。",
    h1Sub:     "Android 向け GitHub ネイティブクライアント",
    stack:     "Kotlin · Jetpack Compose · Material 3",
    downloadBtn: "📥 APK ダウンロード",
    repoBtn:   "GitHub リポジトリ",
    footer:    "純粋なネイティブ Android アプリ（Jetpack Compose） · 完全オープンソース",
    authSuccess: "認証成功",
    authFail:    "認証失敗",
    authSuccessMsg: "認証に成功しました！GitMob に戻ります…",
    authFailMsg:    "認証中にエラーが発生しました。GitMob アプリに戻って再試行してください。",
    openAppBtn: "GitMob を開く",
    openHint:   "自動的にリダイレクトされない場合は、上のボタンをタップしてください",
    features: [
      { emoji: "📦", name: "リポジトリ",       desc: "検索・フィルタ・スター" },
      { emoji: "📂", name: "ファイル閲覧",     desc: "任意ブランチ・パス" },
      { emoji: "🌳", name: "ブランチ",         desc: "作成・切替・管理" },
      { emoji: "🔀", name: "PR / Issues",      desc: "表示・管理" },
      { emoji: "👤", name: "プロフィール",     desc: "情報・フォロワー" },
      { emoji: "⭐", name: "お気に入り",       desc: "グループ管理" },
      { emoji: "➕", name: "リポジトリ作成",   desc: "自動初期化" },
      { emoji: "🚀", name: "Actions",          desc: "ワークフロー・ログ" },
      { emoji: "👥", name: "マルチアカウント", desc: "切替・管理" },
      { emoji: "📜", name: "コミット履歴",     desc: "一覧・差分表示" },
      { emoji: "📤", name: "Releases",         desc: "ダウンロード" },
      { emoji: "💬", name: "ディスカッション", desc: "一覧・詳細" },
      { emoji: "💻", name: "ローカル Git",     desc: "clone・push・pull" },
      { emoji: "📑", name: "ファイル選択",     desc: "ブックマーク・ソート" },
      { emoji: "🎨", name: "Material 3",       desc: "ダイナミックテーマ" },
      { emoji: "🔎", name: "グローバル検索",   desc: "リポジトリ・ユーザー" },
    ],
  },

  ko: {
    pageTitle: "GitMob — 안드로이드 네이티브 GitHub 클라이언트",
    metaDesc:  "GitMob은 Kotlin, Jetpack Compose, Material 3으로 제작된 안드로이드 네이티브 GitHub 클라이언트입니다. 저장소 관리, 파일 탐색, PR/이슈, 멀티계정 등 지원. 완전 오픈소스.",
    h1Sub:     "안드로이드용 GitHub 네이티브 클라이언트",
    stack:     "Kotlin · Jetpack Compose · Material 3",
    downloadBtn: "📥 APK 다운로드",
    repoBtn:   "GitHub 저장소",
    footer:    "순수 네이티브 안드로이드 앱 (Jetpack Compose) · 완전 오픈소스",
    authSuccess: "인증 성공",
    authFail:    "인증 실패",
    authSuccessMsg: "인증에 성공했습니다! GitMob으로 돌아갑니다…",
    authFailMsg:    "인증 중 오류가 발생했습니다. GitMob 앱으로 돌아가 다시 시도해주세요.",
    openAppBtn: "GitMob 열기",
    openHint:   "자동으로 이동하지 않으면 위 버튼을 탭하세요",
    features: [
      { emoji: "📦", name: "저장소 관리",  desc: "검색, 필터, 즐겨찾기" },
      { emoji: "📂", name: "파일 탐색",    desc: "브랜치 & 경로" },
      { emoji: "🌳", name: "브랜치",       desc: "생성, 전환, 관리" },
      { emoji: "🔀", name: "PR / Issues",  desc: "보기 & 관리" },
      { emoji: "👤", name: "프로필",       desc: "정보 & 팔로워" },
      { emoji: "⭐", name: "즐겨찾기",     desc: "그룹 관리" },
      { emoji: "➕", name: "저장소 생성",  desc: "자동 초기화" },
      { emoji: "🚀", name: "Actions",      desc: "워크플로우 & 로그" },
      { emoji: "👥", name: "멀티 계정",    desc: "전환 & 관리" },
      { emoji: "📜", name: "커밋 히스토리", desc: "목록 & diff" },
      { emoji: "📤", name: "Releases",     desc: "다운로드" },
      { emoji: "💬", name: "토론",         desc: "목록 & 상세" },
      { emoji: "💻", name: "로컬 Git",     desc: "clone, push, pull" },
      { emoji: "📑", name: "파일 선택기",  desc: "북마크 & 정렬" },
      { emoji: "🎨", name: "Material 3",   desc: "다이나믹 테마" },
      { emoji: "🔎", name: "글로벌 검색",  desc: "저장소, 사용자, 조직" },
    ],
  },

  es: {
    pageTitle: "GitMob — Cliente Android Nativo de GitHub",
    metaDesc:  "GitMob es un cliente nativo de GitHub para Android construido con Kotlin, Jetpack Compose y Material 3. Gestiona repos, archivos, PR/Issues, múltiples cuentas y más. Código abierto.",
    h1Sub:     "Cliente Nativo de GitHub para Android",
    stack:     "Kotlin · Jetpack Compose · Material 3",
    downloadBtn: "📥 Descargar APK",
    repoBtn:   "Repositorio GitHub",
    footer:    "App Android 100% Nativa (Jetpack Compose) · Código Abierto",
    authSuccess: "Autorización Exitosa",
    authFail:    "Autorización Fallida",
    authSuccessMsg: "¡Autorización exitosa! Redirigiendo a GitMob…",
    authFailMsg:    "Ocurrió un error durante la autorización. Por favor regresa a GitMob e intenta de nuevo.",
    openAppBtn: "Abrir GitMob",
    openHint:   "Si no se redirige automáticamente, toca el botón de arriba",
    features: [
      { emoji: "📦", name: "Repositorios", desc: "Buscar, filtrar, marcar" },
      { emoji: "📂", name: "Archivos",     desc: "Cualquier rama y ruta" },
      { emoji: "🌳", name: "Ramas",        desc: "Crear, cambiar, gestionar" },
      { emoji: "🔀", name: "PR / Issues",  desc: "Ver y gestionar" },
      { emoji: "👤", name: "Perfil",       desc: "Info y seguidores" },
      { emoji: "⭐", name: "Favoritos",    desc: "Grupos y organización" },
      { emoji: "➕", name: "Crear Repo",   desc: "Auto inicializar" },
      { emoji: "🚀", name: "Actions",      desc: "Flujos y registros" },
      { emoji: "👥", name: "Multicuenta",  desc: "Cambiar y gestionar" },
      { emoji: "📜", name: "Commits",      desc: "Historial y diff" },
      { emoji: "📤", name: "Releases",     desc: "Descargar artefactos" },
      { emoji: "💬", name: "Discusiones",  desc: "Lista y detalles" },
      { emoji: "💻", name: "Git Local",    desc: "clone, commit, push, pull" },
      { emoji: "📑", name: "Selector",     desc: "Marcadores y orden" },
      { emoji: "🎨", name: "Material 3",   desc: "Tema dinámico" },
      { emoji: "🔎", name: "Búsqueda",     desc: "Repos, usuarios, orgs" },
    ],
  },
};

// ─── 语言工具函数 ─────────────────────────────────────────────────────────────

/** IP 国家 → 语言 */
function countryToLang(country: string | undefined): Lang {
  if (!country) return "en";
  const c = country.toUpperCase();
  if (["CN","TW","HK","MO","SG"].includes(c)) return "zh";
  if (c === "JP") return "ja";
  if (c === "KR") return "ko";
  if (["ES","MX","AR","CL","CO","PE","VE","EC","BO","PY","UY",
       "GT","HN","SV","NI","CR","PA","DO","CU","PR"].includes(c)) return "es";
  return "en";
}

/** 从 Cookie 字符串提取语言 */
function getCookieLang(cookieHeader: string | null): Lang | null {
  if (!cookieHeader) return null;
  const m = cookieHeader.match(/gitmob-lang=([a-z]{2})/);
  if (!m) return null;
  const l = m[1] as Lang;
  return LANGS.includes(l) ? l : null;
}

/** 语言检测优先级：?lang= → Cookie → CF IP → Accept-Language → en */
function detectLang(request: Request, url: URL): Lang {
  const param = url.searchParams.get("lang") as Lang | null;
  if (param && LANGS.includes(param)) return param;

  const cookie = getCookieLang(request.headers.get("Cookie"));
  if (cookie) return cookie;

  const country = (request.cf as any)?.country as string | undefined;
  const geo = countryToLang(country);
  if (geo !== "en") return geo;

  const al = request.headers.get("Accept-Language") ?? "";
  if (al.startsWith("zh")) return "zh";
  if (al.startsWith("ja")) return "ja";
  if (al.startsWith("ko")) return "ko";
  if (al.startsWith("es")) return "es";

  return "en";
}

/** Lang → BCP47 locale（用于 og:locale） */
function langToLocale(lang: Lang): string {
  return { zh:"zh_CN", en:"en_US", ja:"ja_JP", ko:"ko_KR", es:"es_ES" }[lang];
}

/** 构建 JSON-LD SoftwareApplication 结构化数据 */
function buildJsonLd(lang: Lang, t: I18nContent): string {
  return JSON.stringify({
    "@context": "https://schema.org",
    "@type": "SoftwareApplication",
    name: "GitMob",
    operatingSystem: "Android",
    applicationCategory: "DeveloperApplication",
    offers: { "@type": "Offer", price: "0", priceCurrency: "USD" },
    description: t.metaDesc,
    url: SITE_URL,
    downloadUrl: `${REPO_URL}/releases`,
    screenshot: `${SITE_URL}/logo.png`,
    author: {
      "@type": "Person",
      name: "xiaobaiweinuli",
      url: "https://github.com/xiaobaiweinuli",
    },
    featureList: t.features.map(f => f.name).join(", "),
    inLanguage: lang,
    applicationSubCategory: "Version Control",
    softwareRequirements: "Android 8.0+",
  }, null, 2);
}

// ─── 主路由 ───────────────────────────────────────────────────────────────────

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders("GET, DELETE, OPTIONS") });
    }

    if (request.method === "GET") {
      switch (url.pathname) {
        case "/":
          return handleLanding(request, url);
        case "/sitemap.xml":
          return handleSitemap();
        case "/auth":
          return handleAuth(url, env);
        case "/callback":
          return await handleCallback(url, env, request);
        case "/health":
          return json({ ok: true, ts: Date.now() });
      }
    }

    if (request.method === "DELETE") {
      switch (url.pathname) {
        case "/token":
          return await handleRevokeToken(request, env);
        case "/grant":
          return await handleDeleteGrant(request, env);
      }
    }

    return env.ASSETS.fetch(request);
  },
};

// ─── 落地页 ───────────────────────────────────────────────────────────────────

function handleLanding(request: Request, url: URL): Response {
  const lang = detectLang(request, url);
  const t    = i18n[lang];
  const paramLang = url.searchParams.get("lang");

  // 语言切换器选项
  const langOptions: Array<{ code: Lang; flag: string; label: string }> = [
    { code: "zh", flag: "🇨🇳", label: "中文" },
    { code: "en", flag: "🇺🇸", label: "English" },
    { code: "ja", flag: "🇯🇵", label: "日本語" },
    { code: "ko", flag: "🇰🇷", label: "한국어" },
    { code: "es", flag: "🇪🇸", label: "Español" },
  ];

  const langMenuItems = langOptions.map(o =>
    `<a href="?lang=${o.code}" class="lang-item${lang === o.code ? " active" : ""}" rel="nofollow">${o.label}</a>`
  ).join("\n        ");

  // 功能特性卡片
  const featuresHtml = t.features.map(f =>
    `<div class="feat"><div class="emoji">${f.emoji}</div><strong>${f.name}</strong><span>${f.desc}</span></div>`
  ).join("\n      ");

  // hreflang 链接
  const hreflangHtml = LANGS.map(l =>
    `  <link rel="alternate" hreflang="${l}" href="${SITE_URL}/?lang=${l}">`
  ).join("\n");

  // JSON-LD
  const jsonLd = buildJsonLd(lang, t);

  const html = `<!DOCTYPE html>
<html lang="${lang}">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>${t.pageTitle}</title>
  <meta name="description" content="${t.metaDesc}">
  <link rel="canonical" href="${SITE_URL}/?lang=${lang}">
  <link rel="alternate" hreflang="x-default" href="${SITE_URL}/">
${hreflangHtml}

  <!-- Open Graph -->
  <meta property="og:type"        content="website">
  <meta property="og:url"         content="${SITE_URL}/?lang=${lang}">
  <meta property="og:title"       content="${t.pageTitle}">
  <meta property="og:description" content="${t.metaDesc}">
  <meta property="og:image"       content="${SITE_URL}/logo.png">
  <meta property="og:image:width"  content="512">
  <meta property="og:image:height" content="512">
  <meta property="og:locale"      content="${langToLocale(lang)}">
  <meta property="og:site_name"   content="GitMob">

  <!-- Twitter Card -->
  <meta name="twitter:card"        content="summary">
  <meta name="twitter:title"       content="${t.pageTitle}">
  <meta name="twitter:description" content="${t.metaDesc}">
  <meta name="twitter:image"       content="${SITE_URL}/logo.png">

  <!-- JSON-LD SoftwareApplication -->
  <script type="application/ld+json">
${jsonLd}
  </script>

  <link rel="icon" href="/logo.png" type="image/png">
  <link rel="apple-touch-icon" href="/logo.png">
  <style>
    :root {
      --accent:#FF6B4A; --bg:#0F1117; --card-bg:#161B25;
      --border:#2A3347; --text:#E8EAF0; --subtext:#9BA3BA; --feat-bg:#1E2535;
    }
    [data-theme="light"] {
      --bg:#F8FAFC; --card-bg:#FFFFFF; --border:#E2E8F0;
      --text:#0F172A; --subtext:#64748B; --feat-bg:#F1F5F9;
    }
    *{box-sizing:border-box;margin:0;padding:0}
    body{
      font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,system-ui,sans-serif;
      background:var(--bg);color:var(--text);min-height:100vh;
      display:flex;align-items:center;justify-content:center;
      padding:24px;transition:background 0.6s ease;
    }
    @keyframes fadeInUp{from{opacity:0;transform:translateY(40px)}to{opacity:1;transform:translateY(0)}}
    @keyframes float{0%,100%{transform:translateY(0)}50%{transform:translateY(-15px)}}
    .card{
      background:var(--card-bg);border:1px solid var(--border);border-radius:32px;
      padding:60px 52px;max-width:920px;width:100%;
      box-shadow:0 30px 60px -15px rgb(0 0 0/.3);position:relative;
      animation:fadeInUp 0.9s cubic-bezier(.4,0,.2,1) backwards;
    }
    /* 右上角控件组 */
    .controls{position:absolute;top:28px;right:28px;display:flex;gap:6px;align-items:center}
    .icon-btn{
      width:44px;height:44px;border:none;background:transparent;
      font-size:22px;cursor:pointer;color:var(--subtext);
      border-radius:50%;transition:background 0.2s;display:flex;align-items:center;justify-content:center;
    }
    .icon-btn:hover{background:var(--feat-bg)}
    /* 语言选择器 */
    .lang-selector{position:relative}
    .lang-menu{
      display:none;position:absolute;right:0;top:50px;
      background:var(--card-bg);border:1px solid var(--border);
      border-radius:14px;padding:6px;width:auto;min-width:auto;
      box-shadow:0 8px 24px rgb(0 0 0/.25);z-index:200;
    }
    .lang-menu.open{display:block}
    .lang-item{
      display:block;padding:9px 13px;text-decoration:none;
      color:var(--text);border-radius:9px;font-size:14px;
      transition:background 0.15s;
    }
    .lang-item:hover{background:var(--feat-bg)}
    .lang-item.active{color:var(--accent);font-weight:600}
    /* Logo */
    .logo{
      width:128px;height:128px;display:block;margin:0 auto 28px;
      border-radius:32px;box-shadow:0 20px 30px -10px rgb(0 0 0/.2);
      animation:float 3.5s ease-in-out infinite;
    }
    h1{font-size:42px;font-weight:700;letter-spacing:-2px;color:var(--accent);text-align:center;margin-bottom:10px}
    .subtitle{font-size:18px;line-height:1.6;color:var(--subtext);text-align:center;margin-bottom:48px}
    /* 按钮组 */
    .btn-group{display:flex;gap:20px;justify-content:center;flex-wrap:wrap;margin-bottom:56px}
    .btn{
      padding:18px 40px;font-size:17px;font-weight:600;border-radius:9999px;
      text-decoration:none;display:inline-flex;align-items:center;gap:10px;
      transition:all 0.3s ease;
    }
    .btn-primary{background:var(--accent);color:#fff}
    .btn-primary:hover{transform:translateY(-4px) scale(1.04)}
    .btn-ghost{background:transparent;color:var(--subtext);border:2px solid var(--border)}
    .btn-ghost:hover{border-color:var(--accent);color:var(--accent)}
    /* 功能网格 */
    .features{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:16px}
    .feat{
      background:var(--feat-bg);border-radius:24px;padding:24px 18px;
      text-align:center;transition:all 0.4s ease;
    }
    .feat:hover{transform:translateY(-8px);box-shadow:0 20px 30px -10px rgb(0 0 0/.15)}
    .feat .emoji{font-size:36px;margin-bottom:14px}
    .feat strong{font-size:16px;margin-bottom:6px;display:block}
    .feat span{font-size:13.5px;color:var(--subtext)}
    .footer{margin-top:56px;text-align:center;font-size:14px;color:var(--subtext);opacity:.85}
    @media(max-width:640px){.card{padding:48px 28px}.logo{width:108px;height:108px}h1{font-size:36px}}
  </style>
</head>
<body>
  <div class="card">
    <div class="controls">
      <!-- 语言切换器 -->
      <div class="lang-selector">
        <button class="icon-btn" id="langBtn" aria-label="Select language" aria-expanded="false">🌐</button>
        <div class="lang-menu" id="langMenu" role="menu">
        ${langMenuItems}
        </div>
      </div>
      <!-- 深浅色切换 -->
      <button class="icon-btn" id="themeToggle" aria-label="Toggle theme">🌙</button>
    </div>

    <img src="/logo.png" alt="GitMob Logo" class="logo" width="128" height="128">
    <h1>GitMob</h1>
    <p class="subtitle">${t.h1Sub}<br><strong>${t.stack}</strong></p>

    <div class="btn-group">
      <a href="${REPO_URL}/releases" class="btn btn-primary" target="_blank" rel="noopener noreferrer">${t.downloadBtn}</a>
      <a href="${REPO_URL}" class="btn btn-ghost" target="_blank" rel="noopener noreferrer">${t.repoBtn}</a>
    </div>

    <div class="features" aria-label="Features">
      ${featuresHtml}
    </div>

    <div class="footer">${t.footer}</div>
  </div>

  <script>
    // 主题切换
    var htmlEl = document.documentElement;
    var themeToggle = document.getElementById('themeToggle');
    function setTheme(t) {
      htmlEl.setAttribute('data-theme', t);
      themeToggle.textContent = t === 'dark' ? '☀️' : '🌙';
      localStorage.setItem('gitmob-theme', t);
    }
    var savedTheme = localStorage.getItem('gitmob-theme');
    setTheme(savedTheme || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
    themeToggle.addEventListener('click', function() {
      setTheme((htmlEl.getAttribute('data-theme') || 'dark') === 'dark' ? 'light' : 'dark');
    });

    // 语言切换器
    var langBtn = document.getElementById('langBtn');
    var langMenu = document.getElementById('langMenu');
    langBtn.addEventListener('click', function(e) {
      e.stopPropagation();
      var isOpen = langMenu.classList.toggle('open');
      langBtn.setAttribute('aria-expanded', isOpen ? 'true' : 'false');
    });
    document.addEventListener('click', function() {
      langMenu.classList.remove('open');
      langBtn.setAttribute('aria-expanded', 'false');
    });
    langMenu.addEventListener('click', function(e) { e.stopPropagation(); });
  </script>
</body>
</html>`;

  const headers = new Headers({
    "Content-Type": "text/html;charset=UTF-8",
    ...corsHeaders(),
    ...securityHeaders(),
  });

  // 用户通过 ?lang= 显式选择语言时，写入 Cookie 持久化偏好
  if (paramLang && LANGS.includes(paramLang as Lang)) {
    headers.set("Set-Cookie", `gitmob-lang=${lang}; Path=/; Max-Age=31536000; SameSite=Lax`);
  }

  return new Response(html, { headers });
}

// ─── Sitemap ──────────────────────────────────────────────────────────────────

function handleSitemap(): Response {
  const today = new Date().toISOString().split("T")[0];
  const hreflangEntries = LANGS.map(l =>
    `    <xhtml:link rel="alternate" hreflang="${l}" href="${SITE_URL}/?lang=${l}"/>`
  ).join("\n");

  const xml = `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"
        xmlns:xhtml="http://www.w3.org/1999/xhtml">
  <url>
    <loc>${SITE_URL}/</loc>
    <xhtml:link rel="alternate" hreflang="x-default" href="${SITE_URL}/"/>
${hreflangEntries}
    <lastmod>${today}</lastmod>
    <changefreq>monthly</changefreq>
    <priority>1.0</priority>
  </url>
</urlset>`;

  return new Response(xml, {
    headers: {
      "Content-Type": "application/xml;charset=UTF-8",
      "Cache-Control": "public, max-age=86400",
    },
  });
}

// ─── OAuth 流程 ───────────────────────────────────────────────────────────────

function handleAuth(url: URL, env: Env): Response {
  const state = crypto.randomUUID();
  const force = url.searchParams.get("force") === "1";
  const ghUrl = new URL("https://github.com/login/oauth/authorize");
  ghUrl.searchParams.set("client_id",    env.GITHUB_CLIENT_ID);
  ghUrl.searchParams.set("redirect_uri", `${url.origin}/callback`);
  ghUrl.searchParams.set("scope",        SCOPES);
  ghUrl.searchParams.set("state",        state);
  ghUrl.searchParams.set("response_mode","query");
  if (force) ghUrl.searchParams.set("prompt", "consent");
  return Response.redirect(ghUrl.toString(), 302);
}

async function handleCallback(url: URL, env: Env, request: Request): Promise<Response> {
  const lang  = detectLang(request, url);
  const code  = url.searchParams.get("code");
  const error = url.searchParams.get("error");

  if (error || !code) {
    const desc = url.searchParams.get("error_description") ?? "authorization_failed";
    return htmlRedirect(`${APP_SCHEME}?error=${encodeURIComponent(desc)}`, true, lang);
  }

  try {
    const res = await fetch("https://github.com/login/oauth/access_token", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        client_id:     env.GITHUB_CLIENT_ID,
        client_secret: env.GITHUB_CLIENT_SECRET,
        code,
      }),
    });

    if (!res.ok) throw new Error(`GitHub returned ${res.status}`);
    const data = await res.json() as any;

    if (data.error || !data.access_token) {
      const desc = data.error_description ?? data.error ?? "token_exchange_failed";
      return htmlRedirect(`${APP_SCHEME}?error=${encodeURIComponent(desc)}`, true, lang);
    }

    return htmlRedirect(`${APP_SCHEME}?token=${encodeURIComponent(data.access_token)}`, false, lang);
  } catch (err) {
    const msg = err instanceof Error ? err.message : "unknown_error";
    return htmlRedirect(`${APP_SCHEME}?error=${encodeURIComponent(msg)}`, true, lang);
  }
}

function htmlRedirect(deepLink: string, isError: boolean, lang: Lang = "en"): Response {
  const t     = i18n[lang];
  const title   = isError ? t.authFail    : t.authSuccess;
  const message = isError ? t.authFailMsg : t.authSuccessMsg;
  const color   = isError ? "#F87171"     : "#4ADE80";

  const html = `<!DOCTYPE html>
<html lang="${lang}">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>GitMob — ${title}</title>
  <link rel="icon" href="/logo.png" type="image/png">
  <style>
    *{box-sizing:border-box;margin:0;padding:0}
    body{font-family:-apple-system,sans-serif;background:#0F1117;color:#E8EAF0;min-height:100vh;display:flex;align-items:center;justify-content:center;padding:24px}
    .card{background:#161B25;border:1px solid #2A3347;border-radius:28px;padding:48px 36px;max-width:420px;width:100%;text-align:center}
    .status{font-size:56px;margin-bottom:20px}
    h2{font-size:24px;font-weight:700;color:${color};margin-bottom:12px}
    p{font-size:15.5px;color:#9BA3BA;line-height:1.6;margin-bottom:32px}
    .btn{display:block;padding:16px 36px;background:#FF6B4A;color:#fff;border-radius:9999px;text-decoration:none;font-weight:600;font-size:16px;cursor:pointer;width:100%;border:none}
    .hint{font-size:13px;color:#5C6580;margin-top:20px}
  </style>
</head>
<body>
  <div class="card">
    <div class="status">${isError ? "⚠️" : "✅"}</div>
    <h2>${title}</h2>
    <p>${message}</p>
    <button class="btn" id="openBtn">${t.openAppBtn}</button>
    <p class="hint" id="hint"></p>
  </div>
  <script>
    var deepLink = ${JSON.stringify(deepLink)};
    var hint = document.getElementById('hint');
    var hintText = ${JSON.stringify(t.openHint)};
    function tryOpen() { window.location.href = deepLink; }
    document.getElementById('openBtn').addEventListener('click', tryOpen);
    setTimeout(function() {
      tryOpen();
      setTimeout(function() { hint.textContent = hintText; }, 2000);
    }, 300);
  </script>
</body>
</html>`;

  return new Response(html, {
    headers: { "Content-Type": "text/html;charset=UTF-8", ...corsHeaders(), ...securityHeaders() },
  });
}

// ─── Token 管理 ───────────────────────────────────────────────────────────────

async function handleRevokeToken(request: Request, env: Env): Promise<Response> {
  const token = extractBearerToken(request);
  if (!token) return json({ ok: false, error: "missing_token" }, 400);
  try {
    const res = await githubAppsApi("DELETE", `/applications/${env.GITHUB_CLIENT_ID}/token`, { access_token: token }, env);
    if (res.status === 204 || res.status === 404) return json({ ok: true, action: "token_revoked" });
    return json({ ok: false, error: `github_${res.status}` }, 502);
  } catch {
    return json({ ok: false, error: "unknown" }, 502);
  }
}

async function handleDeleteGrant(request: Request, env: Env): Promise<Response> {
  const token = extractBearerToken(request);
  if (!token) return json({ ok: false, error: "missing_token" }, 400);
  try {
    const res = await githubAppsApi("DELETE", `/applications/${env.GITHUB_CLIENT_ID}/grant`, { access_token: token }, env);
    if (res.status === 204 || res.status === 404) return json({ ok: true, action: "grant_deleted" });
    return json({ ok: false, error: `github_${res.status}` }, 502);
  } catch {
    return json({ ok: false, error: "unknown" }, 502);
  }
}

// ─── 辅助函数 ─────────────────────────────────────────────────────────────────

async function githubAppsApi(method: string, path: string, body: Record<string, string>, env: Env): Promise<Response> {
  const credentials = btoa(`${env.GITHUB_CLIENT_ID}:${env.GITHUB_CLIENT_SECRET}`);
  return fetch(`https://api.github.com${path}`, {
    method,
    headers: {
      Authorization:         `Basic ${credentials}`,
      Accept:                "application/vnd.github+json",
      "Content-Type":        "application/json",
      "User-Agent":          "GitMob-OAuth-Worker/2.0",
      "X-GitHub-Api-Version":"2022-11-28",
    },
    body: JSON.stringify(body),
  });
}

function extractBearerToken(request: Request): string | null {
  const auth  = request.headers.get("Authorization") ?? "";
  const match = auth.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : null;
}

function corsHeaders(methods = "GET, DELETE, OPTIONS"): Record<string, string> {
  return {
    "Access-Control-Allow-Origin":  "*",
    "Access-Control-Allow-Methods": methods,
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
  };
}

function securityHeaders(): Record<string, string> {
  return {
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains; preload",
    "X-Content-Type-Options":    "nosniff",
    "X-Frame-Options":           "DENY",
    "Referrer-Policy":           "strict-origin-when-cross-origin",
    "Content-Security-Policy":   "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:;",
    "Permissions-Policy":        "camera=(), microphone=(), geolocation=()",
  };
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...corsHeaders(), ...securityHeaders() },
  });
}