# Koog AgentCore Runtime

A Ktor plugin that enables Koog-based applications to conform to the Amazon Bedrock AgentCore Runtime contract with minimal configuration.

## Features

- **Ktor Plugin**: Simple `install(AgentCoreRuntime)` setup with a lambda-based invocation handler
- **Flexible Handlers**: String, structured (text/binary), streaming (SSE), and typed JSON handlers for the `/invocations` endpoint
- **Multimodal Output**: `InvocationResult` abstraction returns plain text or raw binary (image/audio/video/document) with the correct `Content-Type`
- **Streaming**: `streamingInvocationHandler` emits each `Flow` chunk as its own `text/event-stream` `data:` event (e.g. token-by-token from Bedrock `converseStream`)
- **Bedrock Interop**: `ContentBlock.toInvocationResult()` mappers (in `BedrockRuntimeMappers.kt`) convert Bedrock Converse output blocks into HTTP responses, keeping the core plugin provider-neutral
- **Health Checks**: Built-in `/ping` endpoint with Healthy/HealthyBusy/Unhealthy status
- **Async Task Tracking**: `AgentCoreTaskTracker` to prevent premature agent termination during background work
- **Rate Limiting**: Built-in per-client token-bucket throttling for invocations and ping endpoints
- **Koog Integration**: Handler runs within `RoutingContext`, so Koog's `aiAgent()` and other extensions are directly available

## Quick Start

### 1. Add Dependency

```kotlin
// settings.gradle.kts
include("koog-agentcore-runtime")

// build.gradle.kts
dependencies {
    implementation(project(":koog-agentcore-runtime"))
}
```

### 2. Install the Plugin

```kotlin
fun Application.module() {
    install(AgentCoreRuntime) {
        invocationHandler = { body, context ->
            // Process the invocation — Koog's aiAgent() is available here
            aiAgent(body, model = OpenAIModels.Chat.GPT4_1)
        }
    }
}
```

### 3. Run Application

The application will automatically expose:
- `POST /invocations` — Agent processing endpoint
- `GET /ping` — Health check endpoint

## Invocation Handler

The handler receives the request body as a `String` and an `AgentCoreContext` with all AgentCore headers.
It is executed within a `RoutingContext`, so Koog's `aiAgent()` and other routing extensions are directly available:

```kotlin
install(AgentCoreRuntime) {
    invocationHandler = { body, context ->
        val sessionId = context.getHeader(AgentCoreHeaders.SESSION_ID)
        val userId = context.getHeader(AgentCoreHeaders.USER_ID)
        // Process request with Koog agent...
        aiAgent(body, model = OpenAIModels.Chat.GPT4_1)
    }
}
```

> **Tip:** If the [Koog Ktor plugin](https://github.com/JetBrains/koog) is installed, you can pass the `body` parameter directly to `aiAgent()` inside the `invocationHandler`. The plugin makes `aiAgent()` available as a routing extension, so no additional wiring is needed.

## Handler Types

The `/invocations` endpoint supports four mutually exclusive handler styles. When more than one is
configured, the precedence is: **typed `handle<I, O>` > `streamingInvocationHandler` > `outputInvocationHandler` > `invocationHandler`**.

### `invocationHandler` — `String` in, `String` out

The default text handler shown above. The response `Content-Type` is negotiated from the request's
`Accept` header (`application/json`, `text/plain`, `application/octet-stream`, or a single
`text/event-stream` `data:` event).

### `outputInvocationHandler` — structured text or binary output

Returns an `InvocationResult` so the plugin can pick the correct responder. Use this for multimodal
output such as a generated image or audio payload:

```kotlin
install(AgentCoreRuntime) {
    outputInvocationHandler = { body, _ ->
        // InvocationResult.Text(...) for text, or:
        InvocationResult.Binary(pngBytes, ContentType.Image.PNG)
    }
}
```

`InvocationResult.Text` honors `Accept`-based negotiation; `InvocationResult.Binary` is always
written via `respondBytes` using its own `contentType`.

#### Bedrock interop

When working with Amazon Bedrock Converse output, map a `ContentBlock` directly to an
`InvocationResult` using the extensions in `BedrockRuntimeMappers.kt`:

```kotlin
outputInvocationHandler = { body, _ ->
    val response = bedrockClient.converse(/* ... */)
    response.output!!.asMessage().content.first().toInvocationResult()
}
```

`ContentBlock.Text` maps to `InvocationResult.Text`; `Image`/`Audio`/`Video`/`Document` blocks with
inline bytes map to `InvocationResult.Binary` with a `Content-Type` derived from the block format.
Blocks that cannot be represented as a single HTTP body (tool use/result, reasoning, or an
`S3Location`-only source) throw `AgentCoreInvocationException`. These mappers live in a dedicated
file so the core plugin carries no `aws.sdk` dependency and stays client-agnostic.

### `streamingInvocationHandler` — incremental SSE output

Returns a `Flow<String>`; each emitted chunk is written as its own `text/event-stream` `data:`
event and flushed immediately, enabling true token-by-token streaming (e.g. from Bedrock
`converseStream`). The response is always `text/event-stream` regardless of `Accept`.

```kotlin
install(AgentCoreRuntime) {
    streamingInvocationHandler = { body, _ ->
        flow {
            converseStream(prompt = body).collect { emit(it.deltaText) }
        }
    }
}
```

### Typed handler — JSON in, JSON out

Register a typed handler via `handle<I, O>` to deserialize the request body into `I` and serialize
the returned `O` back, delegating to Ktor's `ContentNegotiation`:

```kotlin
install(AgentCoreRuntime) {
    handle<MyRequest, MyResponse> { input, context ->
        MyResponse(answer = process(input.prompt))
    }
}
```

Requires the `ContentNegotiation` plugin (e.g. `json()`) to be installed; install `SSE` as well if
you use the streaming or event-stream paths.

## Configuration

The plugin uses fixed configuration per AgentCore contract:
- **Port**: 8080 (required by AgentCore)
- **Endpoints**: `/invocations`, `/ping` (fixed paths)

### Health Monitoring

The `/ping` endpoint provides intelligent health monitoring:

- Returns `"Healthy"` status when no active tasks (HTTP 200)
- Returns `"HealthyBusy"` when there are active background tasks (HTTP 200)
- Returns `"Unhealthy"` on errors (HTTP 503)

You can provide a custom ping service:

```kotlin
install(AgentCoreRuntime) {
    pingService = AgentCorePingService {
        // Custom health check logic
        AgentCorePingResponse(PingStatus.HEALTHY, HttpStatusCode.OK, System.currentTimeMillis() / 1000)
    }
    invocationHandler = { body, _ -> /* ... */ }
}
```

### Background Task Tracking

Amazon AgentCore Runtime monitors agent health and may shut down agents that appear idle. When your agent starts long-running background tasks, the runtime needs to know the agent is still actively working.

The `/ping` endpoint will return **HealthyBusy** while the `AgentCoreTaskTracker` count is greater than 0.

**How the Runtime Uses This Information:**
- **"Healthy"**: Agent is ready, no background tasks → Runtime may scale down if idle
- **"HealthyBusy"**: Agent is healthy but actively processing → Runtime keeps agent alive
- **"Unhealthy"**: Agent has issues → Runtime may restart or replace agent

### Rate Limiting

Built-in rate limiting protects against excessive requests. Rate limiting is deactivated by default and will be active only if limits are set:

```kotlin
install(AgentCoreRuntime) {
    invocationsRateLimit = 50   // requests per minute per client
    pingRateLimit = 200         // requests per minute per client
    invocationHandler = { body, _ -> /* ... */ }
}
```

**Rate Limit Response (429):**
```json
{"error":"Rate limit exceeded"}
```

Rate limits are applied per client IP address and reset every minute.

## API Reference

### POST /invocations

**Request (defined by user):**
```json
{
  "prompt": "Your prompt here"
}
```

**Success Response (200) (defined by user):**
```json
{
  "response": "Agent response",
  "status": "success"
}
```

### GET /ping

**Response (200):**
```json
{
  "status": "Healthy",
  "time_of_last_update": 1697123456
}
```

**Response (503) — When health check detects issues:**
```json
{
  "status": "Unhealthy",
  "time_of_last_update": 1697123456
}
```

## Example

A full deployable example lives in [`example/`](./example/) — a vision-streaming chat agent
plus an end-to-end coverage harness verifying every plugin shape against a real Amazon
Bedrock AgentCore Runtime deployment.

```bash
cd example
./deploy/build-and-push.sh   # gradle fatJar → arm64 Docker image → ECR
./deploy/deploy.sh           # IAM role + AgentCore Runtime (idempotent)
./deploy/test.sh             # 3 primary chat scenarios + 6 coverage tests
```

The example uses Gradle composite build (`includeBuild("..")`) so it always compiles
against the local plugin source. See [`example/README.md`](./example/README.md) for layout
and verified test results.

## Requirements

- Kotlin 2.x
- Ktor 3.x
- JVM 21+
