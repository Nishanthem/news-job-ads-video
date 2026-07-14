#!/usr/bin/env bash
#
# Container entrypoint for the scheduled (serverless) deployment.
#
# It runs the news-job-ads-video pipeline once, then uploads the finished
# video (and, when scraping, the proof-of-source evidence archive) to S3.
# Designed to run as an ECS Fargate scheduled task: it does its work and exits,
# so you pay only for the minutes it runs instead of a 24/7 EC2 instance.
#
# Configuration (environment variables):
#   OUTPUT_S3_BUCKET   (required) S3 bucket to upload results to
#   OUTPUT_S3_PREFIX   key prefix inside the bucket           (default: "news-job-ads")
#   MODE               "scrape" (real sites) or "sample"      (default: "scrape")
#   SOURCES_FILE       sources.json path for scrape mode      (default: /app/sources.json)
#   BRAND              title shown on slides                  (default: app default)
#   LIMIT              cap number of jobs (empty = no cap)     (default: unset)
#   UPLOAD_EVIDENCE    "true"/"false" upload evidence archive  (default: "true")
#   UPLOAD_LATEST      "true"/"false" also write latest.mp4    (default: "true")
#   EXTRA_ARGS         extra flags passed straight to the jar  (default: unset)
set -euo pipefail

: "${OUTPUT_S3_BUCKET:?OUTPUT_S3_BUCKET must be set}"
OUTPUT_S3_PREFIX="${OUTPUT_S3_PREFIX:-news-job-ads}"
MODE="${MODE:-scrape}"
SOURCES_FILE="${SOURCES_FILE:-/app/sources.json}"
UPLOAD_EVIDENCE="${UPLOAD_EVIDENCE:-true}"
UPLOAD_LATEST="${UPLOAD_LATEST:-true}"

WORK_DIR="$(mktemp -d)"
OUT_VIDEO="${WORK_DIR}/jobs.mp4"
OUT_THUMB="${WORK_DIR}/jobs.thumb.png"
EVIDENCE_DIR="${WORK_DIR}/evidence"
STAMP="$(date -u +%Y-%m-%d_%H%M%S)"
DEST_BASE="s3://${OUTPUT_S3_BUCKET}/${OUTPUT_S3_PREFIX}"

echo "[deploy] mode=${MODE} stamp=${STAMP} dest=${DEST_BASE}"

ARGS=(-jar /app/news-job-ads-video.jar --out "${OUT_VIDEO}" --evidence "${EVIDENCE_DIR}")
if [[ "${MODE}" == "sample" ]]; then
  ARGS+=(--sample)
else
  ARGS+=(--sources "${SOURCES_FILE}")
fi
[[ -n "${BRAND:-}" ]] && ARGS+=(--brand "${BRAND}")
[[ -n "${LIMIT:-}" ]] && ARGS+=(--limit "${LIMIT}")
# shellcheck disable=SC2206
[[ -n "${EXTRA_ARGS:-}" ]] && ARGS+=(${EXTRA_ARGS})

echo "[deploy] running: java ${ARGS[*]}"
java "${ARGS[@]}"

if [[ ! -f "${OUT_VIDEO}" ]]; then
  echo "[deploy] ERROR: no video was produced at ${OUT_VIDEO}" >&2
  exit 1
fi

echo "[deploy] uploading video -> ${DEST_BASE}/videos/jobs-${STAMP}.mp4"
aws s3 cp "${OUT_VIDEO}" "${DEST_BASE}/videos/jobs-${STAMP}.mp4"
if [[ "${UPLOAD_LATEST}" == "true" ]]; then
  aws s3 cp "${OUT_VIDEO}" "${DEST_BASE}/videos/latest.mp4"
fi

# YouTube thumbnail (generated next to the video). Uploaded alongside so a
# publishing step can attach it; missing thumbnail is non-fatal.
if [[ -f "${OUT_THUMB}" ]]; then
  echo "[deploy] uploading thumbnail -> ${DEST_BASE}/videos/jobs-${STAMP}.thumb.png"
  aws s3 cp "${OUT_THUMB}" "${DEST_BASE}/videos/jobs-${STAMP}.thumb.png"
  if [[ "${UPLOAD_LATEST}" == "true" ]]; then
    aws s3 cp "${OUT_THUMB}" "${DEST_BASE}/videos/latest.thumb.png"
  fi
fi

if [[ "${UPLOAD_EVIDENCE}" == "true" && -d "${EVIDENCE_DIR}" ]]; then
  echo "[deploy] uploading evidence -> ${DEST_BASE}/evidence/${STAMP}/"
  aws s3 cp "${EVIDENCE_DIR}" "${DEST_BASE}/evidence/${STAMP}/" --recursive
fi

echo "[deploy] DONE."
