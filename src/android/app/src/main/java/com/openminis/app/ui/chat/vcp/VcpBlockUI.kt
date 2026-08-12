package com.openminis.app.ui.chat.vcp

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.window.Dialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.ContentScale
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.compose.AsyncImagePainter
import com.openminis.app.ui.chat.MarkdownBlock
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import coil.request.ImageRequest

internal val LocalVcpHtmlButtonHandler = compositionLocalOf<((String) -> Unit)?> { null }

@Composable
internal fun VcpBlockView(messageId: String, block: VcpContentBlock, isStreaming: Boolean) {
    when (block) {
        is VcpContentBlock.Markdown -> MarkdownBlock(block.content, isStreaming, shardId = null)
        is VcpContentBlock.Thought -> VcpThoughtBlock(messageId, block)
        is VcpContentBlock.ToolUse -> VcpToolUseBlock(block)
        is VcpContentBlock.ToolResult -> VcpToolResultBlock(block)
        is VcpContentBlock.HtmlPreview -> VcpHtmlPreviewBlock(messageId, block)
        is VcpContentBlock.Image -> VcpImageBlock(block)
    }
}

@Composable
private fun VcpThoughtBlock(messageId: String, block: VcpContentBlock.Thought) {
    val active = block.completion == VcpBlockCompletion.STREAMING
    // Do not key state by content hash: the hash changes on every streaming
    // chunk and would erase a user's manual collapse/expand choice.
    var expanded by remember(messageId, block.source, block.theme) { mutableStateOf(active) }
    var userChanged by remember(messageId, block.source, block.theme) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!userChanged) expanded = active }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), shape)) {
        Row(Modifier.fillMaxWidth().clickable { userChanged = true; expanded = !expanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(block.theme.ifBlank { "元思考链" }, Modifier.padding(start = 8.dp).weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (active) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.padding(start = 6.dp).size(18.dp))
        }
        AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                MarkdownBlock(block.content, active, shardId = null)
            }
        }
    }
}

@Composable
private fun VcpToolUseBlock(block: VcpContentBlock.ToolUse) {
    VcpExpandableCard(
        title = "VCP-ToolUse", subtitle = block.toolName,
        status = if (block.completion == VcpBlockCompletion.STREAMING) "接收中" else null,
        icon = Icons.Default.Settings,
    ) {
        SelectionContainer {
            Text(block.content, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun VcpToolResultBlock(block: VcpContentBlock.ToolResult) {
    val context = LocalContext.current
    VcpExpandableCard("VCP-ToolResult", block.toolName, block.status.ifBlank { null }, Icons.Default.Assessment) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            block.details.forEach { detail ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(detail.key, Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        IconButton(onClick = { copy(context, detail.key, detail.value) }, Modifier.size(30.dp)) { Icon(Icons.Default.ContentCopy, "复制", Modifier.size(14.dp)) }
                    }
                    MarkdownBlock(detail.value, false, shardId = null)
                }
            }
            if (block.footer.isNotBlank()) {
                HorizontalDivider()
                MarkdownBlock(block.footer, false, shardId = null)
            }
            TextButton(onClick = {
                val all = block.details.joinToString("\n") { "${it.key}: ${it.value}" } + if (block.footer.isBlank()) "" else "\n\n${block.footer}"
                copy(context, "VCP tool result", all)
            }) { Icon(Icons.Default.ContentCopy, null, Modifier.size(15.dp)); Text("复制全部", Modifier.padding(start = 6.dp)) }
        }
    }
}

@Composable
private fun VcpExpandableCard(title: String, subtitle: String, status: String?, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable () -> Unit) {
    var expanded by remember(title, subtitle) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f), shape)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 8.dp).weight(1f)) {
                Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Text(subtitle, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            }
            status?.let { AssistChip(onClick = {}, label = { Text(it, fontSize = 10.sp) }, modifier = Modifier.height(28.dp)) }
            Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        AnimatedVisibility(expanded) { Box(Modifier.fillMaxWidth().padding(12.dp)) { content() } }
    }
}

@Composable
private fun VcpImageBlock(block: VcpContentBlock.Image) {
    val context = LocalContext.current
    var fullscreen by remember(block.src) { mutableStateOf(false) }
    val request = remember(block.src) {
        ImageRequest.Builder(context)
            .data(block.src)
            .limitDisplaySize()
            .build()
    }
    val shape = RoundedCornerShape(10.dp)
    SubcomposeAsyncImage(
        model = request,
        contentDescription = block.alt.ifBlank { block.title.ifBlank { "VCP 图片" } },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .widthIn(max = (block.widthPx ?: 360).coerceIn(80, 360).dp)
            .heightIn(min = 80.dp, max = 360.dp)
            .padding(vertical = 4.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable { fullscreen = true },
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Error -> Box(
                Modifier.fillMaxWidth().height(100.dp).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text("图片加载失败", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
            else -> SubcomposeAsyncImageContent()
        }
    }
    if (fullscreen) {
        com.openminis.app.ui.components.ImageGalleryViewer(
            items = listOf(com.openminis.app.ui.components.ImageGalleryItem(request, block.alt.ifBlank { block.title })),
            onDismiss = { fullscreen = false },
        )
    }
}

@Composable
private fun VcpHtmlPreviewBlock(messageId: String, block: VcpContentBlock.HtmlPreview) {
    val canPreview = block.completion == VcpBlockCompletion.STABLE
    // Stable HTML is message content, not an attachment: render it immediately.
    // An incomplete streaming fragment remains source code until it closes.
    val renderKey = remember(messageId, block.hash) { "vcp-html:$messageId:${block.hash}" }
    val renderState = remember(renderKey) { VcpHtmlRenderStore.state(renderKey) }
    var preview by remember(renderKey) { mutableStateOf(renderState.showPreview && canPreview) }
    var fullscreen by remember(renderKey) { mutableStateOf(false) }
    var controlsVisible by remember(renderKey) { mutableStateOf(false) }
    var controlsGeneration by remember(renderKey) { mutableIntStateOf(0) }
    val context = LocalContext.current

    fun revealControls() {
        controlsVisible = true
        controlsGeneration++
    }
    LaunchedEffect(controlsGeneration) {
        if (controlsGeneration == 0) return@LaunchedEffect
        kotlinx.coroutines.delay(3_000)
        controlsVisible = false
    }

    val body: @Composable (Modifier, Boolean) -> Unit = { modifier, isFullscreen ->
        if (preview && canPreview) {
            // WebView CSS px map to Android dp under the injected device-width viewport.
            // Include an authored top margin in the viewport extent; using only
            // rect.height clips the same number of pixels from the bubble bottom.
            // Do not cap stable HTML: a hard 560dp ceiling clips legitimate
            // VCP bubbles. Extra 24dp preserves shadows outside root bounds.
            val rootHeightDp = (renderState.rootTopCss + renderState.rootHeightCss + 24).coerceAtLeast(80)
            SandboxedHtml(
                renderKey = if (isFullscreen) "$renderKey:fullscreen" else renderKey,
                renderState = renderState,
                content = block.content,
                modifier = if (isFullscreen) modifier else modifier.height(rootHeightDp.dp),
                onInteraction = { revealControls() },
            )
        } else {
            Box(
                modifier
                    .horizontalScroll(rememberScrollState())
                    .clickable { revealControls() }
                    .padding(12.dp),
            ) {
                Text(block.content, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        body(Modifier.fillMaxWidth().heightIn(min = 80.dp), false)
        val controlsWidth = 108.dp
        val rootRight = if (renderState.hasMeasured) renderState.rootLeftCss.dp + renderState.rootWidthCss.dp else maxWidth
        val controlsX = (rootRight - controlsWidth - 4.dp).coerceIn(0.dp, maxWidth - controlsWidth)
        val controlsY = (if (renderState.hasMeasured) renderState.rootTopCss.dp else 0.dp) + 4.dp
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.offset(x = controlsX, y = controlsY),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HtmlOverlayIcon(Icons.Default.ContentCopy, "复制") { copy(context, "HTML", block.content); revealControls() }
                HtmlOverlayIcon(if (preview) Icons.Default.Code else Icons.Default.Visibility, if (preview) "查看代码" else "预览", canPreview) {
                    preview = !preview; renderState.showPreview = preview; revealControls()
                }
                HtmlOverlayIcon(Icons.Default.Fullscreen, "全屏") { fullscreen = true; revealControls() }
            }
        }
    }

    if (fullscreen) {
        BackHandler { fullscreen = false }
        Dialog(onDismissRequest = { fullscreen = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    body(Modifier.fillMaxSize(), true)
                    Row(
                        Modifier.align(Alignment.TopEnd).padding(10.dp).background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                            RoundedCornerShape(20.dp),
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton({ fullscreen = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                        IconButton({ copy(context, "HTML", block.content) }) { Icon(Icons.Default.ContentCopy, "复制") }
                        IconButton({ preview = !preview; renderState.showPreview = preview }, enabled = canPreview) {
                            Icon(if (preview) Icons.Default.Code else Icons.Default.Visibility, if (preview) "查看代码" else "预览")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HtmlOverlayIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(Color(0x99000000), androidx.compose.foundation.shape.CircleShape)
                .border(0.5.dp, Color.White.copy(alpha = 0.28f), androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                description,
                modifier = Modifier.size(16.dp),
                tint = Color.White.copy(alpha = if (enabled) 0.96f else 0.45f),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SandboxedHtml(
    renderKey: String,
    renderState: VcpHtmlRenderState,
    content: String,
    modifier: Modifier,
    onInteraction: () -> Unit = {},
) {
    val document = remember(content) { sandboxDocument(content) }
    val currentInteraction by rememberUpdatedState(onInteraction)
    val buttonHandler = LocalVcpHtmlButtonHandler.current
    val currentButtonHandler by rememberUpdatedState(buttonHandler)
    // Captured by onRelease; each composition owns exactly one pool lease.
    val leaseGeneration = remember(renderKey) { longArrayOf(0L) }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val lease = VcpHtmlWebViewPool.obtain(context, renderKey) { appContext ->
                WebView(appContext).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    @Suppress("DEPRECATION")
                    run { settings.allowFileAccessFromFileURLs = false; settings.allowUniversalAccessFromFileURLs = false }
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val uri = request.url
                            if (uri.scheme == "vcp-action" && uri.host == "button") {
                                val text = uri.getQueryParameter("text").orEmpty().take(480)
                                if (text.isNotBlank()) view.post { currentButtonHandler?.invoke("[[点击按钮:$text]]") }
                            }
                            return true
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            renderState.hasLoaded = true
                            view.evaluateJavascript(VCP_HTML_BOUNDS_SCRIPT, null)
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onReceivedTitle(view: WebView, title: String?) {
                            if (!title.orEmpty().startsWith("VCPBOUNDS:")) return
                            parseVcpHtmlBounds(title!!.removePrefix("VCPBOUNDS:"))?.let { bounds ->
                                renderState.rootLeftCss = bounds.left
                                renderState.rootTopCss = bounds.top
                                renderState.rootWidthCss = bounds.width
                                renderState.rootHeightCss = bounds.height.coerceAtLeast(1)
                                renderState.hasMeasured = true
                            }
                        }
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                            android.util.Log.d("VcpHtmlPreview", "${message.messageLevel()}: ${message.message()} @${message.lineNumber()}")
                            return true
                        }
                    }
                }
            }
            val webView = lease.webView
            val created = lease.created
            leaseGeneration[0] = lease.generation
            webView.setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) currentInteraction()
                false
            }
            if (created || !renderState.hasLoaded) {
                webView.loadDataWithBaseURL("https://html-preview.invalid/", document, "text/html", "utf-8", null)
            }
            webView
        },
        update = { },
        // LazyColumn disposal only detaches. The six-entry LRU owns destruction.
        onRelease = { VcpHtmlWebViewPool.retain(renderKey, it, leaseGeneration[0]) },
    )
}

internal fun sandboxDocument(content: String): String {
    // Android System WebView versions differ substantially in their handling
    // of CSP-governed srcdoc iframes. Load the generated document directly in
    // this dedicated, bridge-free WebView instead. The WebView itself is the
    // isolation boundary: file/content access and storage are disabled, there
    // is no addJavascriptInterface, and every top-level navigation is blocked.
    val head = """<meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'self' http: https: data: blob:; img-src http: https: data: blob:; style-src 'unsafe-inline' http: https:; font-src http: https: data:; script-src 'unsafe-inline' 'self' http: https:; media-src http: https: data: blob:; connect-src http: https: ws: wss:; frame-src 'self' http: https: data: blob:; form-action 'none'; base-uri 'none'"><style>html,body{box-sizing:border-box;min-height:100%;margin:0;padding:0;background:transparent;overflow:hidden}*,*:before,*:after{box-sizing:inherit}img,video,canvas,svg,iframe{max-width:100%}</style>"""
    val headMatch = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE).find(content)
    if (headMatch != null) {
        val at = headMatch.range.last + 1
        return content.substring(0, at) + head + content.substring(at)
    }
    val htmlMatch = Regex("<html\\b[^>]*>", RegexOption.IGNORE_CASE).find(content)
    if (htmlMatch != null) {
        val at = htmlMatch.range.last + 1
        return content.substring(0, at) + "<head>$head</head>" + content.substring(at)
    }
    return "<!doctype html><html><head>$head</head><body>$content</body></html>"
}

private fun copy(context: Context, label: String, text: String) {
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}
