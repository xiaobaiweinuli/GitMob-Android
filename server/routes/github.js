import { Hono } from 'hono'
import { readFileSync, writeFileSync } from 'fs'
import { SETTINGS_FILE } from '../index.js'

const router = new Hono()

function readSettings() {
  try { return JSON.parse(readFileSync(SETTINGS_FILE, 'utf-8')) } catch { return {} }
}
function writeSettings(s) {
  writeFileSync(SETTINGS_FILE, JSON.stringify(s, null, 2), 'utf-8')
}

// 获取/保存 PAT
router.get('/pat', (c) => {
  const s = readSettings()
  return c.json({ hasPat: !!s.githubPat, username: s.githubUsername || null })
})

router.post('/pat', async (c) => {
  const { pat } = await c.req.json()
  if (!pat) return c.json({ error: 'PAT_REQUIRED' }, 400)

  // 验证 PAT：调用 GitHub /user 接口
  const res = await fetch('https://api.github.com/user', {
    headers: { Authorization: `Bearer ${pat}`, 'User-Agent': 'GitMob/1.1' }
  }).catch(() => null)

  if (!res || !res.ok) return c.json({ error: 'INVALID_PAT' }, 401)

  const user = await res.json()
  const s = readSettings()
  s.githubPat = pat
  s.githubUsername = user.login
  writeSettings(s)
  return c.json({ ok: true, username: user.login, avatar: user.avatar_url })
})

router.delete('/pat', (c) => {
  const s = readSettings()
  delete s.githubPat
  delete s.githubUsername
  writeSettings(s)
  return c.json({ ok: true })
})

// 列出用户仓库
router.get('/repos', async (c) => {
  const { githubPat } = readSettings()
  if (!githubPat) return c.json({ error: 'NO_PAT' }, 401)

  const res = await fetch('https://api.github.com/user/repos?per_page=50&sort=updated', {
    headers: { Authorization: `Bearer ${githubPat}`, 'User-Agent': 'GitMob/1.1' }
  })
  if (!res.ok) return c.json({ error: 'API_ERROR' }, res.status)
  const repos = await res.json()
  return c.json(repos.map((r) => ({
    id: r.id, name: r.name, fullName: r.full_name,
    private: r.private, sshUrl: r.ssh_url, cloneUrl: r.clone_url,
    description: r.description, updatedAt: r.updated_at,
    defaultBranch: r.default_branch,
  })))
})

// 创建新仓库
router.post('/repos', async (c) => {
  const { githubPat } = readSettings()
  if (!githubPat) return c.json({ error: 'NO_PAT' }, 401)

  const { name, description = '', isPrivate = false, autoInit = false } = await c.req.json()
  if (!name) return c.json({ error: 'NAME_REQUIRED' }, 400)

  const res = await fetch('https://api.github.com/user/repos', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${githubPat}`,
      'Content-Type': 'application/json',
      'User-Agent': 'GitMob/1.1',
    },
    body: JSON.stringify({ name, description, private: isPrivate, auto_init: autoInit }),
  })

  const data = await res.json()
  if (!res.ok) return c.json({ error: data.message || 'CREATE_FAILED' }, res.status)

  return c.json({
    ok: true,
    name: data.name,
    fullName: data.full_name,
    sshUrl: data.ssh_url,
    cloneUrl: data.clone_url,
    htmlUrl: data.html_url,
    private: data.private,
    defaultBranch: data.default_branch,
  })
})

export default router
