package ai.koog.agentcore.runtime

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.sse.*
import io.ktor.server.testing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentCoreRuntimeTest {

    private fun Application.installAgentCoreTestModule(
        handler: suspend io.ktor.server.routing.RoutingContext.(String, AgentCoreContext) -> String = { body, _ -> """{"echo":"$body"}""" },
        invocationsRateLimit: Int = 0,
        pingRateLimit: Int = 0
    ) {
        install(ContentNegotiation) { json() }
        install(SSE)
        install(AgentCoreRuntime) {

            invocationHandler = handler
            this.invocationsRateLimit = invocationsRateLimit
            this.pingRateLimit = pingRateLimit
        }
    }

    @Test
    fun `ping returns healthy status`() = testApplication {
        application { installAgentCoreTestModule() }
        client.get("/ping").apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("Healthy", json["status"]?.jsonPrimitive?.content)
            assertTrue(json["time_of_last_update"]?.jsonPrimitive?.long!! > 0)
        }
    }

    @Test
    fun `invocations returns handler result`() = testApplication {
        application { installAgentCoreTestModule() }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"hello"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertTrue(bodyAsText().contains("echo"))
        }
    }

    @Test
    fun `invocations passes headers via context`() = testApplication {
        application {
            installAgentCoreTestModule(handler = { _, context ->
                val sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID)
                """{"sessionId":"$sessionId"}"""
            })
        }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            header(AgentCoreHeaders.SESSION_ID, "test-session-123")
            setBody("""{"prompt":"hello"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertTrue(bodyAsText().contains("test-session-123"))
        }
    }

    @Test
    fun `invocations returns 500 on handler error`() = testApplication {
        application {
            installAgentCoreTestModule(handler = { _, _ ->
                throw AgentCoreInvocationException("test error")
            })
        }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"hello"}""")
        }.apply {
            assertEquals(HttpStatusCode.InternalServerError, status)
        }
    }

    @Test
    fun `rate limiting returns 429 when exceeded`() = testApplication {
        application {
            installAgentCoreTestModule(pingRateLimit = 2)
        }
        repeat(2) {
            client.get("/ping").apply {
                assertEquals(HttpStatusCode.OK, status)
            }
        }
        client.get("/ping").apply {
            assertEquals(HttpStatusCode.TooManyRequests, status)
        }
    }

    @Test
    fun `ordinary invocation does not mark agent as busy`() = testApplication {
        application { installAgentCoreTestModule() }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"hello"}""")
        }
        client.get("/ping").apply {
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("Healthy", json["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `handler can report busy via exposed task tracker`() = testApplication {
        application {
            installAgentCoreTestModule(handler = { _, context ->
                context.taskTracker.increment()
                """{"started":true}"""
            })
        }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"hello"}""")
        }
        client.get("/ping").apply {
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("HealthyBusy", json["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `invocations honors accept header for response content type`() = testApplication {
        application { installAgentCoreTestModule() }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            setBody("""{"prompt":"hello"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals(ContentType.Text.EventStream, contentType()?.withoutParameters())
            val text = bodyAsText()
            assertTrue(text.startsWith("data: "), "SSE body should be framed as a data event: $text")
            assertTrue(text.endsWith("\n\n"), "SSE body should end with a blank line: $text")
        }
    }

    @Test
    fun `invocations honors accept header for binary response`() = testApplication {
        application { installAgentCoreTestModule(handler = { _, _ -> "binary-payload" }) }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.OctetStream)
            setBody("""{"prompt":"hello"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals(ContentType.Application.OctetStream, contentType()?.withoutParameters())
            assertContentEquals("binary-payload".toByteArray(), bodyAsBytes())
        }
    }

    @Test
    fun `rate limited request does not execute route handler twice`() = testApplication {
        var handlerCalls = 0
        application {
            installAgentCoreTestModule(
                handler = { _, _ ->
                    handlerCalls++
                    """{"ok":true}"""
                },
                invocationsRateLimit = 1
            )
        }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"a"}""")
        }.apply { assertEquals(HttpStatusCode.OK, status) }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"b"}""")
        }.apply {
            assertEquals(HttpStatusCode.TooManyRequests, status)
        }
        assertEquals(1, handlerCalls, "Throttled request must not reach the invocation handler")
    }

    @Test
    fun `invocations with text plain content type`() = testApplication {
        application { installAgentCoreTestModule() }
        client.post("/invocations") {
            contentType(ContentType.Text.Plain)
            setBody("hello world")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertTrue(bodyAsText().contains("hello world"))
        }
    }

    @Serializable
    data class TypedRequest(val prompt: String, val count: Int = 1)

    @Serializable
    data class TypedResponse(val answer: String, val repeated: Int)

    private fun Application.installTypedTestModule(
        handler: suspend io.ktor.server.routing.RoutingContext.(TypedRequest, AgentCoreContext) -> TypedResponse =
            { input, _ -> TypedResponse(answer = "hi ${input.prompt}", repeated = input.count) }
    ) {
        install(ContentNegotiation) { json() }
        install(SSE)
        install(AgentCoreRuntime) {
            handle(handler)
        }
    }

    @Test
    fun `typed handler maps json body into input type and serializes output`() = testApplication {
        application { installTypedTestModule() }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"world","count":3}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("hi world", json["answer"]?.jsonPrimitive?.content)
            assertEquals(3, json["repeated"]?.jsonPrimitive?.int)
        }
    }

    @Test
    fun `typed handler applies serializer defaults for missing fields`() = testApplication {
        application { installTypedTestModule() }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"solo"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("hi solo", json["answer"]?.jsonPrimitive?.content)
            assertEquals(1, json["repeated"]?.jsonPrimitive?.int)
        }
    }

    @Test
    fun `typed handler exposes headers through context`() = testApplication {
        application {
            installTypedTestModule(handler = { input, context ->
                val sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID)
                TypedResponse(answer = "${input.prompt}:$sessionId", repeated = input.count)
            })
        }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            header(AgentCoreHeaders.SESSION_ID, "sess-1")
            setBody("""{"prompt":"p"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("p:sess-1", json["answer"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `no handler configured throws AgentCoreInvocationException`() {
        assertFailsWith<AgentCoreInvocationException> {
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(SSE)
                    install(AgentCoreRuntime) {
                        // no handler configured
                    }
                }
                client.get("/ping")
            }
        }
    }

    @Test
    fun `invocations rate limiting returns 429 when exceeded`() = testApplication {
        application {
            installAgentCoreTestModule(invocationsRateLimit = 1)
        }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"a"}""")
        }.apply { assertEquals(HttpStatusCode.OK, status) }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"b"}""")
        }.apply { assertEquals(HttpStatusCode.TooManyRequests, status) }
    }

    @Test
    fun `task tracker decrement returns status to Healthy`() = testApplication {
        application {
            installAgentCoreTestModule(handler = { _, context ->
                context.taskTracker.increment()
                context.taskTracker.decrement()
                """{"done":true}"""
            })
        }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            setBody("""{"prompt":"hello"}""")
        }
        client.get("/ping").apply {
            val json = Json.parseToJsonElement(bodyAsText()).jsonObject
            assertEquals("Healthy", json["status"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `context exposes all request headers`() = testApplication {
        application {
            installAgentCoreTestModule(handler = { _, context ->
                val headers = context.getHeaders()
                val custom = headers["X-Custom-Header"]
                """{"custom":"$custom"}"""
            })
        }
        client.post("/invocations") {
            contentType(ContentType.Application.Json)
            header("X-Custom-Header", "my-value")
            setBody("""{"prompt":"hello"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertTrue(bodyAsText().contains("my-value"))
        }
    }
}
