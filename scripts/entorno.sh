#!/usr/bin/env bash
set -euo pipefail

PROYECTO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROYECTO_DIR"
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
fi

command -v node >/dev/null || { echo 'Se necesita Node.js 24.15+ o 26+.' >&2; exit 1; }
command -v pnpm >/dev/null || { echo 'Se necesita pnpm 11.25.0.' >&2; exit 1; }
command -v docker >/dev/null || { echo 'Se necesita Docker con Compose.' >&2; exit 1; }

mkdir -p .local
if [[ ! -f .env.local ]]; then
  (umask 077; node --input-type=module <<'JS' > .env.local
import { randomBytes } from "node:crypto";
console.log("DATABASE_PASSWORD=" + randomBytes(24).toString("base64url"));
console.log("DATABASE_PORT=55433\nSERVER_PORT=8084\nFRONTEND_PORT=5177\nKUDOS_DEMO=1");
JS
  )
fi
set -a
source .env.local
set +a

export DATABASE_USER="${DATABASE_USER:-kudos}"
export DATABASE_URL="${DATABASE_URL:-jdbc:postgresql://127.0.0.1:${DATABASE_PORT:-55433}/kudos}"
export SERVER_PORT="${SERVER_PORT:-8084}"
export FRONTEND_PORT="${FRONTEND_PORT:-5177}"
export API_TARGET="http://127.0.0.1:$SERVER_PORT"

maven() { (cd backend && ./mvnw "$@"); }
compose() { docker compose --env-file .env.local "$@"; }
