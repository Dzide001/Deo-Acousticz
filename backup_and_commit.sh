#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKUP_DIR="$ROOT_DIR/recovery/backups"
TIMESTAMP="$(date +"%Y%m%d_%H%M%S")"
ARCHIVE_NAME="droidacoustic_backup_${TIMESTAMP}.tar.gz"
ARCHIVE_PATH="$BACKUP_DIR/$ARCHIVE_NAME"
COMMIT_MSG="${1:-backup: ${TIMESTAMP}}"

mkdir -p "$BACKUP_DIR"

echo "[1/3] Creating backup..."

tar -czf "$ARCHIVE_PATH" \
  --exclude="./.git" \
  --exclude="./recovery/backups" \
  --exclude="./android/.gradle" \
  --exclude="./android/**/build" \
  -C "$ROOT_DIR" .

echo "Backup created: $ARCHIVE_PATH"

echo "[2/3] Preparing git repository..."
if ! git -C "$ROOT_DIR" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git -C "$ROOT_DIR" init >/dev/null
  echo "Initialized new git repository at $ROOT_DIR"
fi

echo "[3/3] Staging and committing changes..."
git -C "$ROOT_DIR" add -A

if git -C "$ROOT_DIR" diff --cached --quiet; then
  echo "No changes to commit."
  exit 0
fi

if git -C "$ROOT_DIR" commit -m "$COMMIT_MSG"; then
  echo "Commit created with message: $COMMIT_MSG"
else
  echo "Commit failed (likely missing git user.name/user.email)."
  echo "Set them with:"
  echo "  git config user.name \"Your Name\""
  echo "  git config user.email \"you@example.com\""
  exit 1
fi
