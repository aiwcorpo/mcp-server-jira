#!/usr/bin/env bash
# Runs mcp-jira on the host against mcp-platform-dev (`make up`): https://localhost:18446/mcp, the address
# the gateway's scripts/run-dev-host.sh expects, with the mcp-jira certificate (SAN mcp-jira.internal), the
# gateway's internal JWKS on https://localhost:18445, the jti store in the environment's Redis (ACL user
# mcp-jira) and the Jira mock on http://localhost:18081. Secrets are read from .secrets/dev.env at start.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
dev="${MCP_PLATFORM_DEV:-$here/../mcp-platform-dev}"
jar="$here/mcp-app/target/mcp-jira.jar"

[[ -f "$dev/.secrets/dev.env" ]] || { echo "no $dev/.secrets/dev.env: run make up in mcp-platform-dev" >&2; exit 1; }
[[ -f "$jar" ]] || { echo "no $jar: run ./mvnw verify first" >&2; exit 1; }

secret() { grep "^$1=" "$dev/.secrets/dev.env" | cut -d= -f2-; }

export MCP_PORT="${MCP_PORT:-18446}" MCP_BIND="${MCP_BIND:-127.0.0.1}"
export MCP_TLS_KEYSTORE="$dev/certs/mcp-jira/keystore.p12"
export MCP_TLS_TRUSTSTORE="$dev/certs/ca/truststore.p12"
MCP_TLS_STORE_PASSWORD="$(secret TLS_STORE_PASSWORD)"
export MCP_TLS_STORE_PASSWORD
export MCP_GATEWAY_JWKS_URI="${MCP_GATEWAY_JWKS_URI:-https://localhost:18445/.well-known/jwks.json}"
export MCP_REDIS_HOST=localhost MCP_REDIS_PORT=16379 MCP_REDIS_USER=mcp-jira
MCP_REDIS_PASSWORD="$(secret REDIS_MCP_JIRA_PASSWORD)"
export MCP_REDIS_PASSWORD
export MCP_JIRA_URL="${MCP_JIRA_URL:-http://localhost:18081}"
# The mock of mcp-platform-dev answers over plain http; the server accepts that outside dev only on request.
export MCP_JIRA_ALLOW_PLAIN_HTTP="${MCP_JIRA_ALLOW_PLAIN_HTTP:-true}"
export MCP_CONNECT_URL="${MCP_CONNECT_URL:-http://localhost:18090/connections?system={system}}"

exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$jar"
