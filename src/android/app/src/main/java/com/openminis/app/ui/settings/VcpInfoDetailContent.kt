package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.vcpinfo.VcpInfoCategory
import com.openminis.app.vcpinfo.VcpInfoMessage
import org.json.JSONArray
import org.json.JSONObject

/** 面向用户的结构化详情；原始 JSON 只保留在末尾调试折叠区。 */
@Composable
internal fun VcpInfoDetailContent(message: VcpInfoMessage) {
    val json = remember(message.id) { runCatching { JSONObject(message.rawJson) }.getOrNull() }
    if (json == null) {
        DetailText(message.rawJson)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        when (message.category) {
            VcpInfoCategory.RAG -> RagDetail(json)
            VcpInfoCategory.CHAIN -> ChainDetail(json)
            VcpInfoCategory.CHAT -> ChatDetail(json)
            VcpInfoCategory.MEMO -> MemoDetail(json)
            VcpInfoCategory.DREAM -> DreamDetail(json)
        }
        RawJsonFold(message.rawJson)
    }
}

@Composable
private fun RagDetail(json: JSONObject) {
    json.text("query")?.let { DetailBlock("RAG 检索提问", it, Color(0xFF1976D2), DetailTone.BLUE) }
    val tags = json.array("coreTags").strings()
    if (tags.isNotEmpty()) TagRow("核心标签", tags.map { "✦ $it" }, DetailTone.GOLD)
    val tagStats = json.optJSONObject("tagStats")
    if (tagStats != null) {
        TagRow("标签增强", listOfNotNull(
            "🏷 匹配 ${tagStats.optJSONArray("uniqueMatchedTags")?.length() ?: 0} 个",
            tagStats.firstValue("avgBoostFactor")?.let { "Boost $it" },
            tagStats.firstValue("resultsWithTags")?.let { "$it 项带标签" },
        ), DetailTone.GOLD)
    }
    json.optJSONArray("timeRanges")?.optJSONObject(0)?.let {
        TagRow("时间窗口", listOf("◷ ${it.optString("start").take(10)} → ${it.optString("end").take(10)}"), DetailTone.BLUE)
    }
    val results = json.array("results")
    DetailLabel("召回结果列表 · ${results.length()}", Color(0xFF1976D2))
    for (i in 0 until results.length()) {
        val item = results.optJSONObject(i)
        val content = item?.firstText("text", "content", "pageContent", "document")
            ?: results.optString(i).takeIf { it.isNotBlank() }
            ?: continue
        RagResult(i + 1, content, item)
    }
    if (results.length() == 0) DetailEmpty("没有召回结果")
}

@Composable
private fun ChainDetail(json: JSONObject) {
    val purple = Color(0xFF8E44AD)
    val stages = json.array("stages")
    val flow = (0 until stages.length()).mapNotNull { stages.optJSONObject(it)?.text("clusterName") }
    if (flow.isNotEmpty()) FlowPath(flow, purple)
    json.text("query")?.let { DetailBlock("思考查询", it, purple, DetailTone.PURPLE) }
    val groups = json.array("activatedGroups").strings().map { "组: $it" }
    if (groups.isNotEmpty()) TagRow("激活分组", groups, DetailTone.PURPLE)
    val cache = if (json.optBoolean("fromCache")) " · 来自缓存" else ""
    DetailLabel("阶段执行详情 · ${json.optInt("totalStages", stages.length())}$cache", purple)
    for (i in 0 until stages.length()) {
        val stage = stages.optJSONObject(i) ?: continue
        val stageNo = stage.optInt("stage", i + 1)
        val cluster = stage.text("clusterName") ?: "未命名聚类"
        val results = stage.array("results")
        var stageOpen by remember(stageNo, cluster) { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth()
                .background(toneBackground(DetailTone.PURPLE), RoundedCornerShape(5.dp))
                .border(.5.dp, purple.copy(alpha = .22f), RoundedCornerShape(5.dp)),
        ) {
            Column(Modifier.width(3.dp).background(purple.copy(alpha = .72f), RoundedCornerShape(2.dp))) {
                Text(" ", fontSize = 1.sp, lineHeight = if (stageOpen) 72.sp else 34.sp)
            }
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().clickable { stageOpen = !stageOpen }.padding(horizontal = 8.dp, vertical = 7.dp),
                ) {
                    Text("阶段 $stageNo: $cluster", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = purple, modifier = Modifier.weight(1f))
                    Text("K ${stage.optInt("k")} · 召回 ${stage.optInt("resultCount", results.length())}",
                        fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (stageOpen) "  ▲" else "  ▼", fontSize = 8.sp, color = purple)
                }
                if (stageOpen) {
                    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        for (j in 0 until results.length()) {
                            val result = results.optJSONObject(j)
                            val text = result?.firstText("text", "content", "result") ?: results.optString(j)
                            if (text.isNotBlank()) DetailResult(j + 1, text,
                                result?.firstValue("score", "similarity"), tone = DetailTone.PURPLE)
                        }
                        if (results.length() == 0) DetailEmpty("本阶段没有召回结果")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatDetail(json: JSONObject) {
    json.text("sessionId")?.let { TagRow("会话", listOf("ID $it"), DetailTone.NEUTRAL) }
    json.text("query")?.let { DetailBlock("USER QUERY", it, Color(0xFF616161), DetailTone.NEUTRAL) }
    json.text("response")?.let { DetailBlock("AI INNER VOICE", it, Color(0xFFF57C00), DetailTone.ORANGE) }
}

@Composable
private fun MemoDetail(json: JSONObject) {
    val green = Color(0xFF2E7D32)
    val stats = buildList {
        json.text("mode")?.let { add("模式 $it") }
        if (json.has("diaryCount")) add("命中 ${json.optInt("diaryCount")} 日记")
        if (json.has("fileCount")) add("扫描 ${json.optInt("fileCount")} 文件")
        if (json.has("batchCount")) add("${json.optInt("batchCount")} 批")
        val dbs = json.array("dbNames").strings()
        if (dbs.isNotEmpty()) add("联合库 ${dbs.joinToString(", ")}")
        if (json.optInt("tagMemoChunkCount") > 0) add("TagMemo ${json.optInt("tagMemoChunkCount")} Chunks")
        json.text("sourceMode")?.let { add("来源 $it") }
        if (json.optBoolean("fromCache")) add("来自缓存")
    }
    if (stats.isNotEmpty()) TagRow("检索信息", stats, DetailTone.GREEN)
    json.text("query")?.let { DetailBlock("联合检索提问", it, green, DetailTone.GREEN) }
    json.text("extractedMemories")?.let { DetailBlock("提炼出的联合记忆报告", it, green, DetailTone.GREEN_STRONG) }
    json.text("message")?.let { DetailBlock("日记动作追踪", it, green, DetailTone.GREEN) }
    json.text("rawResponse")?.takeIf { json.text("extractedMemories") == null }?.let {
        DetailBlock("原始模型响应", it, Color(0xFF607D8B), DetailTone.NEUTRAL)
    }
    json.text("error")?.let { DetailBlock("错误", it, MaterialTheme.colorScheme.error, DetailTone.RED) }
}

@Composable
private fun DreamDetail(json: JSONObject) {
    val pink = Color(0xFFD81B60)
    val type = json.optString("type")
    json.text("dreamId")?.let { TagRow("梦境", listOf("梦境ID: $it"), DetailTone.PINK) }
    when (type) {
        "AGENT_DREAM_ASSOCIATIONS" -> {
            TagRow("运行信息", listOf(
                "种子日记 ${json.optInt("seedCount")} 篇",
                "联想唤醒 ${json.optInt("associationCount")} 篇",
                "近期 ${json.optInt("recentSeedsCount")}",
                "中期 ${json.optInt("midSeedsCount")}",
                "深层 ${json.optInt("deepRecallsCount")}",
            ), DetailTone.GOLD)
            DreamSeeds(json.array("seeds"))
            DreamAssociations(json.array("associations"))
        }
        "AGENT_DREAM_NARRATIVE" -> {
            json.text("narrative")?.let { DetailBlock("梦境长叙事正文", it, pink, DetailTone.PINK) }
                ?: json.text("message")?.let { DetailBlock("梦境长叙事正文", it, pink, DetailTone.PINK) }
        }
        "AGENT_DREAM_OPERATIONS" -> {
            TagRow("整理信息", listOfNotNull(
                json.text("logFile")?.let { "日志 $it" },
                "操作 ${json.array("operations").length()} 项",
            ), DetailTone.PINK)
            DreamOperations(json.array("operations"))
        }
        "AGENT_DREAM_START" -> json.text("message")?.let { DetailBlock("入梦开始", it, Color(0xFF7B1FA2), DetailTone.PURPLE) }
        "AGENT_DREAM_END" -> {
            val failed = json.text("status") == "error"
            DetailBlock(if (failed) "梦境运行失败" else "梦境运行结束",
                json.text("error") ?: json.text("message") ?: "正常离梦，数据收拢。",
                if (failed) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                if (failed) DetailTone.RED else DetailTone.GREEN)
        }
        "AGENT_DREAM_SCHEDULE" -> {
            TagRow("计划调度成员", json.array("agents").strings(), DetailTone.BLUE)
            json.text("message")?.let { DetailBlock("入梦调度广播 · ${json.optInt("currentHour")}点", it, Color(0xFF1976D2), DetailTone.BLUE) }
        }
        else -> {
            json.text("message")?.let { DetailBlock("梦境消息", it, pink, DetailTone.PINK) }
            json.text("error")?.let { DetailBlock("错误", it, MaterialTheme.colorScheme.error, DetailTone.RED) }
        }
    }
}

@Composable
private fun DreamSeeds(array: JSONArray) {
    if (array.length() == 0) return
    DetailLabel("入梦采样种子列表 · ${array.length()}", Color(0xFFB8860B))
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        DetailResult(i + 1, obj.text("snippet") ?: "（无摘要）", obj.text("file"), DetailTone.GOLD)
    }
}

@Composable
private fun DreamAssociations(array: JSONArray) {
    if (array.length() == 0) return
    DetailLabel("梦境关联唤醒 · ${array.length()}", Color(0xFFB8860B))
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        DetailResult(i + 1, obj.text("file") ?: "Unknown", obj.firstValue("score"), DetailTone.GOLD)
    }
}

@Composable
private fun DreamOperations(array: JSONArray) {
    if (array.length() == 0) { DetailEmpty("没有梦操作"); return }
    DetailLabel("梦操作列表 · ${array.length()}", Color(0xFFD81B60))
    for (i in 0 until array.length()) {
        val obj = array.optJSONObject(i) ?: continue
        val type = obj.text("type") ?: "unknown"
        val tone = when (type) { "merge" -> DetailTone.ORANGE; "delete" -> DetailTone.RED; "insight" -> DetailTone.BLUE; else -> DetailTone.NEUTRAL }
        DetailResult(i + 1, obj.text("operationId") ?: "未提供操作 ID", obj.text("status") ?: "unknown", tone, type.uppercase())
    }
}

private enum class DetailTone(val accent: Color, val light: Color) {
    BLUE(Color(0xFF1976D2), Color(0xFFEAF3FF)), PURPLE(Color(0xFF8E44AD), Color(0xFFF5ECFA)),
    GREEN(Color(0xFF2E7D32), Color(0xFFEDF8EF)), GREEN_STRONG(Color(0xFF1B5E20), Color(0xFFE5F4E8)),
    ORANGE(Color(0xFFF57C00), Color(0xFFFFF3E5)), RED(Color(0xFFC62828), Color(0xFFFFEBEE)),
    PINK(Color(0xFFD81B60), Color(0xFFFCEAF1)), GOLD(Color(0xFF9A7010), Color(0xFFFFF7DA)),
    NEUTRAL(Color(0xFF607D8B), Color(0xFFF1F4F5)),
}

@Composable
private fun toneBackground(tone: DetailTone): Color = if (androidx.compose.foundation.isSystemInDarkTheme()) {
    tone.accent.copy(alpha = .12f)
} else tone.light

@Composable
private fun TagRow(label: String, values: List<String>, tone: DetailTone = DetailTone.BLUE) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailLabel(label, tone.accent)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            values.forEach { value ->
                Text(value, fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = tone.accent,
                    modifier = Modifier.background(toneBackground(tone), RoundedCornerShape(4.dp))
                        .border(.5.dp, tone.accent.copy(alpha = .28f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable
private fun RagResult(index: Int, text: String, item: JSONObject?) {
    val score = item?.firstValue("score", "similarity", "distance")
    val source = item?.firstValue("source", "sourceFile", "fullPath")
    val boosted = item?.has("boostFactor") == true
    val tone = if (boosted) DetailTone.GOLD else DetailTone.BLUE
    var open by remember(index, text) { mutableStateOf(false) }
    DetailResult(index, text, score?.let { "Score $it" }, tone, source, open, onToggle = { open = !open })
    if (open) {
        val matched = item?.array("matchedTags")?.strings().orEmpty()
        if (matched.isNotEmpty()) TagRow("命中标签", matched.map { "🏷 $it" }, DetailTone.GOLD)
        val extra = buildList {
            item?.firstValue("boostFactor")?.let { add("Boost $it") }
            item?.firstValue("associateCoCount")?.let { add("共现 $it") }
            item?.text("date")?.let { add(it) }
        }
        if (extra.isNotEmpty()) TagRow("结果增强", extra, DetailTone.GOLD)
    }
}

@Composable
private fun DetailResult(
    index: Int,
    text: String,
    score: String?,
    tone: DetailTone = DetailTone.BLUE,
    badge: String? = null,
    expanded: Boolean? = null,
    onToggle: (() -> Unit)? = null,
) {
    var localExpanded by remember(index, text) { mutableStateOf(false) }
    val isExpanded = expanded ?: localExpanded
    val toggle = onToggle ?: { localExpanded = !localExpanded }
    val cardModifier = Modifier.fillMaxWidth().background(toneBackground(tone), RoundedCornerShape(5.dp))
        .border(.5.dp, tone.accent.copy(alpha = .2f), RoundedCornerShape(5.dp))
        .clickable(onClick = toggle)
        .padding(7.dp)
    Column(cardModifier) {
        Row(Modifier.fillMaxWidth()) {
            Text("#$index", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = tone.accent)
            badge?.let { Text("  $it", fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1) }
                ?: androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            score?.let { Text(it, fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                color = tone.accent, modifier = Modifier.background(tone.accent.copy(alpha = .1f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) }
            Text(if (isExpanded) "  ▲" else "  ▼", fontSize = 8.sp, color = tone.accent)
        }
        Text(text, fontSize = 9.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (isExpanded) Int.MAX_VALUE else 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun DetailBlock(
    label: String,
    text: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    tone: DetailTone = DetailTone.BLUE,
) {
    var expanded by remember(label, text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(toneBackground(tone), RoundedCornerShape(5.dp))
        .border(.5.dp, accent.copy(alpha = .22f), RoundedCornerShape(5.dp))
        .clickable { expanded = !expanded }.padding(8.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = accent, modifier = Modifier.weight(1f))
            Text(if (expanded) "收起 ▲" else "展开 ▼", fontSize = 8.sp, color = accent.copy(alpha = .75f))
        }
        Text(text, fontSize = 9.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun FlowPath(stages: List<String>, accent: Color) {
    Column(Modifier.fillMaxWidth().background(toneBackground(DetailTone.PURPLE), RoundedCornerShape(5.dp))
        .border(.5.dp, accent.copy(alpha = .22f), RoundedCornerShape(5.dp)).padding(8.dp)) {
        Text("✦ 思考流脉络", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = accent)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 5.dp)) {
            stages.forEachIndexed { index, stage ->
                Text(stage, fontSize = 8.sp, color = accent,
                    modifier = Modifier.background(accent.copy(alpha = .1f), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp))
                if (index < stages.lastIndex) Text("→", fontSize = 8.sp, color = accent.copy(alpha = .5f))
            }
        }
    }
}

@Composable private fun DetailEmpty(text: String) = Text(text, fontSize = 9.sp, color = MaterialTheme.colorScheme.outline,
    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .5f), RoundedCornerShape(5.dp)).padding(7.dp))
@Composable private fun DetailText(text: String) = Text(text, fontSize = 9.sp, lineHeight = 14.sp)
@Composable private fun DetailLabel(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) = Text(text.uppercase(),
    fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp, color = color)

@Composable
private fun RawJsonFold(raw: String) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .45f), RoundedCornerShape(6.dp))) {
        Text(if (open) "收起调试数据" else "查看原始 JSON（调试）", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 8.dp, vertical = 6.dp))
        if (open) Text(runCatching { JSONObject(raw).toString(2) }.getOrDefault(raw), fontFamily = FontFamily.Monospace,
            fontSize = 8.sp, lineHeight = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
    }
}

private fun JSONObject.text(key: String) = optString(key).trim().takeIf { it.isNotEmpty() }
private fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()
private fun JSONArray.strings() = (0 until length()).mapNotNull { optString(it).trim().takeIf(String::isNotEmpty) }
private fun JSONObject.firstText(vararg keys: String) = keys.firstNotNullOfOrNull { text(it) }
private fun JSONObject.firstValue(vararg keys: String) = keys.firstNotNullOfOrNull { key ->
    if (!has(key) || isNull(key)) null else opt(key)?.toString()?.takeIf { it.isNotBlank() }
}
