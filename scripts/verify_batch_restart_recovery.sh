#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATEWAY_DIR="$ROOT/apps/gateway"
MODEL_CONTAINER="rs-vqa-model-restart-probe"
POSTGRES_CONTAINER="${RSVQA_POSTGRES_CONTAINER:-rs-vqa-postgres-1}"
MODEL_PORT="${RSVQA_RESTART_MODEL_PORT:-18000}"
GATEWAY_PORT="${RSVQA_RESTART_GATEWAY_PORT:-18081}"
PROBE_ROOT="$(mktemp -d /tmp/rsvqa-restart-probe.XXXXXX)"
GATEWAY_PID=""
USER_ID=""
JOB_ID=""

stop_probe_model() {
  if docker container inspect "$MODEL_CONTAINER" >/dev/null 2>&1; then
    docker stop "$MODEL_CONTAINER" >/dev/null 2>&1 || true
    docker container rm "$MODEL_CONTAINER" >/dev/null 2>&1 || true
  fi
}

cleanup() {
  if [[ -n "$GATEWAY_PID" ]]; then
    kill "$GATEWAY_PID" >/dev/null 2>&1 || true
    wait "$GATEWAY_PID" >/dev/null 2>&1 || true
  fi
  stop_probe_model
  if [[ -n "$JOB_ID" ]]; then
    docker exec "$POSTGRES_CONTAINER" psql -U rsvqa -d rsvqa -v ON_ERROR_STOP=1 -q -c \
      "DELETE FROM batch_item WHERE batch_job_id = '$JOB_ID';
       DELETE FROM batch_job WHERE id = '$JOB_ID';
       DELETE FROM app_user WHERE id = '$USER_ID';" >/dev/null 2>&1 || true
  fi
  find "$PROBE_ROOT" -type f -delete 2>/dev/null || true
  find "$PROBE_ROOT" -depth -type d -empty -delete 2>/dev/null || true
}
trap cleanup EXIT

wait_for_url() {
  local url="$1"
  local attempts="${2:-20}"
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    if /usr/bin/curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

start_gateway() {
  env \
    SERVER_PORT="$GATEWAY_PORT" \
    SPRING_PROFILES_ACTIVE=demo \
    SPRING_AI_MCP_SERVER_ENABLED=false \
    RSVQA_MCP_ENABLED=false \
    RSVQA_DEMO_AUTH=false \
    RSVQA_DATABASE_URL=jdbc:postgresql://127.0.0.1:15432/rsvqa \
    RSVQA_DATABASE_USER=rsvqa \
    RSVQA_DATABASE_PASSWORD=rsvqa_dev_only \
    RSVQA_REDIS_HOST=127.0.0.1 \
    RSVQA_REDIS_PORT=16379 \
    RSVQA_MODEL_SERVICE_URL="http://127.0.0.1:$MODEL_PORT" \
    RSVQA_STORAGE_ROOT="$PROBE_ROOT" \
    RSVQA_BATCH_RECOVERY_ENABLED=true \
    RSVQA_BATCH_LEASE_DURATION=PT5S \
    RSVQA_BATCH_RECOVERY_INTERVAL=PT1S \
    java -jar "$GATEWAY_DIR/target/rs-vqa-gateway-0.3.0.jar" \
      >"$PROBE_ROOT/gateway.log" 2>&1 &
  GATEWAY_PID=$!
  if ! wait_for_url "http://127.0.0.1:$GATEWAY_PORT/actuator/health/readiness" 20; then
    tail -80 "$PROBE_ROOT/gateway.log"
    return 1
  fi
}

cd "$GATEWAY_DIR"
mvn -q -DskipTests package

stop_probe_model
docker run -d \
  --name "$MODEL_CONTAINER" \
  -p "127.0.0.1:$MODEL_PORT:8000" \
  -e RSVQA_MODEL_MODE=mock \
  -e RSVQA_MOCK_LATENCY_MS=5000 \
  rs-vqa-model-service:latest >/dev/null
wait_for_url "http://127.0.0.1:$MODEL_PORT/ready" 10

mkdir -p "$PROBE_ROOT/restart"
cp "$ROOT/data/test-images/single/phoenix_desert_urban.jpg" "$PROBE_ROOT/restart/probe.jpg"
IMAGE_SHA256="$(/usr/bin/shasum -a 256 "$PROBE_ROOT/restart/probe.jpg" | awk '{print $1}')"
IMAGE_BYTES="$(wc -c <"$PROBE_ROOT/restart/probe.jpg" | tr -d ' ')"
USER_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
JOB_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
ITEM_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
USERNAME="restart-$USER_ID"

docker exec "$POSTGRES_CONTAINER" psql -U rsvqa -d rsvqa -v ON_ERROR_STOP=1 -q -c \
  "INSERT INTO app_user(id, username, display_name, role, demo, created_at, updated_at)
     VALUES ('$USER_ID', '$USERNAME', 'Restart probe', 'USER', FALSE, NOW(), NOW());
   INSERT INTO batch_job(
       id, user_id, status, total_items, completed_items, failed_items,
       cancel_requested, archived, created_at, updated_at
   ) VALUES ('$JOB_ID', '$USER_ID', 'QUEUED', 1, 0, 0, FALSE, FALSE, NOW(), NOW());
   INSERT INTO batch_item(
       id, batch_job_id, question, status, attempt, storage_key, original_name,
       sha256, mime_type, size_bytes, width_px, height_px, created_at, updated_at
   ) VALUES (
       '$ITEM_ID', '$JOB_ID', 'Are there any roads?', 'QUEUED', 0,
       'restart/probe.jpg', 'probe.jpg', '$IMAGE_SHA256', 'image/jpeg',
       $IMAGE_BYTES, 256, 256, NOW(), NOW()
   );"

start_gateway
CLAIMED=false
for ((attempt = 1; attempt <= 25; attempt++)); do
  STATE="$(docker exec "$POSTGRES_CONTAINER" psql -U rsvqa -d rsvqa -Atqc \
    "SELECT status || ':' || attempt FROM batch_item WHERE id = '$ITEM_ID'")"
  if [[ "$STATE" == "RUNNING:1" ]]; then
    CLAIMED=true
    break
  fi
  sleep 0.2
done
if [[ "$CLAIMED" != "true" ]]; then
  echo "Initial claim failed: $STATE"
  tail -80 "$PROBE_ROOT/gateway.log"
  exit 1
fi

kill -9 "$GATEWAY_PID"
wait "$GATEWAY_PID" >/dev/null 2>&1 || true
GATEWAY_PID=""
AFTER_STOP="$(docker exec "$POSTGRES_CONTAINER" psql -U rsvqa -d rsvqa -Atqc \
  "SELECT status || ':' || attempt || ':' || (lease_owner IS NOT NULL)
   FROM batch_item WHERE id = '$ITEM_ID'")"

start_gateway
COMPLETED=false
for ((attempt = 1; attempt <= 30; attempt++)); do
  FINAL_STATE="$(docker exec "$POSTGRES_CONTAINER" psql -U rsvqa -d rsvqa -Atqc \
    "SELECT item.status || ':' || item.attempt || ':' || job.status || ':' ||
            job.completed_items || ':' || job.failed_items || ':' ||
            (item.lease_owner IS NULL)
     FROM batch_item item
     JOIN batch_job job ON job.id = item.batch_job_id
     WHERE item.id = '$ITEM_ID'")"
  if [[ "$FINAL_STATE" == "COMPLETED:2:COMPLETED:1:0:true" ]]; then
    COMPLETED=true
    break
  fi
  sleep 1
done

echo "after_forced_stop=$AFTER_STOP"
echo "after_restart=$FINAL_STATE"
if [[ "$COMPLETED" != "true" ]]; then
  tail -120 "$PROBE_ROOT/gateway.log"
  exit 1
fi
