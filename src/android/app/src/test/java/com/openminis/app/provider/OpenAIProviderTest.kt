package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.provider.openai.OpenAIProvider
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenAIProviderTest {
    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAIProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `VCPToolBox 中断地址保留反向代理前缀`() {
        val actual = provider.vcpInterruptUrl(
            "https://example.com/api/v1/chat/completions".toHttpUrl(),
        )
        assertEquals("https://example.com/api/v1/interrupt", actual.toString())
    }

    @Test
    fun `非 Chat Completions 地址不生成中断地址`() {
        val actual = provider.vcpInterruptUrl(
            "https://example.com/v1/responses".toHttpUrl(),
        )
        assertNull(actual)
    }

    @Test
    fun `授权 Agent 取消时向 VCPToolBox 发送中断`() = runBlocking {
        val cascadeProvider = OpenAIProvider(
            apiKey = "test-key",
            model = LLMModel.gpt4oMini,
            basePath = server.url("/v1").toString().trimEnd('/'),
            vcpCascadeStopEnabled = true,
            vcpCascadeStopAllAgents = false,
            vcpCascadeStopAgentIds = setOf("allowed"),
        )
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"success\"}"))

        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                cascadeProvider.streamMessage(
                    messages = listOf(LLMMessage(LLMMessage.Role.USER, "hello")),
                    systemPrompt = null,
                    maxTokens = 32,
                    requestContext = LLMRequestContext(agentId = "allowed"),
                ).collect()
            }
        }
        val chat = server.takeRequest(2, TimeUnit.SECONDS)!!
        cascadeProvider.cancelActiveRequest()
        job.cancelAndJoin()
        val interrupt = server.takeRequest(2, TimeUnit.SECONDS)!!

        assertEquals("/v1/chat/completions", chat.path)
        val requestId = JSONObject(chat.body.readUtf8()).getString("requestId")
        assertEquals("/v1/interrupt", interrupt.path)
        assertEquals(requestId, JSONObject(interrupt.body.readUtf8()).getString("requestId"))
        assertEquals("Bearer test-key", interrupt.getHeader("Authorization"))
    }

    /**
     * Builds an SSE stream body from raw JSON events, terminated by [DONE].
     *
     * sendMessage has always streamed internally ("some providers reject
     * stream=false outright"), so every mock response on this path must be
     * SSE-shaped: the stream parser skips any line that does not start with
     * `data:`, and a plain JSON body would surface as an empty-stream
     * TransientError instead of being parsed.
     */
    private fun sseBody(vararg events: String): String = buildString {
        for (event in events) {
            append("data: $event")
            append("\n\n")
        }
        append("data: [DONE]")
        append("\n\n")
    }

    // -- sendMessage response parsing --

    @Test
    fun `sendMessage parses ChatCompletions response`() = runBlocking {
        val responseBody = sseBody(
            """{"choices":[{"delta":{"content":"Hello from GPT!"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
        )

        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))

        val response = provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        )

        assertEquals("Hello from GPT!", response.text)
        assertEquals("stop", response.stopReason)
        assertEquals(10, response.usage?.inputTokens)
        assertEquals(5, response.usage?.outputTokens)
    }

    @Test
    fun `sendMessage parses cached tokens from prompt_tokens_details`() = runBlocking {
        val responseBody = sseBody(
            """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":50}}}"""
        )

        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)

        // inputTokens is fresh-only (prompt_tokens minus cached), matching the
        // Anthropic convention — see parseChatCompletionsUsage. The full prompt
        // stays available as latestContextTokens.
        assertEquals(50, response.usage?.inputTokens)
        assertEquals(10, response.usage?.outputTokens)
        assertEquals(50, response.usage?.cacheReadInputTokens)
        assertNull(response.usage?.cacheCreationInputTokens)
        assertEquals(100, response.usage?.latestContextTokens)
    }

    @Test
    fun `sendMessage returns null cacheReadInputTokens when zero`() = runBlocking {
        val responseBody = sseBody(
            """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":0}}}"""
        )

        server.enqueue(MockResponse().setBody(responseBody).setHeader("Content-Type", "text/event-stream"))
        val response = provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        assertNull(response.usage?.cacheReadInputTokens)
    }

    @Test
    fun `sendMessage treats empty-choices stream as transient failure`() = runBlocking {
        // A 200 stream that ends with no content and no finish_reason is
        // treated as a dropped/upstream failure (failOnSilentEmptyCompletion) —
        // real OpenAI streams always carry finish_reason before [DONE]. This
        // locks in the current always-streaming contract; the old non-streaming
        // "return empty text" behavior no longer exists.
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[],"usage":{"prompt_tokens":5,"completion_tokens":0}}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024)
        } catch (e: LLMError.TransientError) {
            return@runBlocking
        }
        throw AssertionError("Expected TransientError for empty-choices stream")
    }

    // -- Request construction --

    @Test
    fun `sendMessage includes Bearer auth header`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.path!!.contains("/chat/completions"))
    }

    @Test
    fun `sendMessage includes system prompt as system message`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "test")),
            "You are helpful", 100,
        )

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        // System message should be first
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("You are helpful", messages.getJSONObject(0).getString("content"))
        // User message follows
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `sendMessage omits system message when null`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        val messages = body.getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `sendMessage includes temperature when set`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = 0.8)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(0.8, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `sendMessage omits temperature when null`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100, temperature = null)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(!body.has("temperature"))
    }

    @Test
    fun `sendMessage uses max_completion_tokens for OpenAI`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 2048)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(2048, body.getInt("max_completion_tokens"))
        assertTrue(!body.has("max_tokens"))
    }

    @Test
    fun `sendMessage issues a streaming request internally`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(sseBody("""{"choices":[{"delta":{"content":"ok"}}]}"""))
                .setHeader("Content-Type", "text/event-stream")
        )

        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        // sendMessage has always streamed internally since the "some providers
        // reject stream=false outright" change: the request must say
        // stream=true and carry stream_options.include_usage (OpenAI only).
        assertEquals(true, body.getBoolean("stream"))
        assertTrue(body.has("stream_options"))
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    // -- Streaming --

    @Test
    fun `streamMessage parses SSE events with DONE`() = runBlocking {
        val streamBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\" world\"}}]}")
            appendLine()
            appendLine("data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        server.enqueue(
            MockResponse()
                .setBody(streamBody)
                .setHeader("Content-Type", "text/event-stream")
        )

        val chunks = provider.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "Hi")),
            null, 1024,
        ).toList()

        assertTrue(chunks.any { it is LLMStreamChunk.Started })
        val texts = chunks.filterIsInstance<LLMStreamChunk.Text>()
        assertEquals("Hello", texts[0].text)
        assertEquals(" world", texts[1].text)

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertEquals(1, usageChunks.size)
        assertEquals(5, usageChunks[0].usage.inputTokens)
        assertEquals(2, usageChunks[0].usage.outputTokens)

        assertTrue(chunks.any { it is LLMStreamChunk.Finished })
    }

    @Test
    fun `streamMessage keeps two interleaved ChatCompletions tool calls separate`() = runBlocking {
        val streamBody = sseBody(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"read_file","arguments":"{\"path\":\""}},{"index":1,"id":"call_b","function":{"name":"search_web","arguments":"{\"query\":\""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"kotlin\"}"}},{"index":0,"function":{"arguments":"README.md\"}"}}]},"finish_reason":"tool_calls"}]}""",
        )
        server.enqueue(MockResponse().setBody(streamBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "inspect")), null, 1024,
        ).toList()

        val starts = chunks.filterIsInstance<LLMStreamChunk.ToolUseStart>()
        assertEquals(listOf("call_a", "call_b"), starts.map { it.id })
        assertEquals(listOf("read_file", "search_web"), starts.map { it.name })
        val completes = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(2, completes.size)
        assertEquals("README.md", completes[0].args.getString("path"))
        assertEquals("kotlin", completes[1].args.getString("query"))
    }

    @Test
    fun `连续工具链会回传整批结果并继续下一轮调用`() = runBlocking {
        server.enqueue(MockResponse().setBody(sseBody(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"read_file","arguments":"{\"path\":\"README.md\"}"}},{"index":1,"id":"call_b","function":{"name":"search_web","arguments":"{\"query\":\"kotlin\"}"}}]},"finish_reason":"tool_calls"}]}"""
        )).setHeader("Content-Type", "text/event-stream"))
        server.enqueue(MockResponse().setBody(sseBody(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_c","function":{"name":"write_report","arguments":"{\"path\":\"report.md\"}"}}]},"finish_reason":"tool_calls"}]}"""
        )).setHeader("Content-Type", "text/event-stream"))
        server.enqueue(MockResponse().setBody(sseBody(
            """{"choices":[{"delta":{"content":"done"},"finish_reason":"stop"}]}"""
        )).setHeader("Content-Type", "text/event-stream"))

        val history = mutableListOf(LLMMessage(LLMMessage.Role.USER, "inspect and report"))
        suspend fun nextRound(): List<LLMStreamChunk> = provider.streamMessage(
            history, null, 1024,
        ).toList()
        fun appendToolRound(calls: List<LLMStreamChunk.ToolCallComplete>) {
            history += LLMMessage(
                role = LLMMessage.Role.ASSISTANT, content = "",
                contentParts = calls.map { AgentContentPart.ToolUse(it.id, it.name, it.args) },
            )
            history += LLMMessage(
                role = LLMMessage.Role.USER, content = "",
                contentParts = calls.map {
                    AgentContentPart.ToolResult(it.id, it.name, "result:${it.id}")
                },
            )
        }

        val firstCalls = nextRound().filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(listOf("call_a", "call_b"), firstCalls.map { it.id })
        server.takeRequest() // consume the initial user-only request
        appendToolRound(firstCalls)

        val secondCalls = nextRound().filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(listOf("call_c"), secondCalls.map { it.id })
        val secondRequest = JSONObject(server.takeRequest().body.readUtf8())
        val secondMessages = secondRequest.getJSONArray("messages")
        val assistant = secondMessages.getJSONObject(1)
        assertEquals(listOf("call_a", "call_b"), (0 until assistant.getJSONArray("tool_calls").length()).map {
            assistant.getJSONArray("tool_calls").getJSONObject(it).getString("id")
        })
        assertEquals(listOf("call_a", "call_b"), listOf(
            secondMessages.getJSONObject(2).getString("tool_call_id"),
            secondMessages.getJSONObject(3).getString("tool_call_id"),
        ))
        appendToolRound(secondCalls)

        val finalChunks = nextRound()
        assertEquals("done", finalChunks.filterIsInstance<LLMStreamChunk.Text>().joinToString("") { it.text })
        assertTrue(finalChunks.none { it is LLMStreamChunk.ToolCallComplete })
        val thirdMessages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        assertEquals("call_c", thirdMessages.getJSONObject(thirdMessages.length() - 1).getString("tool_call_id"))
    }

    @Test
    fun `streamMessage sends parallel tool call capability when tools are present`() = runBlocking {
        server.enqueue(MockResponse().setBody(sseBody(
            """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}"""
        )).setHeader("Content-Type", "text/event-stream"))
        val tool = com.openminis.app.data.model.AgentToolDefinition(
            name = "noop", description = "test", parameters = emptyMap(),
        )

        provider.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 1024,
            tools = listOf(tool),
        ).toList()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.getBoolean("parallel_tool_calls"))
        assertEquals(1, body.getJSONArray("tools").length())
    }

    @Test
    fun `Responses API keeps two function call items separate`() = runBlocking {
        val responsesProvider = OpenAIProvider(
            apiKey = "test-key", model = LLMModel.gpt4oMini,
            basePath = server.url("/v1").toString().trimEnd('/'), useResponsesAPI = true,
        )
        val streamBody = sseBody(
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"item_a","call_id":"call_a","name":"read_file"}}""",
            """{"type":"response.output_item.added","item":{"type":"function_call","id":"item_b","call_id":"call_b","name":"search_web"}}""",
            """{"type":"response.function_call_arguments.delta","item_id":"item_b","delta":"{\"query\":\"kotlin\"}"}""",
            """{"type":"response.function_call_arguments.delta","item_id":"item_a","delta":"{\"path\":\"README.md\"}"}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","id":"item_a","arguments":"{\"path\":\"README.md\"}"}}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","id":"item_b","arguments":"{\"query\":\"kotlin\"}"}}""",
            """{"type":"response.completed","response":{"status":"completed","output":[{"type":"function_call"}]}}""",
        )
        server.enqueue(MockResponse().setBody(streamBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = responsesProvider.streamMessage(
            listOf(LLMMessage(LLMMessage.Role.USER, "inspect")), null, 1024,
        ).toList()

        val completes = chunks.filterIsInstance<LLMStreamChunk.ToolCallComplete>()
        assertEquals(2, completes.size)
        assertEquals(listOf("read_file", "search_web"), completes.map { it.name })
        assertEquals("README.md", completes[0].args.getString("path"))
        assertEquals("kotlin", completes[1].args.getString("query"))
    }

    @Test
    fun `streamMessage includes stream_options with include_usage`() = runBlocking {
        val streamBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(streamBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertTrue(body.getBoolean("stream"))
        val streamOptions = body.getJSONObject("stream_options")
        assertTrue(streamOptions.getBoolean("include_usage"))
    }

    @Test
    fun `streamMessage includes temperature in request`() = runBlocking {
        val streamBody = buildString {
            appendLine("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }
        server.enqueue(MockResponse().setBody(streamBody).setHeader("Content-Type", "text/event-stream"))

        provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024, temperature = 1.0).toList()

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertEquals(1.0, body.getDouble("temperature"), 0.001)
    }

    @Test
    fun `streamMessage parses cached tokens in usage`() = runBlocking {
        val streamBody = buildString {
            appendLine("""data: {"choices":[{"delta":{"content":"ok"}}]}""")
            appendLine()
            appendLine("""data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":100,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":50}}}""")
            appendLine()
            appendLine("data: [DONE]")
            appendLine()
        }

        server.enqueue(MockResponse().setBody(streamBody).setHeader("Content-Type", "text/event-stream"))

        val chunks = provider.streamMessage(listOf(LLMMessage(LLMMessage.Role.USER, "Hi")), null, 1024).toList()

        val usageChunks = chunks.filterIsInstance<LLMStreamChunk.Usage>()
        assertEquals(1, usageChunks.size)
        assertEquals(50, usageChunks[0].usage.cacheReadInputTokens)
    }

    // -- Error handling --

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws InvalidApiKey on 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test(expected = LLMError.InvalidApiKey::class)
    fun `sendMessage throws InvalidApiKey on 403`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test(expected = LLMError.RateLimited::class)
    fun `sendMessage throws RateLimited on 429`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))
        provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        Unit
    }

    @Test
    fun `sendMessage parses error body for ProviderError`() = runBlocking {
        val errorBody = """{"error":{"message":"The model does not exist","type":"invalid_request_error"}}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(errorBody))

        try {
            provider.sendMessage(listOf(LLMMessage(LLMMessage.Role.USER, "test")), null, 100)
        } catch (e: LLMError.ProviderError) {
            assertTrue(e.message!!.contains("400"))
            assertTrue(e.message!!.contains("The model does not exist"))
            return@runBlocking
        }
        throw AssertionError("Expected ProviderError")
    }

    // -- Provider metadata --

    @Test
    fun `provider name is OpenAI`() {
        assertEquals("OpenAI", provider.name)
    }

    @Test
    fun `provider model can be changed`() {
        provider.model = LLMModel.gpt4o
        assertEquals(LLMModel.gpt4o, provider.model)
    }
}
