package com.gitmob.android.ui.create

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gitmob.android.api.GHCreateRepoRequest
import com.gitmob.android.data.RepoRepository
import com.gitmob.android.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CreateRepoViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RepoRepository()
    private val _done = MutableStateFlow<String?>(null)
    val done = _done.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading = _loading.asStateFlow()

    fun create(name: String, desc: String, private: Boolean, autoInit: Boolean, orgLogin: String? = null) {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val req = GHCreateRepoRequest(
                    name = name.trim(),
                    description = desc.trim().ifEmpty { null },
                    private = private,
                    autoInit = autoInit,
                )
                val r = if (orgLogin != null) {
                    repo.createOrgRepo(orgLogin, req)
                } else {
                    repo.createRepo(req)
                }
                _done.value = r.fullName
            } catch (e: Exception) {
                _error.value = e.message ?: "创建失败"
            } finally {
                _loading.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRepoScreen(
    onBack: () -> Unit,
    onCreated: (String, String) -> Unit,
    orgLogin: String? = null,           // null = 当前用户，否则 = 组织名
    vm: CreateRepoViewModel = viewModel(),
) {
    val c = LocalGmColors.current
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var autoInit by remember { mutableStateOf(true) }

    val done by vm.done.collectAsState()
    val error by vm.error.collectAsState()
    val loading by vm.loading.collectAsState()

    LaunchedEffect(done) {
        done?.let { fullName ->
            val parts = fullName.split("/")
            if (parts.size == 2) onCreated(parts[0], parts[1])
        }
    }

    Scaffold(
        containerColor = c.bgDeep,
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text("新建仓库", fontWeight = FontWeight.SemiBold, color = c.textPrimary)
                        if (orgLogin != null) {
                            Text(
                                "创建到：$orgLogin",
                                fontSize = 11.sp,
                                color = com.gitmob.android.ui.theme.Coral,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = c.textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bgDeep),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 仓库名称
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("仓库名称 *", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.textSecondary)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text("my-project", color = c.textTertiary, fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Coral,
                        unfocusedBorderColor = c.border,
                        focusedContainerColor = c.bgCard,
                        unfocusedContainerColor = c.bgCard,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary,
                    ),
                )
            }

            // 描述
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("描述（可选）", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.textSecondary)
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    placeholder = { Text("项目描述...", color = c.textTertiary, fontSize = 14.sp) },
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Coral,
                        unfocusedBorderColor = c.border,
                        focusedContainerColor = c.bgCard,
                        unfocusedContainerColor = c.bgCard,
                        focusedTextColor = c.textPrimary,
                        unfocusedTextColor = c.textPrimary,
                    ),
                )
            }

            // 私有开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.bgCard, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("私有仓库", fontSize = 14.sp, color = c.textPrimary)
                    Text("仅自己可见", fontSize = 11.sp, color = c.textTertiary,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Switch(
                    checked = isPrivate, onCheckedChange = { isPrivate = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Coral,
                        uncheckedThumbColor = c.textTertiary,
                        uncheckedTrackColor = c.bgItem,
                    ),
                )
            }

            // 自动初始化 README
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.bgCard, RoundedCornerShape(12.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("自动初始化 README", fontSize = 14.sp, color = c.textPrimary)
                    Text("创建后即可克隆", fontSize = 11.sp, color = c.textTertiary,
                        modifier = Modifier.padding(top = 2.dp))
                }
                Switch(
                    checked = autoInit, onCheckedChange = { autoInit = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Coral,
                        uncheckedThumbColor = c.textTertiary,
                        uncheckedTrackColor = c.bgItem,
                    ),
                )
            }

            if (error != null) {
                Text(
                    error!!,
                    color = RedColor,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .background(RedDim, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .fillMaxWidth(),
                )
            }

            Button(
                onClick = { if (name.isNotBlank()) vm.create(name, desc, isPrivate, autoInit, orgLogin) },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Coral),
                enabled = name.isNotBlank() && !loading,
            ) {
                if (loading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("创建仓库", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}
