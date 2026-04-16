import { exec } from 'child_process'
import { promisify } from 'util'
import { readdir, readFile, access } from 'fs/promises'
import { join } from 'path'
import { homedir } from 'os'

const execAsync = promisify(exec)
const SSH_DIR = join(homedir(), '.ssh')

/**
 * 列出所有 SSH 私钥
 */
export async function listKeys() {
  try {
    await access(SSH_DIR)
  } catch {
    return []
  }

  const files = await readdir(SSH_DIR).catch(() => [])
  const keys = []

  const pubFiles = files.filter((f) => f.endsWith('.pub'))

  for (const pubFile of pubFiles) {
    const name = pubFile.replace('.pub', '')
    const privFile = name

    // 判断私钥是否存在
    const hasPriv = files.includes(privFile)
    if (!hasPriv) continue

    let pubKey = ''
    let keyType = 'unknown'
    try {
      pubKey = await readFile(join(SSH_DIR, pubFile), 'utf-8')
      pubKey = pubKey.trim()
      keyType = pubKey.split(' ')[0] || 'unknown'
    } catch (_) {}

    keys.push({
      name,
      pubFile,
      privFile,
      keyType,
      pubKey,
      path: join(SSH_DIR, privFile),
    })
  }

  return keys
}

/**
 * 测试 GitHub SSH 连接
 */
export async function testGitHubConnection(keyPath = null) {
  const sshCmd = keyPath
    ? `ssh -i "${keyPath}" -T git@github.com -o StrictHostKeyChecking=no -o ConnectTimeout=10`
    : `ssh -T git@github.com -o StrictHostKeyChecking=no -o ConnectTimeout=10`

  try {
    const { stderr } = await execAsync(sshCmd)
    // GitHub 返回 stderr: "Hi username! You've successfully authenticated..."
    const output = stderr || ''
    const match = output.match(/Hi ([^!]+)!/)
    if (match) {
      return { success: true, username: match[1].trim(), message: output.trim() }
    }
    return { success: false, message: output.trim() }
  } catch (err) {
    const output = err.stderr || err.message || ''
    const match = output.match(/Hi ([^!]+)!/)
    if (match) {
      return { success: true, username: match[1].trim(), message: output.trim() }
    }
    return { success: false, message: output.trim() }
  }
}

/**
 * 生成新 SSH Key
 */
export async function generateKey({ name = 'id_ed25519_gitmob', email = '', type = 'ed25519' } = {}) {
  const keyPath = join(SSH_DIR, name)

  // 检查是否已存在
  try {
    await access(keyPath)
    return { error: 'KEY_EXISTS', path: keyPath }
  } catch (_) {}

  const comment = email || 'gitmob'
  const cmd = `ssh-keygen -t ${type} -C "${comment}" -f "${keyPath}" -N ""`

  try {
    await execAsync(cmd)
    const pubKey = await readFile(`${keyPath}.pub`, 'utf-8')
    return { success: true, name, path: keyPath, pubKey: pubKey.trim() }
  } catch (err) {
    return { error: err.message }
  }
}

/**
 * 读取公钥内容
 */
export async function getPublicKey(keyName) {
  const pubPath = join(SSH_DIR, `${keyName}.pub`)
  try {
    const content = await readFile(pubPath, 'utf-8')
    return { pubKey: content.trim() }
  } catch {
    return { error: 'NOT_FOUND' }
  }
}

/**
 * 检查 ssh-agent 并添加 key
 */
export async function addKeyToAgent(keyPath) {
  try {
    await execAsync(`ssh-add "${keyPath}"`)
    return { success: true }
  } catch (err) {
    return { error: err.message }
  }
}

/**
 * 检查 ~/.ssh/config 是否配置了 github.com
 */
export async function checkSshConfig() {
  const configPath = join(SSH_DIR, 'config')
  try {
    const content = await readFile(configPath, 'utf-8')
    const hasGitHub = content.includes('github.com')
    return { exists: true, hasGitHub, content }
  } catch {
    return { exists: false, hasGitHub: false, content: '' }
  }
}

/**
 * 写入 ~/.ssh/config 的 GitHub 配置
 */
export async function writeGitHubSshConfig(keyName) {
  const keyPath = join(SSH_DIR, keyName)
  const configPath = join(SSH_DIR, 'config')
  const { exec: execCb } = await import('child_process')
  const execP = promisify(execCb)

  const entry = `\nHost github.com\n  HostName github.com\n  User git\n  IdentityFile ${keyPath}\n  StrictHostKeyChecking no\n`

  try {
    let existing = ''
    try {
      existing = await readFile(configPath, 'utf-8')
    } catch (_) {}

    if (existing.includes('Host github.com')) {
      return { skipped: true, reason: 'already_configured' }
    }

    const { writeFile, chmod } = await import('fs/promises')
    await writeFile(configPath, existing + entry, 'utf-8')
    await chmod(configPath, 0o600)
    return { success: true }
  } catch (err) {
    return { error: err.message }
  }
}
