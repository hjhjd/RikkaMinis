package com.openminis.app.ui.agents

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.openminis.app.agent.*
import com.openminis.app.data.db.*
import com.openminis.app.data.repository.AgentRepository
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AgentEditScreen(agentId: String?, agentRepository: AgentRepository, providerRepository: com.openminis.app.data.repository.ProviderRepository, onBack: () -> Unit, onSaved: (String) -> Unit, onSkills: (String) -> Unit, onMemory: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avatarStore = remember { AgentAvatarStore(context.applicationContext) }
    var loadedAgent by remember { mutableStateOf<AgentEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("auto") }
    var defaultModelBinding by remember { mutableStateOf<String?>(null) }
    val modelGroups by providerRepository.config.collectAsState()
    var avatarPath by remember { mutableStateOf<String?>(null) }
    var pendingAvatar by remember { mutableStateOf<Uri?>(null) }
    var avatarZoom by remember { mutableStateOf(1f) }
    var showAvatarCrop by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(agentId != null) }
    var saving by remember { mutableStateOf(false) }
    var archiveCount by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(agentId) {
        agentId?.let {
            val agent = withContext(Dispatchers.IO) { agentRepository.get(it) }
            if (agent == null) error = "Agent 不存在" else {
                loadedAgent = agent; name = agent.name; instructions = agent.instructions
                language = agent.preferredLanguage ?: "auto"; avatarPath = agent.avatarPath
                defaultModelBinding = agent.defaultModelBinding
            }
        }
        loading = false
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { pendingAvatar = uri; avatarZoom = 1f; showAvatarCrop = true }
    }

    fun save() {
        if (name.isBlank() || saving) return
        saving = true; error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val base = loadedAgent ?: agentRepository.create(name, instructions, language, defaultModelBinding)
                    val updated = base.copy(name = name.trim(), instructions = instructions.trim(), preferredLanguage = language, defaultModelBinding = defaultModelBinding, avatarPath = pendingAvatar?.let { avatarStore.import(base.id, it, avatarZoom) } ?: avatarPath)
                    agentRepository.save(updated)
                    if (updated.id == AgentIds.DEFAULT) {
                        val old = SoulStore.load(context)
                        SoulStore.save(context, SoulFile(SoulMetadata(updated.name, old?.metadata?.emoji.orEmpty(), old?.metadata?.style.orEmpty(), language), updated.instructions))
                    }
                    updated.id
                }
            }.onSuccess(onSaved).onFailure { error = it.message ?: "保存失败" }
            saving = false
        }
    }

    SettingsScaffold(title = if (agentId == null) "创建 Agent" else "Agent 设置", onBack = onBack) {
        if (loading) { Text("正在加载…", modifier = Modifier.padding(24.dp)); return@SettingsScaffold }
        SettingsSection(header = "头像") {
            Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val model: Any? = pendingAvatar ?: avatarStore.resolve(avatarPath)
                Box(Modifier.size(104.dp).clip(CircleShape).clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, contentAlignment = Alignment.Center) {
                    if (model != null) AsyncImage(model, "Agent 头像", Modifier.size(104.dp)) else Icon(Icons.Outlined.Person, null, Modifier.size(64.dp))
                }
                Text("点击选择头像", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
        SettingsSection(header = "基本信息") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Agent 名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("回复语言", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "自动", "zh" to "中文", "en" to "English").forEach { (value, label) ->
                        if (language == value) Button({ language = value }) { Text(label) } else OutlinedButton({ language = value }) { Text(label) }
                    }
                }
            }
        }
        SettingsSection(header = "默认模型组", footer = "仅影响该 Agent 新建的话题；已有话题保持自己的模型绑定。") {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (defaultModelBinding == null) Button({ defaultModelBinding = null }, Modifier.fillMaxWidth()) { Text("使用全局默认") }
                else OutlinedButton({ defaultModelBinding = null }, Modifier.fillMaxWidth()) { Text("使用全局默认") }
                modelGroups.modelGroups.forEach { group ->
                    val binding = "{\"type\":\"group\",\"groupId\":\"${group.id}\"}"
                    if (defaultModelBinding == binding) Button({ defaultModelBinding = binding }, Modifier.fillMaxWidth()) { Text(group.name) }
                    else OutlinedButton({ defaultModelBinding = binding }, Modifier.fillMaxWidth()) { Text(group.name) }
                }
            }
        }
        SettingsSection(header = "人格与提示词", footer = "只配置该 Agent 的身份、语气和行为偏好。应用安全规则与工具协议不会被覆盖。") {
            OutlinedTextField(instructions, { instructions = it }, placeholder = { Text("描述人格、职责、表达方式和工作偏好…") }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 240.dp))
        }
        loadedAgent?.let { agent ->
            SettingsSection(header = "能力与数据") {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ onSkills(agent.id) }, Modifier.weight(1f)) { Text("技能") }
                    OutlinedButton({ onMemory(agent.id) }, Modifier.weight(1f)) { Text("记忆") }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp, 8.dp)) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            loadedAgent?.takeIf { it.id != AgentIds.DEFAULT }?.let { agent ->
                OutlinedButton(
                    onClick = { scope.launch { archiveCount = withContext(Dispatchers.IO) { agentRepository.sessionCount(agent.id) } } },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("删除 Agent") }
            }
            Button(
                ::save,
                Modifier.weight(if (loadedAgent?.id == AgentIds.DEFAULT) 1f else 1.45f).height(52.dp),
                enabled = name.isNotBlank() && !saving,
                shape = MaterialTheme.shapes.medium,
            ) { Text(if (saving) "正在保存…" else "保存") }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }

    if (showAvatarCrop && pendingAvatar != null) {
        AlertDialog(
            onDismissRequest = { showAvatarCrop = false },
            title = { Text("调整头像") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(220.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            pendingAvatar,
                            "头像预览",
                            Modifier.fillMaxSize().graphicsLayer { scaleX = avatarZoom; scaleY = avatarZoom },
                        )
                    }
                    Text("拖动滑块调整图片大小", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    Slider(value = avatarZoom, onValueChange = { avatarZoom = it }, valueRange = 1f..3f)
                }
            },
            dismissButton = { TextButton({ showAvatarCrop = false; pendingAvatar = null }) { Text("取消") } },
            confirmButton = { TextButton({ showAvatarCrop = false }) { Text("完成") } },
        )
    }

    archiveCount?.let { count ->
        AlertDialog(
            onDismissRequest = { archiveCount = null },
            title = { Text("归档这个 Agent？") },
            text = { Text("该 Agent 的 $count 个话题将迁移到默认 Agent。头像和记忆文件不会立即删除。") },
            dismissButton = { TextButton({ archiveCount = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    val id = loadedAgent?.id ?: return@TextButton
                    archiveCount = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { agentRepository.archiveAndReassignToDefault(id) } }
                            .onSuccess { onBack() }
                            .onFailure { error = it.message ?: "归档失败" }
                    }
                }) { Text("归档", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}
