# Serverless scheduled deployment (ECS Fargate + EventBridge)

This replaces the always-on EC2 instance with a **daily scheduled task** that spins
up, produces the video, uploads it to S3, and shuts down. You pay only for the few
minutes it runs each day instead of 24/7 compute.

```
EventBridge Scheduler (daily cron)
        │  ecs:RunTask
        ▼
ECS Fargate task  ──►  scrape sites → render slides → ffmpeg → jobs.mp4 (+ evidence)
        │
        └──►  upload to  s3://<bucket>/news-job-ads/videos/  and  /evidence/
```

## What gets created

The CloudFormation stack (`cloudformation.yaml`) provisions:

- **ECR repository** – holds the container image.
- **S3 bucket** `news-job-ads-<account>-<region>` – output videos + evidence (90-day lifecycle).
- **ECS Fargate cluster + task definition** – Java + ffmpeg + Chromium + piper/espeak TTS.
- **EventBridge Scheduler** – runs the task daily (default 06:00 Asia/Kolkata).
- **IAM roles + security group + CloudWatch log group** – least-privilege wiring.

## Prerequisites

- AWS CLI v2 and Docker installed and logged in to an account with permission to
  create the resources above (ECR, ECS, S3, IAM, EventBridge Scheduler, CloudWatch, EC2 SG).
  > Note: the SQS-only key currently in this project is **not** enough — deploying
  > needs an admin/deployer principal. See "Required permissions" below.
- A VPC and subnets. Public subnets are simplest (`AssignPublicIp=ENABLED`); private
  subnets work if they have a NAT gateway.

## Deploy

```bash
export AWS_REGION=us-east-1               # your region
export VPC_ID=vpc-xxxxxxxx
export SUBNET_IDS=subnet-aaaa,subnet-bbbb # public subnets
./deploy/deploy.sh
```

`deploy.sh` deploys the stack, then builds and pushes the image to the stack's ECR
repo. Re-run it any time to ship new code.

### Run once immediately (don't wait for the schedule)

The stack output `RunOnceCommand` prints a ready-to-use `aws ecs run-task` command.
Fill in a subnet id and run it. Watch logs in CloudWatch group `/ecs/news-job-ads-video`.

## Configuration

Change behaviour via CloudFormation parameters (pass through `deploy.sh` env vars or
`--parameter-overrides`):

| Parameter | Default | Meaning |
|-----------|---------|---------|
| `ScheduleExpression` | `cron(0 6 * * ? *)` | When to run (EventBridge Scheduler cron) |
| `ScheduleTimezone` | `Asia/Kolkata` | Timezone for the schedule |
| `ScheduleEnabled` | `ENABLED` | `DISABLED` to pause automatic runs |
| `Mode` | `scrape` | `scrape` real sites or `sample` |
| `Brand` | `RightRoads Job News` | Title on the slides |
| `JobLimit` | (none) | Cap number of jobs |
| `TaskCpu` / `TaskMemory` | `2048` / `4096` | Fargate size (2 vCPU / 4 GB) |
| `AssignPublicIp` | `ENABLED` | `DISABLED` for private subnets + NAT |

Runtime knobs are also available as container env vars (see
[`run-and-upload.sh`](run-and-upload.sh)): `OUTPUT_S3_PREFIX`, `UPLOAD_EVIDENCE`,
`UPLOAD_LATEST`, `EXTRA_ARGS`, etc.

## Cost

A Fargate 2 vCPU / 4 GB task costs roughly **$0.10/hour**. A daily run of a few
minutes is a few **cents per month** — plus negligible S3 storage — versus a 24/7
EC2 instance billing every hour of the day.

## Required permissions (deployer)

The principal running `deploy.sh` needs to create/manage: `cloudformation:*` (on this
stack), `ecr:*`, `ecs:*`, `s3:*` (on the created bucket), `iam:CreateRole`/`PassRole`,
`scheduler:*`, `logs:*`, `ec2:*SecurityGroup*`/`Describe*`. Use an admin or a scoped
deployer role — not the SQS worker key.

## Tear down

```bash
# empty the bucket first (CloudFormation won't delete a non-empty bucket)
aws s3 rm "s3://$(aws cloudformation describe-stacks --stack-name news-job-ads-video \
  --query "Stacks[0].Outputs[?OutputKey=='OutputBucketName'].OutputValue" --output text)" --recursive
aws cloudformation delete-stack --stack-name news-job-ads-video
```
