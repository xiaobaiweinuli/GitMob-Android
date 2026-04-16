import simpleGit from 'simple-git'

export function getGit(p) { return simpleGit(p) }

export async function getRepoStatus(repoPath) {
  const git = getGit(repoPath)
  const isRepo = await git.checkIsRepo().catch(() => false)
  if (!isRepo) throw new Error('NOT_GIT_REPO')

  const [status, branchInfo, remotes] = await Promise.all([
    git.status(),
    git.branch(),
    git.getRemotes(true).catch(() => []),
  ])

  let lastCommit = null
  try {
    const log = await git.log({ maxCount: 1 })
    if (log.latest) {
      lastCommit = {
        shortHash: log.latest.hash.slice(0, 7),   // 统一用 shortHash
        message:   log.latest.message,
        date:      log.latest.date,
        author:    log.latest.author_name,
      }
    }
  } catch (_) {}

  const origin = remotes.find(r => r.name === 'origin')

  return {
    branch:       status.current,
    tracking:     status.tracking,          // null 表示无 upstream
    ahead:        status.ahead,
    behind:       status.behind,
    staged:       status.staged.length,
    modified:     status.modified.length,
    notAdded:     status.not_added.length,
    deleted:      status.deleted.length,
    conflicted:   status.conflicted.length,
    totalChanges: status.staged.length + status.modified.length +
                  status.not_added.length + status.deleted.length,
    files:        status.files,
    remoteUrl:    origin?.refs?.fetch || null,
    hasRemote:    !!origin,
    branches:     branchInfo.all,
    lastCommit,
    isClean:      status.isClean(),
  }
}

export async function getLog(repoPath, maxCount = 30) {
  const git = getGit(repoPath)
  const log = await git.log({ maxCount })
  return log.all.map(c => ({
    hash:      c.hash,
    shortHash: c.hash.slice(0, 7),
    message:   c.message,
    body:      c.body || '',
    date:      c.date,
    author:    c.author_name,
    email:     c.author_email,
  }))
}

export async function getCommitDetail(repoPath, hash) {
  const git = getGit(repoPath)
  const [stat, diff] = await Promise.all([
    git.show(['--stat', '--format=fuller', hash]),
    git.show(['--patch', '--format=', hash]),
  ])
  return { stat, diff }
}

export async function getDiff(repoPath, staged = false) {
  const git = getGit(repoPath)
  return staged
    ? git.diff(['--cached', '--stat'])
    : git.diff(['HEAD', '--stat']).catch(() => git.diff(['--stat']))
}

export async function getFileDiff(repoPath, filePath) {
  const git = getGit(repoPath)
  return git.diff(['HEAD', '--', filePath])
    .catch(() => git.diff(['--cached', '--', filePath]))
}

export async function stageFiles(repoPath, files) {
  const git = getGit(repoPath)
  if (!files || files.length === 0) await git.add('-A')
  else await git.add(files)
  return { ok: true }
}

export async function unstageFiles(repoPath, files) {
  const git = getGit(repoPath)
  if (!files || files.length === 0) await git.reset(['HEAD'])
  else await git.reset(['HEAD', '--', ...files])
  return { ok: true }
}

export async function commitOnly(repoPath, message) {
  const git = getGit(repoPath)
  const r = await git.commit(message)
  return { commit: r.commit, summary: r.summary }
}

export async function stageAndCommit(repoPath, message) {
  const git = getGit(repoPath)
  await git.add('-A')
  const r = await git.commit(message)
  return { commit: r.commit, summary: r.summary }
}

export async function pushRepo(repoPath, { force = false, branch = null } = {}) {
  const git = getGit(repoPath)
  const status = await git.status()
  const currentBranch = branch || status.current

  if (!force && status.tracking && status.ahead === 0) {
    return { skipped: true, reason: 'nothing_to_push', ahead: 0 }
  }

  // 用数组形式，确保 --set-upstream 生效
  const args = ['--set-upstream', 'origin', currentBranch]
  if (force) args.push('--force')
  const result = await git.push(args)
  return { ok: true, result }
}

export async function setRemoteUrl(repoPath, url) {
  const git = getGit(repoPath)
  const remotes = await git.getRemotes().catch(() => [])
  if (remotes.some(r => r.name === 'origin')) {
    await git.remote(['set-url', 'origin', url])
  } else {
    await git.addRemote('origin', url)
  }
  return { ok: true }
}

export async function initLocalRepo(repoPath) {
  const git = getGit(repoPath)
  const isRepo = await git.checkIsRepo().catch(() => false)
  if (isRepo) return { ok: true, alreadyInit: true }
  await git.init()
  return { ok: true, alreadyInit: false }
}

export async function pullRepo(repoPath, { force = false } = {}) {
  const git = getGit(repoPath)
  await git.fetch('origin').catch(() => {})
  const status = await git.status()

  if (!force && status.tracking && status.behind === 0) {
    return { skipped: true, reason: 'already_up_to_date', behind: 0 }
  }

  if (force) {
    await git.reset(['--hard', `origin/${status.current}`])
    return { mode: 'force-reset', ok: true }
  }

  const result = await git.pull(['--rebase'])
  return { ok: true, result }
}

export async function initAndPush(repoPath, { remoteUrl, branch = 'main', message = 'Initial commit' } = {}) {
  const git = getGit(repoPath)

  const isRepo = await git.checkIsRepo().catch(() => false)
  if (!isRepo) await git.init()

  const remotes = await git.getRemotes().catch(() => [])
  if (remotes.some(r => r.name === 'origin')) {
    await git.remote(['set-url', 'origin', remoteUrl])
  } else {
    await git.addRemote('origin', remoteUrl)
  }

  const status = await git.status()
  if (status.files.length > 0 || !status.isClean()) {
    await git.add('-A')
    await git.commit(message)
  }

  await git.branch(['-M', branch])
  await git.push(['-u', 'origin', branch, '--force'])
  return { ok: true }
}

export async function cloneRepo(remoteUrl, localPath, { branch = null } = {}) {
  const { mkdir } = await import('fs/promises')
  await mkdir(localPath, { recursive: true })
  const git = simpleGit()
  const args = ['--progress']
  if (branch) args.push('-b', branch)
  await git.clone(remoteUrl, localPath, args)
  return { ok: true, path: localPath }
}

export async function createBranch(repoPath, branchName, checkout = true) {
  const git = getGit(repoPath)
  if (checkout) await git.checkoutLocalBranch(branchName)
  else await git.branch([branchName])
  return { branch: branchName }
}

export async function checkoutBranch(repoPath, branch) {
  const git = getGit(repoPath)
  await git.checkout(branch)
  return { branch }
}

export async function revertCommit(repoPath, hash) {
  const git = getGit(repoPath)
  await git.revert([hash, '--no-edit'])
  return { ok: true }
}

export async function resetToCommit(repoPath, hash, mode = 'mixed') {
  const git = getGit(repoPath)
  await git.reset([`--${mode}`, hash])
  return { ok: true }
}
