#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/entorno.sh"

compose up -d --wait db
maven package
pnpm --dir frontend install --frozen-lockfile

jar=backend/target/uco-kudos-0.1.0.jar
if [[ "${KUDOS_DEMO:-0}" == 1 ]]; then
  java -jar "$jar" --spring.main.web-application-type=none --kudos.command=seed-demo > .local/semilla.log 2>&1 || {
    echo 'Falló la carga de demostración. Revisa .local/semilla.log.' >&2
    exit 1
  }
fi

java -jar "$jar" > .local/backend.log 2>&1 &
pid=$!
limpiar() { kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; }
trap limpiar EXIT
trap 'exit 130' INT TERM

for ((intento=0; intento<60; intento++)); do
  kill -0 "$pid" 2>/dev/null || { echo 'El backend no inició. Revisa .local/backend.log.' >&2; exit 1; }
  if curl -fsS --max-time 1 "http://127.0.0.1:$SERVER_PORT/api/health" >/dev/null 2>&1 &&
    grep -q 'Started KudosApplication' .local/backend.log; then
    printf 'UCO Kudos disponible en http://127.0.0.1:%s\n' "$FRONTEND_PORT"
    pnpm --dir frontend dev
    exit
  fi
  sleep 1
done
echo 'El backend no respondió. Revisa .local/backend.log.' >&2
exit 1
