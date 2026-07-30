#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${RSVQA_WEB_URL:-http://localhost:8088}"
COOKIE_JAR="$(mktemp /tmp/rsvqa-demo-reset.XXXXXX)"

cleanup() {
  rm -f "$COOKIE_JAR"
}
trap cleanup EXIT

csrf_token() {
  /usr/bin/curl -fsS \
    -b "$COOKIE_JAR" \
    -c "$COOKIE_JAR" \
    "$BASE_URL/api/v1/auth/csrf" >/dev/null
  awk '$6 == "XSRF-TOKEN" { token = $7 } END { print token }' "$COOKIE_JAR"
}

TOKEN="$(csrf_token)"
if [[ -z "$TOKEN" ]]; then
  echo "Unable to obtain the CSRF cookie." >&2
  exit 1
fi

/usr/bin/curl -fsS \
  -b "$COOKIE_JAR" \
  -c "$COOKIE_JAR" \
  -H "X-XSRF-TOKEN: $TOKEN" \
  -X POST \
  "$BASE_URL/api/v1/auth/demo" >/dev/null

TOKEN="$(csrf_token)"
/usr/bin/curl -fsS \
  -b "$COOKIE_JAR" \
  -c "$COOKIE_JAR" \
  -H "Content-Type: application/json" \
  -H "X-XSRF-TOKEN: $TOKEN" \
  -X POST \
  --data '{"confirmation":"RESET_LOCAL_DEMO"}' \
  "$BASE_URL/api/v1/demo-environment/reset"
echo
