package com.gitmob.app.core.network

/**
 * 全局分页尺寸常量。所有 Repository 的 GraphQL `first:` 参数和 REST `per_page=`
 * 统一引用这里，禁止在查询字符串内散落魔法数字。
 *
 * ★ 修改某个值前先确认对应 GitHub API 的服务端上限：
 *   GraphQL 节点类通常上限 100，个别字段更低（如 releases 最高 100，topics 最高 10）。
 *   REST 通知上限 50。
 */
object PageSize {

    // ──────────────── 可分页列表（UI 无限滚动）────────────────

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

    /** 收件箱通知（REST per_page） */
    const val NOTIFICATIONS = 30

    /** 工作项：Issues / Pull Requests / Discussions */
    const val WORK_ITEMS = 20

    /** Gist 列表底层扫描页和 UI 目标页尺寸 */
    const val GISTS = 20

    /** 组织列表（user.organizations 分页） */
    const val ORGS = 20

    // ──────────────── 固定容量（非分页，不可随意调大）────────────────

    /**
     * 仓库话题标签上限（repositoryTopics）。
     * GitHub 每个仓库最多 20 个 topic，first:10 已足够展示；不分页。
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
}
