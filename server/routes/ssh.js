import { Hono } from 'hono'
import {
  listKeys,
  testGitHubConnection,
  generateKey,
  getPublicKey,
  addKeyToAgent,
  checkSshConfig,
  writeGitHubSshConfig,
} from '../lib/ssh-manager.js'

const router = new Hono()

// 列出所有 SSH Key
router.get('/keys', async (c) => {
  const keys = await listKeys()
  return c.json(keys)
})

// 测试 GitHub 连接
router.get('/test', async (c) => {
  const keyName = c.req.query('key')
  const keyPath = keyName ? `${process.env.HOME}/.ssh/${keyName}` : null
  const result = await testGitHubConnection(keyPath)
  return c.json(result)
})

// 生成新 Key
router.post('/generate', async (c) => {
  const { name, email, type = 'ed25519' } = await c.req.json().catch(() => ({}))
  const result = await generateKey({ name, email, type })
  if (result.error) return c.json(result, result.error === 'KEY_EXISTS' ? 409 : 500)
  return c.json(result)
})

// 获取公钥内容
router.get('/pubkey/:name', async (c) => {
  const name = c.req.param('name')
  const result = await getPublicKey(name)
  if (result.error) return c.json(result, 404)
  return c.json(result)
})

// 添加 Key 到 ssh-agent
router.post('/agent/add', async (c) => {
  const { keyPath } = await c.req.json()
  if (!keyPath) return c.json({ error: 'KEY_PATH_REQUIRED' }, 400)
  const result = await addKeyToAgent(keyPath)
  return c.json(result)
})

// 检查 SSH config
router.get('/config', async (c) => {
  const result = await checkSshConfig()
  return c.json(result)
})

// 写入 GitHub SSH config
router.post('/config/github', async (c) => {
  const { keyName } = await c.req.json()
  if (!keyName) return c.json({ error: 'KEY_NAME_REQUIRED' }, 400)
  const result = await writeGitHubSshConfig(keyName)
  return c.json(result)
})

export default router
