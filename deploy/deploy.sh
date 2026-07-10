#!/usr/bin/env bash
#
# One-shot deploy for the serverless scheduled setup.
#
#   1. deploys/updates the CloudFormation stack (ECR, S3, ECS, schedule, IAM);
#   2. builds the Docker image and pushes it to the stack's ECR repo;
#   3. prints how to trigger a run immediately.
#
# Required env vars:
#   VPC_ID       VPC to run the task in
#   SUBNET_IDS   comma-separated subnet ids (public subnets recommended)
#
# Optional env vars (with defaults):
#   STACK_NAME (news-job-ads-video)  AWS_REGION (from your CLI config)
#   MODE (scrape)  BRAND ("RightRoads Job News")  ASSIGN_PUBLIC_IP (ENABLED)
#   SCHEDULE (cron(0 6 * * ? *))  TIMEZONE (Asia/Kolkata)
#
# Usage:
#   VPC_ID=vpc-xxx SUBNET_IDS=subnet-a,subnet-b ./deploy/deploy.sh
set -euo pipefail

STACK_NAME="${STACK_NAME:-news-job-ads-video}"
PROJECT_NAME="${PROJECT_NAME:-news-job-ads-video}"
AWS_REGION="${AWS_REGION:-$(aws configure get region || true)}"
MODE="${MODE:-scrape}"
BRAND="${BRAND:-RightRoads Job News}"
ASSIGN_PUBLIC_IP="${ASSIGN_PUBLIC_IP:-ENABLED}"
SCHEDULE="${SCHEDULE:-cron(0 6 * * ? *)}"
TIMEZONE="${TIMEZONE:-Asia/Kolkata}"

: "${VPC_ID:?VPC_ID must be set}"
: "${SUBNET_IDS:?SUBNET_IDS must be set (comma-separated subnet ids)}"
: "${AWS_REGION:?AWS_REGION must be set (or configure a default region)}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${HERE}/.." && pwd)"

echo "==> [1/3] Deploying CloudFormation stack '${STACK_NAME}' in ${AWS_REGION}"
aws cloudformation deploy \
  --region "${AWS_REGION}" \
  --stack-name "${STACK_NAME}" \
  --template-file "${HERE}/cloudformation.yaml" \
  --capabilities CAPABILITY_IAM CAPABILITY_NAMED_IAM \
  --parameter-overrides \
    ProjectName="${PROJECT_NAME}" \
    VpcId="${VPC_ID}" \
    SubnetIds="${SUBNET_IDS}" \
    AssignPublicIp="${ASSIGN_PUBLIC_IP}" \
    ScheduleExpression="${SCHEDULE}" \
    ScheduleTimezone="${TIMEZONE}" \
    Mode="${MODE}" \
    Brand="${BRAND}"

get_output() {
  aws cloudformation describe-stacks --region "${AWS_REGION}" \
    --stack-name "${STACK_NAME}" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}

ECR_URI="$(get_output EcrRepositoryUri)"
BUCKET="$(get_output OutputBucketName)"
REGISTRY="${ECR_URI%%/*}"

echo "==> [2/3] Building & pushing image to ${ECR_URI}:latest"
aws ecr get-login-password --region "${AWS_REGION}" \
  | docker login --username AWS --password-stdin "${REGISTRY}"
docker build -t "${ECR_URI}:latest" "${ROOT}"
docker push "${ECR_URI}:latest"

echo "==> [3/3] Done."
echo "    Output bucket : s3://${BUCKET}/news-job-ads/"
echo "    Schedule      : ${SCHEDULE} (${TIMEZONE})"
echo
echo "Trigger a run now (replace <SUBNET_ID> with one of your subnets):"
get_output RunOnceCommand
