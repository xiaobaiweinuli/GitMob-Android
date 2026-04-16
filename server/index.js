import { Hono } from 'hono'
import { serve } from '@hono/node-server'
import { serveStatic } from '@hono/node-server/serve-static'
import { cors } from 'hono/cors'
import { join, dirname } from 'path'
import { fileURLToPath } from 'url'
import { existsSync, mkdirSync, writeFileSync, readFileSync } from 'fs'

import reposRouter from './routes/repos.js'
import gitRouter from './routes/git.js'
import sshRouter from './routes/ssh.js'
import githubRouter from './routes/github.js'

const __dirname = dirname(fileURLToPath(import.meta.url))
const ROOT = join(__dirname, '..')

const DATA_DIR = join(ROOT, 'data')
const REPOS_FILE = join(DATA_DIR, 'repos.json')
const SETTINGS_FILE = join(DATA_DIR, 'settings.json')
if (!existsSync(DATA_DIR)) mkdirSync(DATA_DIR, { recursive: true })
if (!existsSync(REPOS_FILE)) writeFileSync(REPOS_FILE, JSON.stringify([]), 'utf-8')
if (!existsSync(SETTINGS_FILE)) writeFileSync(SETTINGS_FILE, JSON.stringify({}), 'utf-8')

export { REPOS_FILE, SETTINGS_FILE }

const app = new Hono()
app.use('*', cors())

app.route('/api/repos', reposRouter)
app.route('/api/git', gitRouter)
app.route('/api/ssh', sshRouter)
app.route('/api/github', githubRouter)

app.get('/api/ping', (c) => c.json({ ok: true, version: '1.1.0' }))

app.use('/*', serveStatic({ root: join(ROOT, 'public') }))

app.get('*', (c) => {
  try {
    const html = readFileSync(join(ROOT, 'public/index.html'), 'utf-8')
    return c.html(html)
  } catch {
    return c.text('Not found', 404)
  }
})

const PORT = process.env.PORT || 5493
const HOST = '127.0.0.1'

serve({ fetch: app.fetch, port: PORT, hostname: HOST }, () => {
  console.log(`\n  ██████╗ ██╗████████╗███╗   ███╗ ██████╗ ██████╗`)
  console.log(`  ██╔════╝ ██║╚══██╔══╝████╗ ████║██╔═══██╗██╔══██╗`)
  console.log(`  ██║  ███╗██║   ██║   ██╔████╔██║██║   ██║██████╔╝`)
  console.log(`  ██║   ██║██║   ██║   ██║╚██╔╝██║██║   ██║██╔══██╗`)
  console.log(`  ╚██████╔╝██║   ██║   ██║ ╚═╝ ██║╚██████╔╝██████╔╝`)
  console.log(`   ╚═════╝ ╚═╝   ╚═╝   ╚═╝     ╚═╝ ╚═════╝ ╚═════╝\n`)
  console.log(`  🚀 服务启动: http://${HOST}:${PORT}`)
  console.log(`  📱 浏览器访问: http://localhost:${PORT}\n`)
})
