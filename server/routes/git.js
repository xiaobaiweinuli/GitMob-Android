import { Hono } from 'hono'
import { readFileSync } from 'fs'
import { REPOS_FILE } from '../index.js'
import {
  getLog, getDiff, getFileDiff, getCommitDetail,
  stageFiles, unstageFiles, commitOnly, stageAndCommit,
  pushRepo, pullRepo, initAndPush, cloneRepo,
  createBranch, checkoutBranch,
  revertCommit, resetToCommit,
  setRemoteUrl, initLocalRepo,
} from '../lib/git-ops.js'

const router = new Hono()

function getRepo(id) {
  return JSON.parse(readFileSync(REPOS_FILE, 'utf-8')).find(r => r.id === id)
}

router.get('/:id/log', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  try { return c.json(await getLog(repo.path, Number(c.req.query('limit') || 30))) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.get('/:id/log/:hash', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  try { return c.json(await getCommitDetail(repo.path, c.req.param('hash'))) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.get('/:id/diff', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  try { return c.json({ diff: await getDiff(repo.path, c.req.query('staged') === 'true') }) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.get('/:id/diff/file', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const fp = c.req.query('path')
  if (!fp) return c.json({ error: 'PATH_REQUIRED' }, 400)
  try { return c.json({ diff: await getFileDiff(repo.path, fp) }) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/:id/stage', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const { files } = await c.req.json().catch(() => ({}))
  try { return c.json(await stageFiles(repo.path, files)) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/:id/unstage', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const { files } = await c.req.json().catch(() => ({}))
  try { return c.json(await unstageFiles(repo.path, files)) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/:id/commit', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const { message, stageAll = false } = await c.req.json()
  if (!message?.trim()) return c.json({ error: 'MESSAGE_REQUIRED' }, 400)
  try {
    const r = stageAll
      ? await stageAndCommit(repo.path, message.trim())
      : await commitOnly(repo.path, message.trim())
    return c.json({ ok: true, ...r })
  } catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/:id/push', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const { force = false, branch = null } = await c.req.json().catch(() => ({}))
  try { return c.json(await pushRepo(repo.path, { force, branch })) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/:id/pull', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const { force = false } = await c.req.json().catch(() => ({}))
  try { return c.json(await pullRepo(repo.path, { force })) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/:id/init-push', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const { remoteUrl, branch = 'main', message = 'Initial commit' } = await c.req.json()
  if (!remoteUrl) return c.json({ error: 'REMOTE_URL_REQUIRED' }, 400)
  try { return c.json(await initAndPush(repo.path, { remoteUrl, branch, message })) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/:id/branch', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const { name, checkout = true } = await c.req.json()
  if (!name) return c.json({ error: 'NAME_REQUIRED' }, 400)
  try { return c.json(await createBranch(repo.path, name, checkout)) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/:id/checkout', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const { branch } = await c.req.json()
  if (!branch) return c.json({ error: 'BRANCH_REQUIRED' }, 400)
  try { return c.json(await checkoutBranch(repo.path, branch)) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/:id/revert/:hash', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  try { return c.json(await revertCommit(repo.path, c.req.param('hash'))) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/:id/reset', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const { hash, mode = 'mixed' } = await c.req.json()
  if (!hash) return c.json({ error: 'HASH_REQUIRED' }, 400)
  try { return c.json(await resetToCommit(repo.path, hash, mode)) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

router.post('/clone', async (c) => {
  const { remoteUrl, localPath, branch } = await c.req.json()
  if (!remoteUrl || !localPath) return c.json({ error: 'PARAMS_REQUIRED' }, 400)
  const { homedir } = await import('os')
  const { join } = await import('path')
  const expanded = localPath.startsWith('~') ? join(homedir(), localPath.slice(1)) : localPath
  try { return c.json(await cloneRepo(remoteUrl, expanded, { branch })) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

// 设置/更换远程地址
router.post('/:id/remote', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  const { url } = await c.req.json()
  if (!url) return c.json({ error: 'URL_REQUIRED' }, 400)
  try { return c.json(await setRemoteUrl(repo.path, url)) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

// 仅本地 git init
router.post('/:id/init-local', async (c) => {
  const repo = getRepo(c.req.param('id'))
  if (!repo) return c.json({ error: 'NOT_FOUND' }, 404)
  try { return c.json(await initLocalRepo(repo.path)) }
  catch (e) { return c.json({ error: e.message }, 500) }
})

export default router
