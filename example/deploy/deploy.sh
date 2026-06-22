#!/usr/bin/env bash
# Create one IAM role and two AgentCore Runtimes (default + stream mode), IAM-authorized.
# Outputs are written to deploy/runtime-info.json for the test script to consume.
set -euo pipefail
cd "$(dirname "$0")/.."

ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
REGION=${AWS_REGION:-us-east-1}
IMAGE_URI=$(cat deploy/image-uri.txt)

ROLE_NAME=KoogAgentCoreExampleRole
RUNTIME_DEFAULT=koog_agentcore_example_default
RUNTIME_STREAM=koog_agentcore_example_stream

echo ">>> Account: ${ACCOUNT_ID}  Region: ${REGION}"
echo ">>> Image:   ${IMAGE_URI}"

# --- IAM role ---------------------------------------------------------------
TRUST=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Service": "bedrock-agentcore.amazonaws.com" },
    "Action": "sts:AssumeRole",
    "Condition": {
      "StringEquals": { "aws:SourceAccount": "${ACCOUNT_ID}" },
      "ArnLike":      { "aws:SourceArn": "arn:aws:bedrock-agentcore:${REGION}:${ACCOUNT_ID}:*" }
    }
  }]
}
JSON
)

POLICY=$(cat <<JSON
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow",
      "Action": ["ecr:BatchGetImage","ecr:GetDownloadUrlForLayer","ecr:GetAuthorizationToken"],
      "Resource": "*" },
    { "Effect": "Allow",
      "Action": ["logs:CreateLogGroup","logs:CreateLogStream","logs:PutLogEvents","logs:DescribeLogStreams","logs:DescribeLogGroups"],
      "Resource": "arn:aws:logs:${REGION}:${ACCOUNT_ID}:*" },
    { "Effect": "Allow",
      "Action": ["bedrock:InvokeModel","bedrock:InvokeModelWithResponseStream"],
      "Resource": "*" }
  ]
}
JSON
)

if ! aws iam get-role --role-name "${ROLE_NAME}" >/dev/null 2>&1; then
  echo ">>> Creating IAM role ${ROLE_NAME}"
  aws iam create-role --role-name "${ROLE_NAME}" \
    --assume-role-policy-document "${TRUST}" >/dev/null
else
  echo ">>> Updating trust policy on existing role ${ROLE_NAME}"
  aws iam update-assume-role-policy --role-name "${ROLE_NAME}" \
    --policy-document "${TRUST}" >/dev/null
fi

aws iam put-role-policy --role-name "${ROLE_NAME}" \
  --policy-name AgentCoreExecutionPolicy \
  --policy-document "${POLICY}" >/dev/null
ROLE_ARN=$(aws iam get-role --role-name "${ROLE_NAME}" --query Role.Arn --output text)
echo ">>> Role: ${ROLE_ARN}"

# Allow the role to propagate
sleep 8

# --- Helper: create or update agent runtime --------------------------------
upsert_runtime () {
  local NAME=$1
  local MODE=$2
  echo ">>> Upserting runtime ${NAME} (MODE=${MODE})"
  local EXISTING_ID
  EXISTING_ID=$(aws bedrock-agentcore-control list-agent-runtimes --region "${REGION}" \
    --query "agentRuntimes[?agentRuntimeName=='${NAME}'].agentRuntimeId | [0]" --output text 2>/dev/null || echo "None")

  local PAYLOAD
  PAYLOAD=$(cat <<JSON
{
  "agentRuntimeArtifact": { "containerConfiguration": { "containerUri": "${IMAGE_URI}" } },
  "networkConfiguration":  { "networkMode": "PUBLIC" },
  "environmentVariables":  { "MODE": "${MODE}" },
  "roleArn": "${ROLE_ARN}"
}
JSON
)

  if [[ "${EXISTING_ID}" == "None" || -z "${EXISTING_ID}" ]]; then
    aws bedrock-agentcore-control create-agent-runtime --region "${REGION}" \
      --agent-runtime-name "${NAME}" \
      --cli-input-json "${PAYLOAD}" >/dev/null
  else
    aws bedrock-agentcore-control update-agent-runtime --region "${REGION}" \
      --agent-runtime-id "${EXISTING_ID}" \
      --cli-input-json "${PAYLOAD}" >/dev/null
  fi
}

upsert_runtime "${RUNTIME_DEFAULT}" "default"
upsert_runtime "${RUNTIME_STREAM}"  "stream"

# --- Wait for both to be READY ---------------------------------------------
wait_ready () {
  local NAME=$1
  for i in $(seq 1 60); do
    local STATUS
    STATUS=$(aws bedrock-agentcore-control list-agent-runtimes --region "${REGION}" \
      --query "agentRuntimes[?agentRuntimeName=='${NAME}'].status | [0]" --output text 2>/dev/null || true)
    case "${STATUS}" in
      READY)   echo ">>> ${NAME}: READY"; return 0 ;;
      FAILED|UPDATE_FAILED|CREATE_FAILED)
               echo "!!! ${NAME}: ${STATUS}"; return 1 ;;
      *)       echo "    ${NAME}: ${STATUS:-?} (${i}/60)"; sleep 5 ;;
    esac
  done
  echo "!!! ${NAME}: timed out"; return 1
}

wait_ready "${RUNTIME_DEFAULT}"
wait_ready "${RUNTIME_STREAM}"

# --- Capture ARNs -----------------------------------------------------------
ARN_DEFAULT=$(aws bedrock-agentcore-control list-agent-runtimes --region "${REGION}" \
  --query "agentRuntimes[?agentRuntimeName=='${RUNTIME_DEFAULT}'].agentRuntimeArn | [0]" --output text)
ARN_STREAM=$(aws bedrock-agentcore-control list-agent-runtimes --region "${REGION}" \
  --query "agentRuntimes[?agentRuntimeName=='${RUNTIME_STREAM}'].agentRuntimeArn | [0]" --output text)

cat > deploy/runtime-info.json <<JSON
{
  "region": "${REGION}",
  "default_runtime": { "name": "${RUNTIME_DEFAULT}", "arn": "${ARN_DEFAULT}" },
  "stream_runtime":  { "name": "${RUNTIME_STREAM}",  "arn": "${ARN_STREAM}" }
}
JSON

echo
echo ">>> Deployed:"
echo "    default: ${ARN_DEFAULT}"
echo "    stream:  ${ARN_STREAM}"
echo "    info written to deploy/runtime-info.json"
