#!/usr/bin/env bash

set -euo pipefail

# Colors and icons
RED="\033[31m"; GRN="\033[32m"; YEL="\033[33m"; BLU="\033[34m"; CYN="\033[36m"; RST="\033[0m"
ICON_RUN="🚀"; ICON_CFG="⚙️"; ICON_OK="✅"; ICON_WARN="⚠️"; ICON_ERR="❌"; ICON_INFO="ℹ️"; ICON_FILE="🗂️"; ICON_DOCK="🐳"

print_help() {
  cat <<'EOF'
HDFS Watcher – Local Runner

Usage:
  run-local.sh [options]

Modes:
  -m, --mode <standalone|cloud>     Run mode (default: standalone)
  -p, --pseudoop <true|false>       Use local directory instead of HDFS (default: false)

Common options:
  -r, --port <PORT>                 Server port (default: 8080)
  -J, --java-opts "..."              Extra JAVA_OPTS
  --no-build                        Skip mvn build step

Pseudoop options (standalone only):
  -l, --local-path <PATH>           Local storage path (default: /tmp/hdfsWatcher)

Streaming options (cloud mode):
  -q, --output-dest <DEST>          spring.cloud.stream output destination (e.g., hdfswatcher-textproc)

Monitoring options:
  -M, --monitor                     Enable RabbitMQ monitoring emitter
  -Q, --monitor-queue <NAME>        Monitoring queue name (default: pipeline.metrics)
  -E, --emit-interval <SECS>        Monitoring emit interval seconds (default: 10)

Examples:
  # Pseudoop (local dir), standalone
  ./run-local.sh -p true -l /tmp/hdfsWatcher -r 8081

  # HDFS, standalone (uses application-standalone.properties)
  ./run-local.sh -m standalone -p false

  # Cloud profile with stream destination and monitoring enabled
  ./run-local.sh -m cloud -p false -q hdfswatcher-textproc -M -Q pipeline.metrics -E 10

EOF
}

# Defaults
MODE="standalone"
PSEUDOOP="false"
PORT="8080"
LOCAL_PATH="/tmp/hdfsWatcher"
OUTPUT_DEST=""
MONITOR_ENABLED="false"
MONITOR_QUEUE="pipeline.metrics"
EMIT_INTERVAL="10"
JAVA_OPTS_EXTRA=""
DO_BUILD="true"

# Parse args
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) print_help; exit 0 ;;
    -m|--mode) MODE="${2:-}"; shift 2 ;;
    -p|--pseudoop) PSEUDOOP="${2:-}"; shift 2 ;;
    -r|--port) PORT="${2:-}"; shift 2 ;;
    -l|--local-path) LOCAL_PATH="${2:-}"; shift 2 ;;
    -q|--output-dest) OUTPUT_DEST="${2:-}"; shift 2 ;;
    -M|--monitor) MONITOR_ENABLED="true"; shift 1 ;;
    -Q|--monitor-queue) MONITOR_QUEUE="${2:-}"; shift 2 ;;
    -E|--emit-interval) EMIT_INTERVAL="${2:-}"; shift 2 ;;
    -J|--java-opts) JAVA_OPTS_EXTRA="${2:-}"; shift 2 ;;
    --no-build) DO_BUILD="false"; shift 1 ;;
    *) echo -e "${YEL}${ICON_WARN} Unknown option:${RST} $1"; print_help; exit 1 ;;
  esac
done

echo -e "${BLU}${ICON_INFO} Mode:${RST} ${MODE}  ${BLU}Pseudoop:${RST} ${PSEUDOOP}  ${BLU}Port:${RST} ${PORT}"

# Validate inputs
if [[ "${MODE}" != "standalone" && "${MODE}" != "cloud" ]]; then
  echo -e "${RED}${ICON_ERR} Invalid --mode:${RST} ${MODE}"; exit 1
fi

if [[ "${PSEUDOOP}" != "true" && "${PSEUDOOP}" != "false" ]]; then
  echo -e "${RED}${ICON_ERR} Invalid --pseudoop:${RST} ${PSEUDOOP}"; exit 1
fi

# Start RabbitMQ docker for local testing (both modes)
start_rabbit() {
  if ! docker ps --format '{{.Names}}' | grep -q '^hdfswatcher-rabbit$'; then
    if docker ps -a --format '{{.Names}}' | grep -q '^hdfswatcher-rabbit$'; then
      echo -e "${CYN}${ICON_DOCK} Starting RabbitMQ container hdfswatcher-rabbit...${RST}"
      docker start hdfswatcher-rabbit >/dev/null
    else
      echo -e "${CYN}${ICON_DOCK} Running RabbitMQ (3-management) on 5672/15672...${RST}"
      docker run -d --name hdfswatcher-rabbit -p 5672:5672 -p 15672:15672 rabbitmq:3-management >/dev/null
    fi
  fi
}

start_rabbit || true

if [[ "${PSEUDOOP}" == "true" ]]; then
  mkdir -p "${LOCAL_PATH}"
  echo -e "${GRN}${ICON_FILE} Local storage:${RST} ${LOCAL_PATH}"
fi

# Build
if [[ "${DO_BUILD}" == "true" ]]; then
  echo -e "${CYN}${ICON_RUN} Building with Maven...${RST}"
  ./mvnw -q -DskipTests package || { echo -e "${RED}${ICON_ERR} Build failed${RST}"; exit 1; }
  echo -e "${GRN}${ICON_OK} Build complete${RST}"
fi

# Find jar
JAR=$(ls -1 target/hdfsWatcher-*.jar 2>/dev/null | head -n 1 || true)
if [[ -z "${JAR}" ]]; then
  echo -e "${RED}${ICON_ERR} Could not find built JAR in target/hdfsWatcher-*.jar${RST}"; exit 1
fi

# Compose args
ARGS=(
  "--hdfswatcher.mode=${MODE}"
  "--server.port=${PORT}"
  "--spring.rabbitmq.host=localhost"
)

if [[ "${MODE}" == "cloud" ]]; then
  ARGS+=("--spring.profiles.active=cloud")
fi

if [[ "${PSEUDOOP}" == "true" ]]; then
  ARGS+=(
    "--hdfswatcher.pseudoop=true"
    "--hdfswatcher.local-storage-path=${LOCAL_PATH}"
  )
else
  ARGS+=("--hdfswatcher.pseudoop=false")
  # Use application-standalone.properties for HDFS details (no flags needed)
fi

if [[ -n "${OUTPUT_DEST}" ]]; then
  ARGS+=("--spring.cloud.stream.bindings.output.destination=${OUTPUT_DEST}")
fi

if [[ "${MONITOR_ENABLED}" == "true" ]]; then
  ARGS+=(
    "--app.monitoring.rabbitmq.enabled=true"
    "--app.monitoring.rabbitmq.queue-name=${MONITOR_QUEUE}"
    "--app.monitoring.emit-interval-seconds=${EMIT_INTERVAL}"
  )
fi

echo -e "${CYN}${ICON_CFG} Java opts:${RST} ${JAVA_OPTS_EXTRA:-<none>}"
echo -e "${CYN}${ICON_CFG} Args:${RST} ${ARGS[*]}"

echo -e "${GRN}${ICON_RUN} Starting hdfsWatcher (background)...${RST}"
LOGFILE="target/hdfswatcher-local.log"
nohup bash -c "JAVA_OPTS=\"${JAVA_OPTS_EXTRA}\" exec java ${JAVA_OPTS_EXTRA} -jar '${JAR}' ${ARGS[*]}" >"${LOGFILE}" 2>&1 &
APP_PID=$!
echo -e "${CYN}${ICON_INFO} PID:${RST} ${APP_PID}  ${CYN}Logs:${RST} ${LOGFILE}"

cleanup() {
  echo -e "\n${YEL}${ICON_WARN} Stopping hdfsWatcher (PID ${APP_PID})...${RST}"
  kill ${APP_PID} 2>/dev/null || true
  wait ${APP_PID} 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo -n "${ICON_INFO} Waiting for http://localhost:${PORT} to be ready"
for i in {1..30}; do
  if curl -fs "http://localhost:${PORT}/actuator/health" >/dev/null 2>&1; then
    echo -e "\r${GRN}${ICON_OK} Server is up on port ${PORT}               ${RST}"
    break
  fi
  echo -n "."
  sleep 1
done

while true; do
  echo -e "\n${BLU}${ICON_INFO} Menu:${RST}"
  echo "  1) Enable processing"
  echo "  2) Disable processing"
  echo "  3) Toggle processing"
  echo "  4) Show status"
  echo "  5) Show files"
  echo "  q) Quit"
  read -r -p "Select: " choice
  case "$choice" in
    1)
      curl -s -X POST "http://localhost:${PORT}/api/processing/start" || true; echo ;;
    2)
      curl -s -X POST "http://localhost:${PORT}/api/processing/stop" || true; echo ;;
    3)
      curl -s -X POST "http://localhost:${PORT}/api/processing/toggle" || true; echo ;;
    4)
      curl -s "http://localhost:${PORT}/api/status" || true; echo ;;
    5)
      curl -s "http://localhost:${PORT}/api/files" || true; echo ;;
    q|Q)
      exit 0 ;;
    *)
      echo -e "${YEL}${ICON_WARN} Unknown choice${RST}" ;;
  esac
done


