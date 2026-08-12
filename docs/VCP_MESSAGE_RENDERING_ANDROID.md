# Android VCP 消息块与 HTML 富内容渲染

> 状态：第一版开发验证完成
>
> 开发分支：`feature/new-chat-ui-validation`
>
> 更新时间：2026-08-12
> 适用工程：`src/android`

## 1. 背景与目标

RikkaMinis 原有 Android 对话页已经支持原生 Provider 思考块、工具调用、Markdown、KaTeX、表格和媒体，但不能识别 VCP 模型在普通文本中输出的专用块协议，也不能直接展示 VCP 生成的 HTML 气泡。

本次改造参考 VCPMobile 的消息 Block 设计，在不替换现有 Compose/Markdown 内核的前提下新增：

- VCP 特定文本协议解析；
- VCP 思考链折叠和流式展示；
- VCP Tool Use / Tool Result 折叠展示；
- HTML Fence、完整 HTML 文档和 HTML Fragment 预览；
- 独立原始 `<img>` 原生渲染；
- HTML 按钮向聊天发送 VCP 点击消息；
- HTML WebView 离屏保活、尺寸持久化和 `#vcp-root` 实时测量；
- 无外框 HTML 气泡和按需显示的悬浮操作按钮。

本次没有把聊天整体改成 WebView，也没有把 VCP 文本工具块伪装成 RikkaMinis 原生工具调用。

---

## 2. 总体架构

当前渲染链路如下：

```text
ChatMessage
  └─ AssistantBlock[]（原有 Provider 顺序）
       ├─ thinking  ──────────────→ 原生 ThinkingBlock
       ├─ tool_use ───────────────→ 原生 ToolCallPill
       ├─ info ───────────────────→ 原生 InfoBlock
       └─ text
            └─ VcpContentParser
                 ├─ Markdown
                 ├─ Thought
                 ├─ ToolUse
                 ├─ ToolResult
                 ├─ HtmlPreview
                 └─ Image
                      ↓
               FlatChatItem.AssistantVcpBlock
                      ↓
                    LazyColumn
                      ↓
                   VcpBlockView
```

关键原则：

1. **保留来源顺序**：VCP 块在每个原生 `text` 块内部展开，不破坏 Provider reasoning、原生工具和文本的先后顺序。
2. **来源隔离**：VCP 文本工具块只负责展示；原生工具块继续负责真实执行、停止、重试和从当前位置重跑。
3. **稳定块与流式尾块分离**：闭合块成为稳定块；未闭合的最后一个特殊块以 `STREAMING` 或 `INCOMPLETE` 状态保留。
4. **旧会话兼容**：没有 `text` 类型块、正文仅存于 `ChatMessage.content` 的历史消息也会经过 VCP 解析。

---

## 3. 新增数据模型

文件：

```text
src/android/app/src/main/java/com/openminis/app/ui/chat/vcp/VcpContentBlock.kt
```

### 3.1 Block 类型

```kotlin
VcpContentBlock.Markdown
VcpContentBlock.Thought
VcpContentBlock.ToolUse
VcpContentBlock.ToolResult
VcpContentBlock.HtmlPreview
VcpContentBlock.Image
```

### 3.2 完成状态

```kotlin
VcpBlockCompletion.STABLE
VcpBlockCompletion.STREAMING
VcpBlockCompletion.INCOMPLETE
```

含义：

- `STABLE`：已发现合法结束标记，可冻结渲染；
- `STREAMING`：当前消息仍在输出，特殊块尚未闭合；
- `INCOMPLETE`：流已结束但特殊块缺少结束标记，仍保留并降级展示，避免吞内容。

### 3.3 稳定指纹

Block 根据类型与原始内容计算 SHA-256 截断指纹，用于：

- HTML 渲染状态 Key；
- WebView保活 Key；
- 内容变更后实例失效；
- 测试和诊断。

LazyColumn 的流式列表 Key 不包含持续变化的内容 hash，避免每个 chunk 都销毁并重建列表项。

---

## 4. VCP 协议解析

文件：

```text
src/android/app/src/main/java/com/openminis/app/ui/chat/vcp/VcpContentParser.kt
```

### 4.1 思考链

支持 VCP 元思考链：

```text
[--- VCP元思考链: 主题 ---]
思考内容
[--- 元思考链结束 ---]
```

主题可省略。

支持标准标签：

```html
<think>...</think>
<thinking>...</thinking>
```

两种来源会归一化为 `Thought`，同时保留来源字段：

```text
VCP_META
THINK_TAG
```

### 4.2 VCP Tool Use

支持：

```text
<<<[TOOL_REQUEST]>>>
tool_name:「始」DailyNote「末」
...
<<<[END_TOOL_REQUEST]>>>
```

工具名也支持：

```xml
<tool_name>DailyNote</tool_name>
```

### 4.3 VCP Tool Result

支持：

```text
[[VCP调用结果信息汇总:
- 工具名称: Search
- 执行状态: success
- 返回内容: 第一行
  后续行
VCP调用结果结束]]
```

解析结果包含：

- `toolName`；
- `status`；
- `details: List<Detail>`；
- `footer`；
- 多行详情值。

### 4.4 HTML

支持三种来源：

#### HTML Fence

````markdown
```html
<div>...</div>
```
````

#### 完整 HTML 文档

```html
<!DOCTYPE html>
<html>...</html>
```

#### HTML Container Fragment

支持行首容器：

```text
div, section, article, header, footer,
main, aside, figure, figcaption
```

支持开始标签跨行，例如：

```html
<div id="vcp-root" style="
    padding: 25px;
    border-radius: 20px;
">
    <div>...</div>
</div>
```

解析器会：

- 从最外层开始标签扫描真正的 `>`；
- 忽略单双引号属性中的字符；
- 对同名标签做嵌套深度计数；
- 找到与最外层对应的结束标签；
- 将整段输出为一个 HTML Preview，而不是把内层 `<div>` 拆成多个控件。

### 4.5 原始 `<img>`

独立的原始图片标签会被解析为原生图片块：

```html
<img src="https://cdn.example.com/image.jpg" width="160" alt="图片">
```

也支持跨行标签。读取属性：

- `src`；
- `alt`；
- `title`；
- `width`。

容器内部的 `<img>` 不会被单独拆分，由 HTML WebView自行渲染。

### 4.6 防误判

解析器已处理：

- VCP 块标记必须满足对应行首规则；
- fenced code 内出现的 VCP 示例不会触发；
- 普通正文中以行内代码提到 `<<<[TOOL_REQUEST]>>>` 不触发；
- CRLF；
- 未闭合块；
- 同一文本内 Markdown 与多个特殊块的顺序。

---

## 5. 思考块 UI

实现文件：

```text
VcpBlockUI.kt
```

效果：

- 显示 VCP 主题或默认“思维链”；
- 默认折叠历史思考；
- 当前流式思考默认展开；
- 未完成时显示进度指示；
- 点击 Header 展开/折叠；
- 用户手动操作后，不再因后续 chunk 重置选择；
- 展开内容复用现有 `MarkdownBlock`，支持代码、列表、表格和公式。

现有 Provider reasoning 仍走原生 `ThinkingBlock`，本次未合并其数据模型。

---

## 6. VCP 工具块 UI

### 6.1 Tool Use

- 默认折叠；
- 显示 `VCP-ToolUse`、工具名和接收状态；
- 参数使用等宽文本；
- 不提供原生工具的 Stop、Retry、Rerun 按钮。

### 6.2 Tool Result

- 默认折叠；
- 显示工具名称和执行状态；
- 详情值使用紧凑 Markdown；
- 支持单项复制；
- 支持复制全部；
- 支持 Footer。

VCP 文本状态只用于展示，不覆盖真实原生工具状态。

---

## 7. HTML 气泡渲染

主要文件：

```text
VcpBlockUI.kt
VcpHtmlBounds.kt
VcpHtmlRenderStore.kt
```

### 7.1 默认行为

- 未闭合、仍在流式的 HTML：显示源代码，不执行半截脚本；
- 稳定 HTML：默认直接预览；
- 不显示 HTML 卡片标题栏；
- 不显示宿主边框和固定白色外框；
- HTML 自身的 `#vcp-root` 就是视觉气泡。

### 7.2 HTML 文档包装

HTML Fragment 会包装为完整文档；已有 `<html>` / `<head>` 的文档会在原有 Head 中注入：

- viewport；
- CSP；
- 透明页面基础样式；
- 响应式媒体约束。

宿主页样式：

```css
html, body {
    margin: 0;
    padding: 0;
    background: transparent;
    overflow: hidden;
}
```

### 7.3 外部资源

按当前产品要求，HTML 和原生图片允许：

- HTTP / HTTPS；
- 外部图片、CSS、字体、脚本；
- HTTP/HTTPS API；
- WS/WSS；
- data/blob 媒体；
- localhost 或后续云端 VCP 资源服务。

WebView设置为允许 mixed content，以兼容 HTTPS 壳页面引用 HTTP VCP 资源。

### 7.4 安全边界

当前仍保留：

- 使用专用 WebView；
- 没有通用 `addJavascriptInterface`；
- 禁止文件访问；
- 禁止 ContentProvider 访问；
- 关闭 DOM Storage；
- 拦截所有普通顶层导航；
- 表单提交由 CSP 禁止；
- WebView池淘汰时停止并销毁实例。

注意：为了支持模型生成的交互式 HTML，当前允许远程脚本和网络连接。这比纯静态富文本风险更高，详见“当前限制”。

---

## 8. `#vcp-root` 实时尺寸协议

VCP HTML 推荐明确提供：

```html
<div id="vcp-root">...</div>
```

根节点查找顺序：

```text
#vcp-root
[data-vcp-root]
body.firstElementChild
body
```

注入脚本监听：

- `ResizeObserver`；
- `MutationObserver`；
- 图片 load/error；
- `document.fonts.ready`；
- window load；
- requestAnimationFrame；
- 100ms、400ms、1000ms 兜底测量。

尺寸通过受控 title 协议返回：

```text
VCPBOUNDS:left,top,width,height
```

没有为尺寸报告开放通用 Java对象 Bridge。

Compose保存：

- root left/top；
- root width/height；
- 是否完成首次测量。

最终高度使用：

```text
rootTop + rootHeight + 24dp 阴影余量
```

已移除原有 `560dp` 硬上限，避免长气泡底部被截断。

---

## 9. HTML WebView 保活

VCPMobile 的消息列表不回收 DOM，只用 `content-visibility:auto` 跳过离屏绘制；Compose `LazyColumn` 会直接 dispose 离屏 item，因此 Android 端增加了有限保活池。

### 9.1 状态 Store

`VcpHtmlRenderStore` 保存：

- 根节点尺寸；
- 测量状态；
- 代码/预览模式；
- 首次加载状态。

最多保存 128 个状态，按访问顺序淘汰。

### 9.2 WebView LRU

`VcpHtmlWebViewPool`：

- Lazy item释放时只 detach，不立即 destroy；
- 再次出现时重新挂载同一个 WebView；
- CSS动画、JS状态和已加载资源保持；
- 最多保留 6 个 idle WebView；
- 活跃 WebView不计入 idle 淘汰数量；
- 超限销毁最久未使用的 idle 实例。

### 9.3 OEM重挂载恢复

WebView重新挂载时执行：

```text
VISIBLE
alpha = 1
onResume
requestLayout
invalidate
window resize event
```

用于恢复部分 OEM WebView在 detach/reparent 后不主动提交的 Surface。

### 9.4 租约代次

每次 obtain 都生成 generation lease。旧 Lazy item迟到的 `onRelease` 只有在 generation 仍匹配时才允许 detach，避免：

```text
新 item已经挂载 WebView
→ 旧 item迟到释放
→ 错误移除新 item中的 WebView
→ 页面变空白
```

---

## 10. HTML 悬浮操作按钮

稳定 HTML 默认不显示固定工具栏。

点击 HTML 后，在 `#vcp-root` 右上角显示三个图标：

- 复制 HTML；
- 代码/预览切换；
- 全屏。

特点：

- 不使用共同白色胶囊底；
- 独立透明按钮；
- 根据 root left/top/width 定位；
- 位置限制在聊天可用宽度内；
- 3秒后自动淡出；
- 再次点击重新显示并计时。

全屏模式保留独立悬浮操作区。

---

## 11. HTML 交互按钮协议

参考 VCPMobile，页面内任意 `<button>` 都会被统一拦截。

发送内容优先级：

```text
data-send
> onclick 中的 input(...)
> 按钮 textContent
```

支持：

```html
<button data-send="想跟你贴贴！">申请贴贴</button>
```

```html
<button onclick="input('想跟你贴贴！')">申请贴贴</button>
```

```html
<button>申请贴贴</button>
```

点击后：

- 按钮禁用；
- 透明度降低；
- 文本追加 `✓`；
- 发送：

```text
[[点击按钮:内容]]
```

- 最长内容限制约480字符；
- 通过现有 `performSendOrEnqueue()` 发送；
- Agent运行中时进入现有排队逻辑。

WebView使用受控 URL：

```text
vcp-action://button?text=...
```

宿主拦截后发送，不真正导航。普通顶层 URL仍被阻止。

---

## 12. 修改和新增文件

### 修改

#### `ChatFlatItems.kt`

- 新增 `FlatChatItem.AssistantVcpBlock`；
- 接入 VCP parser；
- 在原生 text 块内部展开 VCP blocks；
- 支持 legacy `message.content`；
- 增加 owningMessageId、去重和 compact 状态分支；
- 保持流式 VCP列表 Key稳定。

#### `ChatScreen.kt`

- 增加 `AssistantVcpBlock` 渲染分支；
- 接入消息 Bounds/长按逻辑；
- 提供 `LocalVcpHtmlButtonHandler`；
- 将 HTML 按钮连接到 `performSendOrEnqueue()`。

### 新增源码

```text
src/android/app/src/main/java/com/openminis/app/ui/chat/vcp/
├── VcpBlockUI.kt
├── VcpContentBlock.kt
├── VcpContentParser.kt
├── VcpHtmlBounds.kt
└── VcpHtmlRenderStore.kt
```

职责：

| 文件 | 职责 |
|---|---|
| `VcpContentBlock.kt` | VCP Block类型、完成状态、hash |
| `VcpContentParser.kt` | 静态/流式 VCP 文本协议解析 |
| `VcpBlockUI.kt` | 思考、工具、图片、HTML Compose UI |
| `VcpHtmlBounds.kt` | `#vcp-root` 尺寸和按钮事件注入协议 |
| `VcpHtmlRenderStore.kt` | HTML状态与 WebView LRU保活池 |

### 新增测试

```text
src/android/app/src/test/java/com/openminis/app/ui/chat/vcp/
├── VcpContentParserTest.kt
├── VcpFlatItemsIntegrationTest.kt
├── VcpHtmlBoundsTest.kt
└── VcpHtmlSandboxTest.kt
```

覆盖：

- 完整和未闭合思考链；
- fenced code防误判；
- Tool Use / Result；
- 多行 Tool Result；
- HTML fence/document/container；
- 多行最外层标签；
- 嵌套 div；
- 多行 img；
- CRLF；
- legacy消息；
- 流式列表 Key；
- `#vcp-root` Bounds协议；
- HTML文档包装；
- 外部资源策略；
- HTML按钮协议注入。

---

## 13. 构建与测试

环境：

```sh
cd /home/nova/workspace/RikkaMinis/src/android
export JAVA_HOME=/home/nova/tools/jdk-17.0.20+8
export PATH="$JAVA_HOME/bin:$PATH"
```

运行 VCP测试：

```sh
./gradlew :app:testDebugUnitTest --tests 'com.openminis.app.ui.chat.vcp.*'
```

构建 Debug APK：

```sh
./gradlew :app:assembleDebug
```

当前验证结果：

```text
BUILD SUCCESSFUL
```

APK：

```text
src/android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 14. 当前限制

### 14.1 超过 WebView池容量后仍会重建

当前最多保留6个 idle HTML WebView。超过容量后，最旧实例会销毁；再次滚到该块时会重新渲染，CSS入场动画可能重播。

尚未实现最终规划中的截图快照降级。

### 14.2 HTML快照缓存未实现

目前没有：

- stable HTML Bitmap快照；
- 被池淘汰后先显示快照；
- 点击快照恢复 live WebView；
- 快照磁盘缓存。

### 14.3 HTML状态不是数据库持久化

尺寸、预览模式和 WebView状态仅在当前 App进程内保存。进程重启后会重新加载 HTML。

### 14.4 HTML权限模型较宽

为兼容云端部署和外部资源，当前允许：

- 任意 HTTP/HTTPS子资源；
- 远程脚本；
- 网络连接；
- WS/WSS。

虽然文件访问、Content访问和通用 Java Bridge均关闭，但模型生成 HTML仍属于可执行不可信内容。后续应增加“安全静态模式 / 交互模式”分级和用户授权提示。

### 14.5 普通链接暂不外跳

WebView顶层导航统一拦截。目前 HTML按钮支持受控消息发送，但普通 `<a href>` 尚未连接到 RikkaMinis `ChatLinkResolver` 或系统浏览器。

### 14.6 图片点击未进入统一画廊

独立 `<img>` Block支持原生全屏画廊；HTML WebView内部的图片点击尚未接入 RikkaMinis图片画廊。

### 14.7 VCP Tool Result能力仍有限

当前未实现：

- Result详情图片自动检测；
- 全屏工具结果浏览；
- 原始内容Tab；
- VCP Tool KV参数编辑器样式；
- VCP交互工具 Overlay。

### 14.8 HTML代码未语法高亮

代码模式目前使用等宽纯文本，没有 syntect、Prism或项目 `SyntaxHighlighter` 的 HTML token高亮。

### 14.9 Provider reasoning尚未统一

原生 Provider reasoning和 VCP Thought共用相似视觉，但仍是两套模型和组件。

### 14.10 超长 HTML完整展开

为避免裁切，稳定 HTML已取消560dp上限。极长 HTML可能形成非常高的 LazyColumn item，影响滚动锚点和测量性能。后续应对超长内容提供折叠、快照或全屏策略。

---

## 15. 后续规划

### P0：HTML快照与淘汰降级

- WebView首次稳定后捕获 Bitmap；
- Store保存快照和最终尺寸；
- WebView池淘汰后显示快照；
- 滚动回来不重建、不重播动画；
- 用户点击后按需恢复 live WebView；
- 限制快照内存和分辨率。

### P0：生命周期清理

- 会话关闭时清理对应 HTML Store和 WebView；
- 消息删除/编辑时精确清理；
- App进入后台时暂停不可见 WebView；
- 内存警告时主动淘汰 idle实例和快照。

### P1：安全/交互双模式

建议增加：

```text
安全预览：禁止远程脚本和 connect
交互预览：允许脚本、API和 WebSocket
```

交互模式由用户主动启用或按可信来源策略授权。

### P1：HTML链接和图片事件

- `<a>` 接入 `ChatLinkResolver`；
- HTML内部图片接入 `ImageGalleryViewer`；
- 下载行为需要明确确认；
- 支持 `minis://` 资源的受控解析。

### P1：Mermaid

- 识别 ` ```mermaid `；
- 流式期间显示代码；
- 稳定后渲染 SVG；
- hash缓存；
- 点击全屏；
- 失败回退代码。

### P1：Tool Result增强

- 图片 URL / data URI自动预览；
- 全屏查看；
- 复制全部；
- 原始内容；
- 更完整的状态颜色和错误展示。

### P2：其他 VCP Block

按优先级补充：

- Diary；
- Tool Call Summary；
- Role Divider；
- `[[点击按钮:...]]` 独立 Button Block；
- Style Block（仅作用于对应 HTML，不影响 Compose主界面）；
- VCP行内 highlight/alert/quoted标记。

### P2：统一 ConversationBlock

目前 VCP作为 `FlatChatItem.AssistantVcpBlock` 接入。后续可将：

```text
Native Thinking
Native Tool
VCP Thought
VCP Tool
Markdown
HTML
Info
```

统一为正式 `ConversationBlock` 编译层，进一步缩小 `ChatScreen.kt` 和 `ChatFlatItems.kt` 的职责。

### P2：性能和诊断

- HTML首帧/尺寸稳定耗时日志；
- WebView池命中率；
- 快照内存统计；
- VCP parser大文本基准；
- 解析失败“查看原文”；
- HTML控制台错误详情入口。

---

## 16. 已验证效果

真机开发验证中已确认：

- VCP思考链可流式显示、折叠和展开；
- VCP Tool Use / Result可折叠展示；
- 多行最外层 HTML不会被拆成多个控件；
- HTML稳定后默认直接渲染；
- 外部和 localhost图片可以加载；
- 气泡不再使用固定 HTML卡片标题栏；
- `#vcp-root` 尺寸可以动态更新；
- 长气泡不再受560dp限制裁切；
- 两个 HTML气泡上下反复滚动时可以复用原 WebView；
- CSS动画不会在普通离屏返回时重复播放；
- HTML按钮可以发送 `[[点击按钮:...]]`；
- VCP按钮支持运行中排队发送；
- Debug构建和 VCP单元/集成测试通过。

---

## 17. 非本次变更

工作区中以下未跟踪文件在创建开发分支前已经存在，不属于本功能实现，也未在本文档中列为代码变更：

```text
src/android/app/provider-customization.properties
src/android/app/src/main/assets/proot-aarch64
src/android/app/src/main/jniLibs/arm64-v8a/libproot-loader.so
src/android/app/src/main/jniLibs/arm64-v8a/libproot-loader32.so
src/android/app/src/main/jniLibs/arm64-v8a/libproot.so
```
