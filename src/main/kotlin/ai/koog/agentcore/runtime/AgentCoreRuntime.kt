package ai.koog.agentcore.runtime

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

/**
 * Well-known AgentCore HTTP headers used by Amazon Bedrock AgentCore Runtime.
 *
 * These headers are passed by the AgentCore Runtime when invoking agents and can be
 * accessed through [AgentCoreContext] in the invocation handler.
 */
object AgentCoreHeaders {
    // Core AgentCore Headers
    const val SESSION_ID = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id"
    const val USER_ID = "X-Amzn-Bedrock-AgentCore-Runtime-User-Id"
    const val CUSTOM_HEADER_PREFIX = "X-Amzn-Bedrock-AgentCore-Runtime-Custom-"

    // Authentication & Authorization
    const val AUTHORIZATION = "Authorization"
    const val WORKLOAD_ACCESS_TOKEN = "workloadaccesstoken"
    const val WORKLOAD_ACCESS_TOKEN_RUNTIME = "x-amzn-bedrock-agentcore-runtime-workload-accesstoken"
    const val GUEST_AUTH = "x-aws-guest-auth"

    // AWS Infrastructure
    const val REQUEST_ID = "x-amzn-requestid"
    const val TRACE_ID = "x-amzn-trace-id"
    const val BAGGAGE = "baggage"

    // Proxy Information
    const val PROXY_IP = "x-aws-proxy-ip"
    const val PROXY_PORT = "x-aws-proxy-port"
}

/**
 * Context object containing HTTP headers from AgentCore invocation requests.
 *
 * Provides access to all headers sent by the AgentCore Runtime, including session IDs,
 * user IDs, authentication tokens, and custom headers.
 *
 * Example usage:
 * ```kotlin
 * install(AgentCoreRuntime) {
 *     invocationHandler = { body, context ->
 *         val sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID)
 *         // process request...
 *     }
 * }
 * ```
 */
class AgentCoreContext(private val headers: Headers, val taskTracker: AgentCoreTaskTracker) {
    /** Returns all headers from the invocation request. */
    fun getHeaders(): Headers = headers

    /** Returns the value of a specific header, or null if not present. */
    fun getHeader(headerName: String): String? = headers[headerName]
}

/**
 * Health status for AgentCore ping responses.
 *
 * - [HEALTHY] — Agent is ready, no background tasks. Runtime may scale down if idle.
 * - [HEALTHY_BUSY] — Agent is healthy but actively processing. Runtime keeps agent alive.
 * - [UNHEALTHY] — Agent has issues. Runtime may restart or replace agent.
 */
enum class PingStatus(private val value: String) {
    HEALTHY("Healthy"),
    HEALTHY_BUSY("HealthyBusy"),
    UNHEALTHY("Unhealthy");

    override fun toString(): String = value
}

/**
 * Exception thrown when there are issues with AgentCore invocation.
 */
class AgentCoreInvocationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Tracks active tasks to report [PingStatus.HEALTHY_BUSY] status during health checks.
 *
 * Amazon AgentCore Runtime monitors agent health and may shut down agents that appear idle.
 * When your agent starts long-running background tasks, use this tracker to communicate
 * that the agent is still actively working to avoid premature termination.
 *
 * The `/ping` endpoint will return **HealthyBusy** while the active task count is greater than 0.
 *
 * Example usage:
 * ```kotlin
 * install(AgentCoreRuntime) {
 *     invocationHandler = { body, context ->
 *         context.taskTracker.increment()  // Tell runtime: "I'm starting background work"
 *         launch {
 *             try {
 *                 // Long-running background work
 *             } finally {
 *                 context.taskTracker.decrement()  // Tell runtime: "Background work completed"
 *             }
 *         }
 *         "Task started"
 *     }
 * }
 * ```
 */
class AgentCoreTaskTracker {
    private val activeTasks = AtomicLong(0)

    /** Increments the active task count. Call when starting a background task. */
    fun increment() { activeTasks.incrementAndGet() }

    /** Decrements the active task count. Call when a background task completes. Will not go below 0. */
    fun decrement() {
        activeTasks.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    /** Returns the current number of active tasks. */
    fun getCount(): Long = activeTasks.get()
}

/**
 * Ping response data returned by [AgentCorePingService].
 */
data class AgentCorePingResponse(
    val status: PingStatus,
    val httpStatus: HttpStatusCode,
    val timeOfLastUpdate: Long
)

/**
 * Interface for providing ping status.
 *
 * Implement this interface to customize health check behavior, for example
 * to integrate with external health monitoring systems.
 */
fun interface AgentCorePingService {
    fun getPingStatus(): AgentCorePingResponse
}

/**
 * Default ping service that uses [AgentCoreTaskTracker] for busy detection.
 *
 * Returns:
 * - [PingStatus.HEALTHY] when no active tasks
 * - [PingStatus.HEALTHY_BUSY] when there are active tasks
 * - [PingStatus.UNHEALTHY] on any exception
 */
class StaticAgentCorePingService(private val taskTracker: AgentCoreTaskTracker) : AgentCorePingService {
    private val cachedResponse = AtomicReference<AgentCorePingResponse>()

    override fun getPingStatus(): AgentCorePingResponse {
        return try {
            if (taskTracker.getCount() > 0) {
                updateCachedResponse(PingStatus.HEALTHY_BUSY, HttpStatusCode.OK)
            } else {
                updateCachedResponse(PingStatus.HEALTHY, HttpStatusCode.OK)
            }
        } catch (_: Exception) {
            updateCachedResponse(PingStatus.UNHEALTHY, HttpStatusCode.InternalServerError)
        }
    }

    private fun updateCachedResponse(status: PingStatus, httpStatus: HttpStatusCode): AgentCorePingResponse {
        return cachedResponse.updateAndGet { current ->
            if (current == null || current.status != status) {
                AgentCorePingResponse(status, httpStatus, System.currentTimeMillis() / 1000)
            } else {
                current
            }
        }
    }
}


/**
 * Resolves the response `Content-Type` for the `/invocations` endpoint from the request's
 * `Accept` header, aligning with the content types produced by the reference Java contract
 * (`application/json`, `text/event-stream`, `application/octet-stream`, `text/plain`).
 *
 * Defaults to `application/json` when the `Accept` header is missing or does not match a
 * supported type (e.g. `* / *`).
 */
internal fun resolveResponseContentType(acceptHeader: String?): ContentType {
    if (acceptHeader.isNullOrBlank()) return ContentType.Application.Json
    val accepted = parseHeaderValue(acceptHeader).map { it.value }
    return when {
        accepted.any { it.startsWith(ContentType.Text.EventStream.toString()) } -> ContentType.Text.EventStream
        accepted.any { it.startsWith(ContentType.Application.OctetStream.toString()) } -> ContentType.Application.OctetStream
        accepted.any { it.startsWith(ContentType.Application.Json.toString()) } -> ContentType.Application.Json
        accepted.any { it.startsWith(ContentType.Text.Plain.toString()) } -> ContentType.Text.Plain
        else -> ContentType.Application.Json
    }
}

/**
 * Writes the invocation [result] back to the client using the negotiated [contentType],
 * producing a genuine body for each supported type (matching the reference Java contract):
 *
 * - `text/event-stream`: streamed via a [ByteWriteChannel] as a properly framed SSE `data:` event.
 * - `application/octet-stream`: written as raw bytes (true binary response).
 * - everything else (`application/json`, `text/plain`, ...): written as text.
 */
internal suspend fun respondInvocationResult(call: ApplicationCall, contentType: ContentType, result: String) {
    when (contentType) {
        ContentType.Text.EventStream -> call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            writeStringUtf8("data: $result\n\n")
            flush()
        }
        ContentType.Application.OctetStream ->
            call.respondBytes(result.toByteArray(Charsets.UTF_8), ContentType.Application.OctetStream)
        else -> call.respondText(result, contentType)
    }
}

/**
 * Streams the invocation output [chunks] back to the client as a `text/event-stream` response,
 * writing each emitted chunk as its own properly framed SSE `data:` event and flushing after each
 * one so the client receives output incrementally (e.g. token-by-token from a model stream).
 */
internal suspend fun respondInvocationStream(call: ApplicationCall, chunks: Flow<String>) {
    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
        chunks.collect { chunk ->
            writeStringUtf8("data: $chunk\n\n")
            flush()
        }
    }
}

/**
 * Writes a structured [InvocationResult] back to the client.
 *
 * - [InvocationResult.Text] is written via [respondInvocationResult] using the negotiated [contentType]
 *   (so text/event-stream/octet-stream negotiation still applies).
 * - [InvocationResult.Binary] is written via `respondBytes` using the result's own content type
 *   (derived from the originating block's `format`), regardless of the `Accept` header.
 */
internal suspend fun respondInvocationResult(call: ApplicationCall, contentType: ContentType, result: InvocationResult) {
    when (result) {
        is InvocationResult.Text -> respondInvocationResult(call, contentType, result.value)
        is InvocationResult.Binary -> call.respondBytes(result.bytes, result.contentType)
    }
}

/**
 * Structured result of an invocation that may carry non-text content.
 *
 * Allows handlers to return either plain text or raw binary content (e.g. an image or audio
 * payload produced by a model) so that the plugin can pick the appropriate Ktor responder
 * (`respondText` vs `respondBytes`).
 */
sealed interface InvocationResult {
    /** A textual result, written via `respondText` (honoring the negotiated content type). */
    data class Text(val value: String) : InvocationResult

    /** A binary result, written via `respondBytes` with the given [contentType]. */
    data class Binary(val bytes: ByteArray, val contentType: ContentType) : InvocationResult {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Binary) return false
            return bytes.contentEquals(other.bytes) && contentType == other.contentType
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + contentType.hashCode()
    }
}

/**
 * Handler type for invocation requests.
 *
 * Receives the request body as a [String] and an [AgentCoreContext] with headers,
 * executed within a [RoutingContext] so Koog's `aiAgent()` and other routing
 * extensions are available.
 */
typealias InvocationHandler = suspend RoutingContext.(body: String, context: AgentCoreContext) -> String

/**
 * Handler type for invocation requests that may produce non-text output.
 *
 * Receives the request body as a [String] and an [AgentCoreContext] with headers, and returns an
 * [InvocationResult] so the plugin can pick the correct responder (text vs binary). This is the
 * natural fit for handlers that return media (image/audio) produced by a model, e.g. by mapping a
 * Bedrock `ContentBlock` via [toInvocationResult].
 */
typealias OutputInvocationHandler = suspend RoutingContext.(body: String, context: AgentCoreContext) -> InvocationResult

/**
 * Handler type for invocation requests that produce a **stream** of output chunks.
 *
 * Receives the request body as a [String] and an [AgentCoreContext] with headers, and returns a
 * [Flow] of [String] chunks. Each emitted chunk is written to the client as its own
 * `text/event-stream` `data:` event, so callers receive output incrementally (e.g. token-by-token).
 *
 * This is the natural fit for handlers backed by a streaming model API such as Bedrock's
 * `converseStream`, where each `ContentBlockDelta` text fragment can be emitted as it arrives.
 */
typealias StreamingInvocationHandler = suspend RoutingContext.(body: String, context: AgentCoreContext) -> Flow<String>

/**
 * Typed handler for invocation requests.
 *
 * Receives the request body already deserialized into [I] and an [AgentCoreContext] with headers,
 * and returns a value of type [O] that is serialized back to the client.
 *
 * Deserialization/serialization is delegated to Ktor's `ContentNegotiation` plugin, so the request
 * `Content-Type` (e.g. `application/json`) and the response `Accept` header drive the conversion.
 */
typealias TypedInvocationHandler<I, O> = suspend RoutingContext.(input: I, context: AgentCoreContext) -> O

/**
 * Internal holder describing a typed invocation registered via [AgentCoreRuntimeConfig.handle].
 *
 * Captures the runtime [TypeInfo] of the input and output types so the plugin can delegate
 * deserialization (`call.receive`) and serialization (`call.respond`) to `ContentNegotiation`.
 */
@PublishedApi
internal class TypedInvocation(
    val inputType: TypeInfo,
    val outputType: TypeInfo,
    val handle: suspend RoutingContext.(input: Any?, context: AgentCoreContext) -> Any?
)

/**
 * Configuration for the [AgentCoreRuntime] Ktor plugin.
 */
class AgentCoreRuntimeConfig {
    /**
     * The handler that processes invocation requests.
     * Receives the request body as a String and an [AgentCoreContext] with headers.
     *
     * Either this or a typed handler registered via [handle] must be set before the plugin is
     * installed. If both are set, the typed handler takes precedence.
     */
    var invocationHandler: InvocationHandler? = null

    /**
     * The handler that processes invocation requests and may produce non-text output.
     * Receives the request body as a String and an [AgentCoreContext], returning an [InvocationResult]
     * that the plugin maps to the appropriate responder (text vs binary).
     *
     * Takes precedence over [invocationHandler] but not over a typed handler registered via [handle].
     */
    var outputInvocationHandler: OutputInvocationHandler? = null

    /**
     * The handler that processes invocation requests by streaming output chunks.
     * Receives the request body as a String and an [AgentCoreContext], returning a [Flow] of [String]
     * chunks that the plugin writes as individual `text/event-stream` `data:` events.
     *
     * Takes precedence over [outputInvocationHandler] and [invocationHandler], but not over a typed
     * handler registered via [handle].
     */
    var streamingInvocationHandler: StreamingInvocationHandler? = null

    /**
     * The typed handler registered via [handle], if any.
     */
    @PublishedApi
    internal var typedInvocation: TypedInvocation? = null

    /**
     * Custom ping service. If not set, a default [StaticAgentCorePingService] is used.
     */
    var pingService: AgentCorePingService? = null

    /**
     * Task tracker used to report [PingStatus.HEALTHY_BUSY] while long-running background
     * work is in progress. It is exposed to the invocation handler via [AgentCoreContext.taskTracker].
     * Provide a custom instance to share a tracker across components if needed.
     */
    var taskTracker: AgentCoreTaskTracker = AgentCoreTaskTracker()

    /**
     * Rate limit for `/invocations` endpoint (requests per minute per client). 0 = no limit.
     */
    var invocationsRateLimit: Int = 0

    /**
     * Rate limit for `/ping` endpoint (requests per minute per client). 0 = no limit.
     */
    var pingRateLimit: Int = 0
}

/**
 * Registers a typed invocation handler for the `/invocations` endpoint.
 *
 * The request body is deserialized into [I] and the returned value of type [O] is serialized back
 * to the client. Both directions are handled by Ktor's `ContentNegotiation` plugin, so the request
 * `Content-Type` (e.g. `application/json`) and the response `Accept` header drive the conversion.
 * This is the Kotlin equivalent of the Java starter's typed `@AgentCoreInvocation` method.
 *
 * Requires the `ContentNegotiation` plugin (e.g. with `json()`) to be installed on the application.
 * When set, this takes precedence over [AgentCoreRuntimeConfig.invocationHandler].
 *
 * Example usage:
 * ```kotlin
 * install(AgentCoreRuntime) {
 *     handle<MyRequest, MyResponse> { input, context ->
 *         MyResponse(answer = process(input.prompt))
 *     }
 * }
 * ```
 */
inline fun <reified I : Any, reified O> AgentCoreRuntimeConfig.handle(
    crossinline handler: TypedInvocationHandler<I, O>
) {
    typedInvocation = TypedInvocation(typeInfo<I>(), typeInfo<O>()) { input, context ->
        @Suppress("UNCHECKED_CAST")
        handler(input as I, context)
    }
}

/**
 * Ktor plugin that implements the Amazon Bedrock AgentCore Runtime contract.
 *
 * Provides:
 * - `GET /ping` — health check endpoint with task-aware status reporting
 * - `POST /invocations` — invocation endpoint that forwards to a user-provided handler
 * - Per-client rate limiting on both endpoints
 * - Task tracking for HEALTHY_BUSY status
 *
 * ### Quick Start
 *
 * ```kotlin
 * fun Application.module() {
 *     install(AgentCoreRuntime) {
 *         invocationHandler = { body, context ->
 *             // Process the invocation request
 *             aiAgent(body, model = OpenAIModels.Chat.GPT4_1)
 *         }
 *         // Optional: rate limiting
 *         invocationsRateLimit = 50  // requests per minute per client
 *         pingRateLimit = 200
 *     }
 * }
 * ```
 *
 * ### Endpoints
 *
 * **GET /ping** — Returns health status:
 * ```json
 * {"status":"Healthy","time_of_last_update":1697123456}
 * ```
 *
 * **POST /invocations** — Forwards request body to the configured handler.
 * All AgentCore headers are available via [AgentCoreContext].
 */
val AgentCoreRuntime = createApplicationPlugin(name = "AgentCoreRuntime", createConfiguration = ::AgentCoreRuntimeConfig) {
    val logger = LoggerFactory.getLogger("AgentCoreRuntime")
    val config = pluginConfig
    val taskTracker = config.taskTracker
    val pingService = config.pingService ?: StaticAgentCorePingService(taskTracker)

    val stringHandler = config.invocationHandler
    val outputHandler = config.outputInvocationHandler
    val streamingHandler = config.streamingInvocationHandler
    val typedInvocation = config.typedInvocation
    if (stringHandler == null && outputHandler == null && streamingHandler == null && typedInvocation == null) {
        throw AgentCoreInvocationException(
            "No invocation handler configured. Set invocationHandler, outputInvocationHandler, " +
                "streamingInvocationHandler or register a typed handler via handle<Input, Output> { ... } " +
                "in AgentCoreRuntime plugin configuration."
        )
    }

    val hasInvocationsLimit = config.invocationsRateLimit > 0
    val hasPingLimit = config.pingRateLimit > 0

    if (hasInvocationsLimit || hasPingLimit) {
        application.install(RateLimit) {
            if (hasInvocationsLimit) {
                register(RateLimitName("agentcore-invocations")) {
                    rateLimiter(limit = config.invocationsRateLimit, refillPeriod = 1.minutes)
                    requestKey { call ->
                        call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
                            ?: call.request.local.remoteAddress
                    }
                }
            }
            if (hasPingLimit) {
                register(RateLimitName("agentcore-ping")) {
                    rateLimiter(limit = config.pingRateLimit, refillPeriod = 1.minutes)
                    requestKey { call ->
                        call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
                            ?: call.request.local.remoteAddress
                    }
                }
            }
        }
    }

    application.routing {
        val pingRoute: Route.() -> Unit = {
            get("/ping") {
                val pingResponse = pingService.getPingStatus()
                call.respondText(
                    """{"status":"${pingResponse.status}","time_of_last_update":${pingResponse.timeOfLastUpdate}}""",
                    ContentType.Application.Json,
                    pingResponse.httpStatus
                )
            }
        }

        val invocationsRoute: Route.() -> Unit = {
            post("/invocations") {
                val context = AgentCoreContext(call.request.headers, taskTracker)
                try {
                    if (typedInvocation != null) {
                        val input = call.receive<Any?>(typedInvocation.inputType)
                        val result = typedInvocation.handle(this, input, context)
                        call.respond(result, typedInvocation.outputType)
                    } else if (streamingHandler != null) {
                        val body = call.receiveText()
                        val chunks = streamingHandler(this, body, context)
                        respondInvocationStream(call, chunks)
                    } else if (outputHandler != null) {
                        val body = call.receiveText()
                        val responseContentType = resolveResponseContentType(call.request.header(HttpHeaders.Accept))
                        val result = outputHandler(this, body, context)
                        respondInvocationResult(call, responseContentType, result)
                    } else {
                        val body = call.receiveText()
                        val responseContentType = resolveResponseContentType(call.request.header(HttpHeaders.Accept))
                        val result = stringHandler!!(this, body, context)
                        respondInvocationResult(call, responseContentType, result)
                    }
                } catch (e: AgentCoreInvocationException) {
                    logger.error("Error trying to invoke AgentCore method: ${e.message}", e)
                    call.respondText(
                        """{"error":"${e.message?.replace("\"", "\\\"")}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError
                    )
                }
            }
        }

        if (hasPingLimit) {
            rateLimit(RateLimitName("agentcore-ping"), pingRoute)
        } else {
            pingRoute()
        }

        if (hasInvocationsLimit) {
            rateLimit(RateLimitName("agentcore-invocations"), invocationsRoute)
        } else {
            invocationsRoute()
        }
    }
}
