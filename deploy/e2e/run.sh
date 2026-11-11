#!/usr/bin/env bash
set -euo pipefail
# 本 runner 只操作自己新建的 Compose project；不读取开发 .env.local。
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
exec python3 "$ROOT/deploy/e2e/run.py" "$@"
