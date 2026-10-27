#!/usr/bin/env bash
# First installation on a VM, as root: install.sh <mcp-jira.jar>. Review before running.
set -euo pipefail

APP_USER=mcp
APP_DIR=/opt/mcp-jira
CFG_DIR=/etc/mcp-jira
DEPLOY_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR_SRC="${1:?usage: install.sh <path-to-mcp-jira.jar>}"

id -u "$APP_USER" &>/dev/null || useradd --system --no-create-home --shell /usr/sbin/nologin "$APP_USER"

install -d -o root -g "$APP_USER" -m 0750 "$APP_DIR" "$CFG_DIR" "$CFG_DIR/tls"
install -o root -g "$APP_USER" -m 0640 "$JAR_SRC" "$APP_DIR/mcp-jira.jar"
# The environment file holds passwords: created once from the template, never overwritten.
if [[ ! -e "$CFG_DIR/mcp-jira.env" ]]; then
    install -o root -g "$APP_USER" -m 0640 "$DEPLOY_DIR/systemd/mcp-jira.env.template" "$CFG_DIR/mcp-jira.env"
fi
install -o root -g root -m 0644 "$DEPLOY_DIR/systemd/mcp-jira.service" /etc/systemd/system/mcp-jira.service
systemctl daemon-reload
echo "Installed. Next: fill $CFG_DIR/mcp-jira.env, put keystore.p12 and truststore.p12 into $CFG_DIR/tls,"
echo "then: systemctl enable --now mcp-jira"
