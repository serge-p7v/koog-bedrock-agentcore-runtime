# Koog AgentCore Runtime

A Ktor plugin that enables Koog-based applications to conform to the Amazon Bedrock AgentCore Runtime contract with minimal configuration.

## Features

- **Ktor Plugin**: Simple `install(AgentCoreRuntime)` setup with a lambda-based invocation handler
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

## Requirements

- Kotlin 2.x
- Ktor 3.x
- JVM 21+
