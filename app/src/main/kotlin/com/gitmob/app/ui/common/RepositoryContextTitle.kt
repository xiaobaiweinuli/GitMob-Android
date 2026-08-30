package com.gitmob.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.gitmob.app.R

/**
 * 仓库子页面共用的单行路径标题。只负责渲染与点击语义，不持有导航或业务状态。
 */
@Composable
fun RepositoryContextTitle(
    owner: String,
    repository: String,
    pageTitle: String,
    onOwnerClick: (String) -> Unit,
    onRepositoryClick: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ownerClickLabel = stringResource(R.string.repository_context_open_owner, owner)
    val repositoryClickLabel = stringResource(
        R.string.repository_context_open_repository,
        owner,
        repository,
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = owner,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.clickable(
                onClickLabel = ownerClickLabel,
                role = Role.Button,
                onClick = { onOwnerClick(owner) },
            ),
        )
        Text(text = " / ", maxLines = 1, softWrap = false)
        Text(
            text = repository,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.clickable(
                onClickLabel = repositoryClickLabel,
                role = Role.Button,
                onClick = { onRepositoryClick(owner, repository) },
            ),
        )
        Text(
            text = " · $pageTitle",
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
