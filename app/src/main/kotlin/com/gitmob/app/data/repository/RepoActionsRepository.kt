package com.gitmob.app.data.repository

import com.gitmob.app.R
import com.gitmob.app.core.error.ApiResult
import com.gitmob.app.core.error.UserVisibleException
import com.gitmob.app.core.error.safeCall
import com.gitmob.app.core.download.ExternalDownloadLauncher
import com.gitmob.app.core.network.GHApiClient
import com.gitmob.app.core.permission.RepoPermission
import com.gitmob.app.data.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RepoActionsRepository @Inject constructor(
    private val api: GHApiClient,
    private val downloadLauncher: ExternalDownloadLauncher,
) {
    suspend fun getRepositoryPermission(owner: String, name: String): ApiResult<RepoPermission> = safeCall {
        val data = api.graphQL<ActionsPermissionData>(
            "query ActionsRepositoryPermission(\u0024owner: String!, \u0024name: String!) { repository(owner: \u0024owner, name: \u0024name) { viewerPermission } }",
            mapOf("owner" to JsonPrimitive(owner), "name" to JsonPrimitive(name)),
        )
        data.repository?.viewerPermission?.let { runCatching { RepoPermission.valueOf(it) }.getOrNull() } ?: RepoPermission.NONE
    }

    suspend fun getActions(owner: String, name: String, page: Int = 1): ApiResult<RepoActionsPage> = safeCall {
        val workflows = api.get<RestWorkflowsResponse>("/repos/$owner/$name/actions/workflows?per_page=100&page=1")
        val runs = api.get<RestRunsResponse>("/repos/$owner/$name/actions/runs?per_page=30&page=$page")
        RepoActionsPage(runs.totalCount, workflows.workflows.map(::toWorkflow), runs.runs.map(::toRun), page, runs.runs.size >= 30)
    }

    suspend fun getRun(owner: String, name: String, runId: Long): ApiResult<RepoWorkflowRunDetail> = safeCall {
        val run = api.get<RestWorkflowRun>("/repos/$owner/$name/actions/runs/$runId")
        val jobs = api.get<RestJobsResponse>("/repos/$owner/$name/actions/runs/$runId/jobs?per_page=100")
        val artifacts = api.get<RestArtifactsResponse>("/repos/$owner/$name/actions/runs/$runId/artifacts?per_page=100")
        RepoWorkflowRunDetail(toRun(run), jobs.jobs.map(::toJob), artifacts.artifacts.map(::toArtifact))
    }

    suspend fun getDispatchInputs(owner: String, name: String, workflow: RepoWorkflow, ref: String): ApiResult<List<WorkflowDispatchInput>> = safeCall {
        require(ref.isNotBlank()) { "Workflow ref must not be blank" }
        require(workflow.path.startsWith(".github/workflows/") && !workflow.path.contains("..")) { "Invalid workflow path" }
        val query = """
            query ActionWorkflowYaml(${'$'}owner: String!, ${'$'}name: String!, ${'$'}expression: String!) {
                repository(owner: ${'$'}owner, name: ${'$'}name) {
                    object(expression: ${'$'}expression) {
                        ... on Blob { text isBinary isTruncated byteSize }
                    }
                }
            }
        """.trimIndent()
        val data = api.graphQL<ActionWorkflowYamlData>(
            query,
            mapOf(
                "owner" to JsonPrimitive(owner),
                "name" to JsonPrimitive(name),
                "expression" to JsonPrimitive("$ref:${workflow.path}"),
            ),
        )
        val blob = data.repository?.objectNode ?: error("Workflow YAML not found")
        if (blob.isBinary || blob.isTruncated || blob.byteSize !in 1..MAX_WORKFLOW_YAML_BYTES) {
            error("Workflow YAML is unavailable")
        }
        WorkflowDispatchYamlParser.parse(blob.text ?: error("Workflow YAML is unavailable"))
    }

    suspend fun dispatch(owner: String, name: String, workflowId: Long, ref: String, inputs: Map<String, String>): ApiResult<Unit> = safeCall {
        api.post<Unit, DispatchRequest>("/repos/$owner/$name/actions/workflows/$workflowId/dispatches", DispatchRequest(ref, inputs))
    }
    suspend fun cancel(owner: String, name: String, runId: Long, force: Boolean = false): ApiResult<Unit> = safeCall {
        api.post<Unit, EmptyRequest>("/repos/$owner/$name/actions/runs/$runId/${if (force) "force-cancel" else "cancel"}", EmptyRequest)
    }
    suspend fun rerun(owner: String, name: String, runId: Long, failedJobsOnly: Boolean = false): ApiResult<Unit> = safeCall {
        api.post<Unit, EmptyRequest>("/repos/$owner/$name/actions/runs/$runId/${if (failedJobsOnly) "rerun-failed-jobs" else "rerun"}", EmptyRequest)
    }
    suspend fun deleteRun(owner: String, name: String, runId: Long): ApiResult<Unit> = safeCall {
        api.delete<Unit>("/repos/$owner/$name/actions/runs/$runId")
    }
    suspend fun enableWorkflow(owner: String, name: String, workflowId: Long, enabled: Boolean): ApiResult<Unit> = safeCall {
        if (enabled) {
            api.putNoBody<Unit>("/repos/$owner/$name/actions/workflows/$workflowId/enable")
        } else {
            api.putNoBody<Unit>("/repos/$owner/$name/actions/workflows/$workflowId/disable")
        }
    }
    suspend fun downloadArtifact(owner: String, name: String, artifact: RepoActionArtifact): ApiResult<Unit> = safeCall {
        if (artifact.expired) error("Artifact expired")
        val url = api.resolveRestDownloadUrl("/repos/$owner/$name/actions/artifacts/${artifact.id}/zip", "application/vnd.github+json")
            ?: throw UserVisibleException(R.string.download_address_unavailable)
        downloadLauncher.open(url)
    }

    private fun toWorkflow(value: RestWorkflow) = RepoWorkflow(value.id, value.nodeId, value.name, value.path, value.state, value.createdAt, value.updatedAt)
    private fun toRun(value: RestWorkflowRun) = RepoWorkflowRun(value.id, value.name, value.displayTitle ?: value.name.orEmpty(), value.event, value.status, value.conclusion, value.workflowId, value.runNumber, value.runAttempt, value.headBranch, value.headSha, value.actor?.let { SimpleUser(it.login, it.name, it.avatarUrl, null) }, value.createdAt, value.updatedAt, value.htmlUrl)
    private fun toJob(value: RestJob) = RepoActionJob(value.id, value.name, value.status, value.conclusion, value.startedAt, value.completedAt, value.runnerName, value.steps.map { RepoActionStep(it.name, it.status, it.conclusion, it.number) })
    private fun toArtifact(value: RestArtifact) = RepoActionArtifact(value.id, value.name, value.sizeInBytes, value.expired, value.expiresAt)
}

@Serializable private data object EmptyRequest
@Serializable private data class DispatchRequest(val ref: String, val inputs: Map<String, String>)
@Serializable private data class RestWorkflowsResponse(@SerialName("total_count") val totalCount: Int = 0, val workflows: List<RestWorkflow> = emptyList())
@Serializable private data class RestWorkflow(val id: Long, @SerialName("node_id") val nodeId: String = "", val name: String = "", val path: String = "", val state: String = "", @SerialName("created_at") val createdAt: String? = null, @SerialName("updated_at") val updatedAt: String? = null)
@Serializable private data class RestRunsResponse(@SerialName("total_count") val totalCount: Int = 0, @SerialName("workflow_runs") val runs: List<RestWorkflowRun> = emptyList())
@Serializable private data class RestWorkflowRun(val id: Long, val name: String? = null, @SerialName("display_title") val displayTitle: String? = null, val event: String = "", val status: String? = null, val conclusion: String? = null, @SerialName("workflow_id") val workflowId: Long = 0, @SerialName("run_number") val runNumber: Int = 0, @SerialName("run_attempt") val runAttempt: Int = 1, @SerialName("head_branch") val headBranch: String? = null, @SerialName("head_sha") val headSha: String = "", val actor: RestActor? = null, @SerialName("created_at") val createdAt: String = "", @SerialName("updated_at") val updatedAt: String = "", @SerialName("html_url") val htmlUrl: String = "")
@Serializable private data class RestActor(val login: String = "", @SerialName("avatar_url") val avatarUrl: String? = null, val name: String? = null)
@Serializable private data class RestJobsResponse(val jobs: List<RestJob> = emptyList())
@Serializable private data class RestJob(val id: Long, val name: String = "", val status: String = "", val conclusion: String? = null, @SerialName("started_at") val startedAt: String? = null, @SerialName("completed_at") val completedAt: String? = null, @SerialName("runner_name") val runnerName: String? = null, val steps: List<RestStep> = emptyList())
@Serializable private data class RestStep(val name: String = "", val status: String = "", val conclusion: String? = null, val number: Int = 0)
@Serializable private data class RestArtifactsResponse(val artifacts: List<RestArtifact> = emptyList())
@Serializable private data class RestArtifact(val id: Long, val name: String = "", @SerialName("size_in_bytes") val sizeInBytes: Long = 0, val expired: Boolean = false, @SerialName("expires_at") val expiresAt: String? = null)
@Serializable private data class ActionsPermissionData(val repository: ActionsPermissionRepository? = null)
@Serializable private data class ActionsPermissionRepository(val viewerPermission: String? = null)
@Serializable private data class ActionWorkflowYamlData(val repository: ActionWorkflowYamlRepository? = null)
@Serializable private data class ActionWorkflowYamlRepository(
    @SerialName("object") val objectNode: ActionWorkflowYamlBlob? = null,
)
@Serializable private data class ActionWorkflowYamlBlob(
    val text: String? = null,
    val isBinary: Boolean = false,
    val isTruncated: Boolean = false,
    val byteSize: Int = 0,
)

private const val MAX_WORKFLOW_YAML_BYTES = 1024 * 1024
