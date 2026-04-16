import { Hono } from 'hono'
import { readFileSync, writeFileSync } from 'fs'
import { existsSync, statSync } from 'fs'
import { join } from 'path'
import { homedir } from 'os'
import { REPOS_FILE } from '../index.js'
import { getRepoStatus } from '../lib/git-ops.js'

const router = new Hono()

function readRepos() {
  try {
    return JSON.parse(readFileSync(REPOS_FILE, 'utf-8'))
  } catch {
    return []
  }
}

function writeRepos(repos) {
  writeFileSync(REPOS_FILE, JSON.stringify(repos, null, 2), 'utf-8')
}

function genId() {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6)
}

// 获取所有仓库列表（含状态）
router.get('/', async (c) => {
  const repos = readRepos()
  const results = await Promise.all(
    repos.map(async (repo) => {
      try {
        const status = await getRepoStatus(repo.path)
        return { ...repo, status, error: null }
      } catch (err) {
        return { ...repo, status: null, error: err.message }
      }
    })
  )
  return c.json(results)
})

// 添加仓库
router.post('/', async (c) => {
  const { path: repoPath, name } = await c.req.json()

  if (!repoPath) return c.json({ error: 'PATH_REQUIRED' }, 400)

  // 展开 ~ 路径
  const expanded = repoPath.startsWith('~')
    ? join(homedir(), repoPath.slice(1))
    : repoPath

  if (!existsSync(expanded)) {
    return c.json({ error: 'PATH_NOT_FOUND' }, 404)
  }

  const stat = statSync(expanded)
  if (!stat.isDirectory()) {
    return c.json({ error: 'NOT_A_DIRECTORY' }, 400)
  }

  const repos = readRepos()
  const exists = repos.find((r) => r.path === expanded)
  if (exists) return c.json({ error: 'ALREADY_ADDED' }, 409)

  const displayName = name || expanded.split('/').pop()
  const repo = {
    id: genId(),
    path: expanded,
    name: displayName,
    addedAt: new Date().toISOString(),
  }

  repos.push(repo)
  writeRepos(repos)

  let status = null
  try {
    status = await getRepoStatus(expanded)
  } catch (_) {}

  return c.json({ ...repo, status })
})

// 获取单个仓库状态
router.get('/:id/status', async (c) => {
  const id = c.req.param('id')
  const repos = readRepos()
  const repo = repos.find((r) => r.id === id)
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)

  try {
    const status = await getRepoStatus(repo.path)
    return c.json(status)
  } catch (err) {
    return c.json({ error: err.message }, 500)
  }
})

// 更新仓库名称
router.patch('/:id', async (c) => {
  const id = c.req.param('id')
  const { name } = await c.req.json()
  const repos = readRepos()
  const idx = repos.findIndex((r) => r.id === id)
  if (idx === -1) return c.json({ error: 'NOT_FOUND' }, 404)
  repos[idx].name = name
  writeRepos(repos)
  return c.json(repos[idx])
})

// 删除仓库（仅从列表移除，不删除文件）
router.delete('/:id', async (c) => {
  const id = c.req.param('id')
  let repos = readRepos()
  const len = repos.length
  repos = repos.filter((r) => r.id !== id)
  if (repos.length === len) return c.json({ error: 'NOT_FOUND' }, 404)
  writeRepos(repos)
  return c.json({ ok: true })
})

export default router
