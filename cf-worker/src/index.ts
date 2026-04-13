/**
 * GitMob OAuth Worker
 * 路由:
 *   GET    /           → App 落地页
 *   GET    /auth        → 跳转 GitHub OAuth 授权（?force=1 强制重授权）
 *   GET    /callback    → 接收 code，换 token，HTML + JS 唤起 App
 *   GET    /health      → 健康检查
 *   DELETE /token       → 撤销 Token（token 失效，授权记录保留）
 *   DELETE /grant       → 删除授权 Grant（彻底移除 App 授权，需重新授权）
 *
 * 环境变量（CF Dashboard → Workers → Settings → Variables）:
 *   GITHUB_CLIENT_ID      明文
 *   GITHUB_CLIENT_SECRET  加密 Secret
 */

export interface Env {
  GITHUB_CLIENT_ID: string;
  GITHUB_CLIENT_SECRET: string;
  ASSETS: Fetcher;
}

const APP_SCHEME = "gitmob://oauth";
const REPO_URL = "https://github.com/xiaobaiweinuli/GitMob-Android";
const SCOPES = "repo,user,delete_repo,admin:public_key,workflow";

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders("GET, DELETE, OPTIONS") });
    }

    if (request.method === "GET") {
      switch (url.pathname) {
        case "/":
          return handleLanding();
        case "/auth":
          return handleAuth(url, env);
        case "/callback":
          return await handleCallback(url, env);
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

function securityHeaders(): Record<string, string> {
  return {
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains; preload",
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Content-Security-Policy":
      "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:;",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
  };
}

function handleLanding(): Response {
  const html = `<!DOCTYPE html>
<html lang="zh">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>GitMob — 手机端 GitHub 管理工具</title>
  <meta name="description" content="GitMob 是采用 Kotlin + Jetpack Compose + Material 3 打造的纯原生 Android GitHub 客户端。">
  <link rel="icon" href="/logo.png" type="image/png">
  <link rel="apple-touch-icon" href="/logo.png">
  <style>
    :root {
      --accent: #FF6B4A; --bg: #0F1117; --card-bg: #161B25;
      --border: #2A3347; --text: #E8EAF0; --subtext: #9BA3BA; --feat-bg: #1E2535;
    }
    [data-theme="light"] {
      --bg: #F8FAFC; --card-bg: #FFFFFF; --border: #E2E8F0;
      --text: #0F172A; --subtext: #64748B; --feat-bg: #F1F5F9;
    }
    * { box-sizing: border-box; margin:0; padding:0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, system-ui, sans-serif;
      background: var(--bg); color: var(--text); min-height: 100vh;
      display: flex; align-items: center; justify-content: center;
      padding: 24px; transition: background 0.6s ease;
    }
    @keyframes fadeInUp { from { opacity: 0; transform: translateY(40px); } to { opacity: 1; transform: translateY(0); } }
    @keyframes float { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-15px); } }
    .card {
      background: var(--card-bg); border: 1px solid var(--border); border-radius: 32px;
      padding: 60px 52px; max-width: 920px; width: 100%;
      box-shadow: 0 30px 60px -15px rgb(0 0 0 / 0.3); position: relative;
      animation: fadeInUp 0.9s cubic-bezier(0.4, 0, 0.2, 1) backwards;
    }
    .theme-toggle {
      position: absolute; top: 28px; right: 28px; width: 48px; height: 48px;
      border: none; background: transparent; font-size: 26px; cursor: pointer; color: var(--subtext);
    }
    .logo {
      width: 128px; height: 128px; display: block; margin: 0 auto 28px;
      border-radius: 32px; box-shadow: 0 20px 30px -10px rgb(0 0 0 / 0.2);
      animation: float 3.5s ease-in-out infinite;
    }
    h1 { font-size: 42px; font-weight: 700; letter-spacing: -2px; color: var(--accent); text-align: center; margin-bottom: 10px; }
    .subtitle { font-size: 18px; line-height: 1.6; color: var(--subtext); text-align: center; margin-bottom: 48px; }
    .btn-group { display: flex; gap: 20px; justify-content: center; flex-wrap: wrap; margin-bottom: 56px; }
    .btn { padding: 18px 40px; font-size: 17px; font-weight: 600; border-radius: 9999px; text-decoration: none; display: inline-flex; align-items: center; gap: 10px; transition: all 0.3s ease; }
    .btn-primary { background: var(--accent); color: #fff; }
    .btn-primary:hover { transform: translateY(-4px) scale(1.04); }
    .btn-ghost { background: transparent; color: var(--subtext); border: 2px solid var(--border); }
    .btn-ghost:hover { border-color: var(--accent); color: var(--accent); }
    .features { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 16px; }
    .feat { background: var(--feat-bg); border-radius: 24px; padding: 24px 18px; text-align: center; transition: all 0.4s ease; }
    .feat:hover { transform: translateY(-8px); box-shadow: 0 20px 30px -10px rgb(0 0 0 / 0.15); }
    .feat .emoji { font-size: 36px; margin-bottom: 14px; }
    .feat strong { font-size: 16px; margin-bottom: 6px; display: block; }
    .feat span { font-size: 13.5px; color: var(--subtext); }
    .footer { margin-top: 56px; text-align: center; font-size: 14px; color: var(--subtext); opacity: 0.85; }
    @media (max-width: 640px) { .card { padding: 48px 28px; } .logo { width: 108px; height: 108px; } h1 { font-size: 36px; } }
  </style>
</head>
<body>
  <div class="card">
    <button class="theme-toggle" id="themeToggle" aria-label="切换主题">🌙</button>
    <img src="/logo.png" alt="GitMob Logo" class="logo">
    <h1>GitMob</h1>
    <p class="subtitle">手机端 GitHub 原生管理工具<br><strong>Kotlin · Jetpack Compose · Material 3</strong></p>
    <div class="btn-group">
      <a href="${REPO_URL}/releases" class="btn btn-primary" target="_blank">📥 下载 APK</a>
      <a href="${REPO_URL}" class="btn btn-ghost" target="_blank">GitHub 仓库</a>
    </div>
    <div class="features">
      <div class="feat"><div class="emoji">📦</div><strong>仓库管理</strong><span>搜索、筛选、星标</span></div>
      <div class="feat"><div class="emoji">📂</div><strong>文件浏览</strong><span>任意分支、路径</span></div>
      <div class="feat"><div class="emoji">🌳</div><strong>分支操作</strong><span>创建、切换、管理</span></div>
      <div class="feat"><div class="emoji">🔀</div><strong>PR / Issues</strong><span>查看开放状态</span></div>
      <div class="feat"><div class="emoji">👤</div><strong>个人主页</strong><span>资料展示、关注列表</span></div>
      <div class="feat"><div class="emoji">⭐</div><strong>收藏夹</strong><span>仓库收藏、分组管理</span></div>
      <div class="feat"><div class="emoji">➕</div><strong>手动创建</strong><span>仓库、自动初始化</span></div>
      <div class="feat"><div class="emoji">🚀</div><strong>Actions</strong><span>工作流、日志查看</span></div>
      <div class="feat"><div class="emoji">👥</div><strong>多账号</strong><span>复制、切换管理</span></div>
      <div class="feat"><div class="emoji">📜</div><strong>提交历史</strong><span>commit 列表、diff 对比</span></div>
      <div class="feat"><div class="emoji">📤</div><strong>Releases</strong><span>发行版、产物下载</span></div>
      <div class="feat"><div class="emoji">💬</div><strong>讨论管理</strong><span>讨论列表与详情</span></div>
      <div class="feat"><div class="emoji">💻</div><strong>本地 Git</strong><span>clone、commit、push、pull</span></div>
      <div class="feat"><div class="emoji">📑</div><strong>文件选择器</strong><span>书签、多种排序</span></div>
      <div class="feat"><div class="emoji">🎨</div><strong>Material 3</strong><span>动态主题、极致体验</span></div>
      <div class="feat"><div class="emoji">🔎</div><strong>全局搜索</strong><span>仓库、用户、组织</span></div>
    </div>
    <div class="footer">纯原生 Android 应用（Jetpack Compose） · 完全开源</div>
  </div>
  <script>
    const htmlEl = document.documentElement;
    const toggle = document.getElementById('themeToggle');
    function setTheme(t) {
      htmlEl.setAttribute('data-theme', t);
      toggle.textContent = t === 'dark' ? '☀️' : '🌙';
      localStorage.setItem('gitmob-theme', t);
    }
    const saved = localStorage.getItem('gitmob-theme');
    setTheme(saved || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
    toggle.addEventListener('click', () => setTheme((htmlEl.getAttribute('data-theme')||'dark')==='dark'?'light':'dark'));
  </script>
</body>
</html>`;

  return new Response(html, {
    headers: { "Content-Type": "text/html;charset=UTF-8", ...corsHeaders(), ...securityHeaders() },
  });
}

function handleAuth(url: URL, env: Env): Response {
  const state = crypto.randomUUID();
  const force = url.searchParams.get("force") === "1";
  const ghUrl = new URL("https://github.com/login/oauth/authorize");
  ghUrl.searchParams.set("client_id", env.GITHUB_CLIENT_ID);
  ghUrl.searchParams.set("redirect_uri", `${url.origin}/callback`);
  ghUrl.searchParams.set("scope", SCOPES);
  ghUrl.searchParams.set("state", state);
  ghUrl.searchParams.set("response_mode", "query");
  if (force) ghUrl.searchParams.set("prompt", "consent");
  return Response.redirect(ghUrl.toString(), 302);
}

async function handleCallback(url: URL, env: Env): Promise<Response> {
  const code = url.searchParams.get("code");
  const error = url.searchParams.get("error");

  if (error || !code) {
    const desc = url.searchParams.get("error_description") ?? "authorization_failed";
    return htmlRedirect(`${APP_SCHEME}?error=${encodeURIComponent(desc)}`, true);
  }

  try {
    const res = await fetch("https://github.com/login/oauth/access_token", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({
        client_id: env.GITHUB_CLIENT_ID,
        client_secret: env.GITHUB_CLIENT_SECRET,
        code,
      }),
    });

    if (!res.ok) throw new Error(`GitHub returned ${res.status}`);
    const data = await res.json() as any;

    if (data.error || !data.access_token) {
      const desc = data.error_description ?? data.error ?? "token_exchange_failed";
      return htmlRedirect(`${APP_SCHEME}?error=${encodeURIComponent(desc)}`, true);
    }

    return htmlRedirect(`${APP_SCHEME}?token=${encodeURIComponent(data.access_token)}`, false);
  } catch (err) {
    const msg = err instanceof Error ? err.message : "unknown_error";
    return htmlRedirect(`${APP_SCHEME}?error=${encodeURIComponent(msg)}`, true);
  }
}

function htmlRedirect(deepLink: string, isError: boolean): Response {
  const title = isError ? "授权失败" : "授权成功";
  const color = isError ? "#F87171" : "#4ADE80";
  const message = isError
    ? "授权过程中出现错误，请返回 GitMob App 重试。"
    : "授权成功！正在自动跳转回 GitMob…";

  const html = `<!DOCTYPE html>
<html lang="zh">
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
    .btn{display:inline-block;padding:16px 36px;background:#FF6B4A;color:#fff;border-radius:9999px;text-decoration:none;font-weight:600;font-size:16px;cursor:pointer;width:100%;border:none}
    .hint{font-size:13px;color:#5C6580;margin-top:20px}
  </style>
</head>
<body>
  <div class="card">
    <div class="status">${isError ? "⚠️" : "✅"}</div>
    <h2>${title}</h2>
    <p>${message}</p>
    <button class="btn" id="openBtn">打开 GitMob</button>
    <p class="hint" id="hint"></p>
  </div>
  <script>
    var deepLink = ${JSON.stringify(deepLink)};
    function tryOpen() { window.location.href = deepLink; }
    document.getElementById('openBtn').addEventListener('click', tryOpen);
    setTimeout(function(){
      tryOpen();
      setTimeout(function(){
        document.getElementById('hint').textContent = '如果未自动跳转，请点击上方按钮手动打开 GitMob';
      }, 2000);
    }, 300);
  </script>
</body>
</html>`;

  return new Response(html, {
    headers: { "Content-Type": "text/html;charset=UTF-8", ...corsHeaders(), ...securityHeaders() },
  });
}

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

async function githubAppsApi(method: string, path: string, body: Record<string, string>, env: Env): Promise<Response> {
  const credentials = btoa(`${env.GITHUB_CLIENT_ID}:${env.GITHUB_CLIENT_SECRET}`);
  return fetch(`https://api.github.com${path}`, {
    method,
    headers: {
      Authorization: `Basic ${credentials}`,
      Accept: "application/vnd.github+json",
      "Content-Type": "application/json",
      "User-Agent": "GitMob-OAuth-Worker/2.0",
      "X-GitHub-Api-Version": "2022-11-28",
    },
    body: JSON.stringify(body),
  });
}

function extractBearerToken(request: Request): string | null {
  const auth = request.headers.get("Authorization") ?? "";
  const match = auth.match(/^Bearer\s+(.+)$/i);
  return match ? match[1].trim() : null;
}

function corsHeaders(methods = "GET, DELETE, OPTIONS"): Record<string, string> {
  return {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": methods,
    "Access-Control-Allow-Headers": "Content-Type, Authorization",
  };
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json", ...corsHeaders(), ...securityHeaders() },
  });
}
