package com.gitmob.app.core.network

/**
 * 全局分页尺寸、固定容量与单次扫描预算。Repository 的 GraphQL `first`/`last`/`limit`、
 * REST `per_page`、扫描停止条件及同源截断判断统一引用这里。
 *
 * 修改值前确认对应 GitHub API 的上限；固定展示容量与扫描预算不等于服务端硬上限。
 * 明确的计数、单条结果或父提交对探测可保留 0/1/2，不作为普通分页配置。
 */
object PageSize {

    // ──────────────── 请求容量（分页列表及固定预取）────────────────

    /** 仓库列表（viewer.repositories / repositoryOwner.repositories） */
    const val REPOS = 20

    /** 星标仓库列表（viewer.starredRepositories / user.starredRepositories） */
    const val STARRED_REPOS = 20

    /** 列表（Chip 横排）：viewer.lists */
    const val STAR_LISTS = 20

    /** 列表内仓库（list.items） */
    const val LIST_ITEMS = 20

    /** 通用用户名单：关注者 / 关注 / 仓库 Watchers / 组织成员 */
    const val USER_LIST = 30

    /** 分支列表（refs） */
    const val BRANCHES = 50

    /** REST compare 提交分页，与文件总容量分开设置。 */
    const val COMPARE_COMMITS = 30

    /** Labels, assignees, milestones and reviewer candidate connections. */
    const val METADATA = 20

    /** 收件箱通知（REST per_page） */
    const val NOTIFICATIONS = 30

    /** 工作项：Issues / Pull Requests / Discussions */
    const val WORK_ITEMS = 20

    /** Repository issue and issue comment connections. */
    const val REPO_ISSUES = 20
    const val ISSUE_COMMENTS = 20

    /** User content edit history connections. */
    const val CONTENT_EDITS = 20

    /** Gist 列表底层扫描页和 UI 目标页尺寸 */
    const val GISTS = 20

    /** 普通组织列表，以及创建仓库时的所有者组织选择器。 */
    const val ORGS = 20

    /** Releases REST pages. */
    const val RELEASES = 30

    /** Pull request review connection capacity. */
    const val PULL_REQUEST_REVIEWS = 30

    /** Pull request review thread connection capacity. */
    const val PULL_REQUEST_REVIEW_THREADS = 50

    /** Pull request review comment connection capacity. */
    const val PULL_REQUEST_REVIEW_COMMENTS = 50

    /** Pull request commit connection capacity. */
    const val PULL_REQUEST_COMMITS = 50

    /** Pull request files REST page capacity. */
    const val PULL_REQUEST_FILES = 100

    /** Commit parent connection capacity for commit detail data. */
    const val COMMIT_PARENTS = 20

    /** Repository discussion category connection capacity. */
    const val DISCUSSION_CATEGORIES = 100

    /** Repository contributors REST page capacity. */
    const val CONTRIBUTORS = 50

    /** GitHub Actions workflow REST list capacity. */
    const val ACTION_WORKFLOWS = 100

    /** GitHub Actions run REST page capacity. */
    const val ACTION_RUNS = 30

    /** GitHub Actions job REST list capacity. */
    const val ACTION_JOBS = 100

    /** GitHub Actions artifact REST list capacity. */
    const val ACTION_ARTIFACTS = 100

    // ──────────────── 固定容量（非分页，不可随意调大）────────────────

    /** REST compare 最多返回的文件总数，同时用于裁剪和可能截断的判断。 */
    const val COMPARE_FILES_LIMIT = 300

    /** 每个 Gist 的文件元数据预取容量，同时用于文件数量封顶标记。 */
    const val GIST_FILE_METADATA_LIMIT = 100

    /**
     * 仓库话题标签上限（repositoryTopics）。
     * GitHub 每个仓库最多 20 个 topic；此处使用固定展示容量，不分页。
     */
    const val TOPICS_PER_REPO = 10

    /**
     * 置顶仓库上限（User/Organization.pinnedItems）。
     * GitHub 设计最多固定 6 个项目，固定值，不分页。
     */
    const val PINNED_ITEMS = 6

    /**
     * 归属列表扫描上限（getListsContaining：查某仓库属于哪些列表）。
     * 语义上是"扫描当前用户所有列表以检测成员关系"，不是 UI 分页尺寸。
     * 上限 20 = 与 STAR_LISTS 保持一致，超过时存在漏判风险（极罕见）。
     */
    const val STAR_LISTS_SCAN_LIMIT = 20

    /**
     * 归属列表扫描时每个列表的预取项数（getListsContaining 内部）。
     * 每个列表前 100 个仓库 id 用于判断当前仓库是否在其中；超过 100 项的列表存在漏判。
     */
    const val LIST_ITEMS_SCAN_LIMIT = 100

    /**
     * 首页/资料页组织 BottomSheet 预览条数（不分页，轻量预览语义）。
     * 见 UserRepository.getOrganizations() 注释。
     */
    const val ORGS_PREVIEW = 30

    /**
     * 社交账号（socialAccounts）。GitHub 每用户上限约 10 个，不分页。
     */
    const val SOCIAL_ACCOUNTS = 10

    // ──────────────── 单次扫描预算（单位为源页/批次，不是节点数）────────────────

    /** 仓库搜索一次最多扫描的完整源页数，达到预算后由用户手动继续。 */
    const val REPO_SEARCH_SCAN_PAGE_LIMIT = 5

    /** Gist 分类/可见性过滤一次最多扫描的源批次数。 */
    const val GIST_SCAN_BATCH_LIMIT = 5
}
