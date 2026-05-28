#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "== GET /v1/models =="
curl -sS "${BASE_URL}/v1/models" | sed -e 's/.*/&/'
echo

echo "== POST /v1/chat/completions =="
curl -sS -X POST "${BASE_URL}/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{
        "model": "phi-3-mini-4k-instruct",
        "messages": [
          {"role": "user", "content": "Write a one-sentence haiku about Java."}
        ],
        "max_tokens": 128,
        "temperature": 0.7,
        "stream": false
      }'
echo
