# Koog AgentCore Runtime — Example & Coverage Harness

Deployable example for the [`koog-bedrock-agentcore-runtime`](..)
Ktor plugin, with two faces:

1. **Primary use case** — a minimal vision-streaming chat agent (`App.kt`, ~150 lines).
   This is what a community user starts from.
2. **Coverage harness** — extra dispatch paths in `E2eHarness.kt` that exercise every
   `InvocationInput × InvocationResult` cell against the live AgentCore Runtime, so plugin
   behaviour is verified end-to-end through the AWS proxy (not just in-process).

Both share **one container, one runtime, one IAM role**. The harness only kicks in for
requests that opt into it (an `action` field, a non-text Content-Type, or multipart);
plain chat requests bypass it.

## What's verified end-to-end

The plugin itself has 28 in-process `testApplication` tests covering every shape. This
example deploys to AgentCore and runs nine **client-side** scenarios over the AWS API:

| Group   | # | Scenario                  | Plugin shape exercised                            |
|---------|---|---------------------------|---------------------------------------------------|
| PRIMARY | 1 | plain chat                | Text in / TextStream out                          |
| PRIMARY | 2 | vision chat               | Text in (with image) / TextStream out             |
| PRIMARY | 3 | bad JSON                  | global `Throwable` → structured 500               |
| E2E     | 4 | `action=string-out`       | Text in / Text one-shot out                       |
| E2E     | 5 | `action=image-out`        | Text in / Binary one-shot out                     |
| E2E     | 6 | `action=bytes-stream`     | Text in / **BinaryStream out** (chunked)          |
| E2E     | 7 | `image/png` raw body      | **Binary in** / Text out                          |
| E2E     | 8 | `>64 KB` body             | **Stream in** (`ByteReadChannel`) / Text out      |
| E2E     | 9 | `multipart/form-data`     | **Multipart in** / Text out                       |

Verified results, run on `2026-06-22` against `arn:aws:iam::*:user/bedrock-test`,
`us-east-1`, model `global.anthropic.claude-opus-4-6-v1`:

| # | Test                  | Result | Notes                                                       |
|---|-----------------------|--------|-------------------------------------------------------------|
| 1 | plain chat            | ✅     | 32 SSE events                                               |
| 2 | vision chat           | ✅     | 24 SSE events; model read "HELLO BEDROCK" from the PNG       |
| 3 | bad JSON              | ✅     | structured 500 from global throwable handler                |
| 4 | string-out            | ✅     | plain text body                                             |
| 5 | image-out             | ✅     | 7,759 PNG bytes, magic `89 50 4E 47 0D 0A 1A 0A`             |
| 6 | bytes-stream          | ✅     | 1,024 bytes chunked, deterministic A–Z payload preserved    |
| 7 | binary-in             | ✅     | `Content-Type: image/png` → `InvocationInput.Binary`, 7,759 |
| 8 | stream-in (>64 KB)    | ✅     | 100,000 bytes via `InvocationInput.Stream`                   |
| 9 | multipart-in          | ✅     | parts: `FormItem:prompt`, `FileItem:image`                  |

## Layout

```
build.gradle.kts                                # composite build → .. (parent plugin)
settings.gradle.kts
Dockerfile                                       # linux/arm64 (AgentCore requirement)
src/main/kotlin/com/example/koogagentcore/
    App.kt          # ← primary vision-chat agent + handler dispatch
    E2eHarness.kt   # ← coverage harness for non-primary input/output shapes
deploy/
    smoke-local.sh        # local /ping + chat smoke on :8090
    build-and-push.sh     # gradle fatJar → arm64 image → ECR
    deploy.sh             # IAM role + AgentCore runtime (idempotent)
    test.sh               # 3 PRIMARY + 6 E2E scenarios against the deployed runtime
```

## How the dispatch works

```kotlin
// App.kt — Application.module()
install(AgentCoreRuntime) {
    binaryStreamThresholdBytes = 64 * 1024
    handlerTimeoutMillis = 60_000
    handler = { input, _ ->
        when (input) {
            is InvocationInput.Text      -> handleText(input.body)        // primary or e2e
            is InvocationInput.Binary    -> E2eHarness.handleBinary(input)
            is InvocationInput.Stream    -> E2eHarness.handleStream(input)
            is InvocationInput.Multipart -> E2eHarness.handleMultipart(input)
        }
    }
}
```

`handleText` peeks at the JSON body: if there's an `action` field, route to
`E2eHarness.handleAction(...)`; otherwise treat it as a `ChatRequest` and stream
Bedrock token deltas back as SSE.

The e2e harness is deliberately self-contained in `E2eHarness.kt` — community readers can
delete that file and the dispatch lines that reference it without breaking the primary
agent.

## Deploy

Prereqs: Docker Desktop (arm64), AWS CLI configured, Bedrock model access in `us-east-1`.

```bash
./deploy/build-and-push.sh   # gradle fatJar → arm64 image → ECR
./deploy/deploy.sh           # IAM role + runtime (idempotent, IAM auth)
./deploy/test.sh             # 9 client-side scenarios
```

## Local development

```bash
gradle fatJar
deploy/smoke-local.sh   # uses port 8090; needs AWS creds for the real Bedrock call
```

## Cleanup

```bash
ID=$(aws bedrock-agentcore-control list-agent-runtimes --region us-east-1 --no-paginate \
  --query "agentRuntimes[?agentRuntimeName=='koog_agentcore_example_default'].agentRuntimeId | [0]" --output text)
aws bedrock-agentcore-control delete-agent-runtime --region us-east-1 --agent-runtime-id "$ID"
aws iam delete-role-policy --role-name KoogAgentCoreExampleRole --policy-name AgentCoreExecutionPolicy
aws iam delete-role --role-name KoogAgentCoreExampleRole
aws ecr delete-repository --repository-name koog-agentcore-example --region us-east-1 --force
```
