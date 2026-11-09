package com.gitmob.app.data.model

enum class WorkflowDispatchInputType { STRING, BOOLEAN, CHOICE, NUMBER, ENVIRONMENT }

data class WorkflowDispatchInput(
    val name: String,
    val description: String?,
    val required: Boolean,
    val type: WorkflowDispatchInputType,
    val defaultValue: String?,
    val options: List<String>,
)

data class RepoWorkflow(
    val id: Long,
    val nodeId: String,
    val name: String,
    val path: String,
    val state: String,
    val createdAt: String?,
    val updatedAt: String?,
)

data class RepoWorkflowRun(
    val id: Long,
    val name: String?,
    val displayTitle: String,
    val event: String,
    val status: String?,
    val conclusion: String?,
    val workflowId: Long,
    val runNumber: Int,
    val runAttempt: Int?,
    val headBranch: String?,
    val headSha: String,
    val actor: SimpleUser?,
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String,
)

data class RepoActionJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: String?,
    val completedAt: String?,
    val runnerName: String?,
    val steps: List<RepoActionStep>,
)

data class RepoActionStep(val name: String, val status: String, val conclusion: String?, val number: Int)
data class RepoActionArtifact(val id: Long, val name: String, val sizeInBytes: Long, val expired: Boolean, val expiresAt: String?)

data class RepoActionsPage(
    val totalCount: Int,
    val workflows: List<RepoWorkflow>,
    val runs: List<RepoWorkflowRun>,
    val page: Int,
    val hasNextPage: Boolean,
)

data class RepoWorkflowRunDetail(
    val run: RepoWorkflowRun,
    val jobs: List<RepoActionJob>,
    val artifacts: List<RepoActionArtifact>,
)

/** Route/request identity never contains credentials, URLs or log contents. */
data class RepoActionStepLogKey(
    val owner: String,
    val name: String,
    val runId: Long,
    val attempt: Int?,
    val jobId: Long,
    val stepNumber: Int,
)

enum class ActionLogAvailability { READY, WAITING, SKIPPED }

data class RepoActionStepLog(
    val jobName: String,
    val stepName: String,
    val availability: ActionLogAvailability,
    val text: String = "",
    val truncated: Boolean = false,
)
