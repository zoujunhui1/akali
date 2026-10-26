#!/bin/sh
# Build the admin backend and the web page, put them on the admin machine, and restart. Run from anywhere: ./admin-server/deploy.sh
# The machine's address, ssh user and public hostname come from admin-server/deploy.env (not in git, see deploy.env.example).
set -e
cd "$(dirname "$0")"
[ -f deploy.env ] || { echo "missing admin-server/deploy.env (copy deploy.env.example)"; exit 1; }
. ./deploy.env
KEY="${ADMIN_KEY:-$HOME/.ssh/id_rsa}"
ARCH="${ADMIN_ARCH:-arm64}"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

CGO_ENABLED=0 GOOS=linux GOARCH="$ARCH" go build -ldflags="-s -w" -o "$TMP/akali-admin" ./cmd/akali-admin
(cd ../admin-web && npm run build >/dev/null)
tar -C ../admin-web/dist -czf "$TMP/web.tgz" .
printf '%s {\n\thandle /api/* {\n\t\treverse_proxy 127.0.0.1:9090\n\t}\n\thandle /healthz {\n\t\treverse_proxy 127.0.0.1:9090\n\t}\n\thandle {\n\t\troot * /var/www/akali-admin\n\t\ttry_files {path} /index.html\n\t\tfile_server\n\t}\n}\n' "$ADMIN_PUBLIC_HOST" > "$TMP/Caddyfile"

scp -q -i "$KEY" -o BatchMode=yes "$TMP/akali-admin" "$TMP/web.tgz" "$TMP/Caddyfile" akali-admin.service "$ADMIN_USER@$ADMIN_HOST:/tmp/"
ssh -i "$KEY" -o BatchMode=yes "$ADMIN_USER@$ADMIN_HOST" '
  sudo install -m 755 /tmp/akali-admin /usr/local/bin/akali-admin &&
  sudo install -m 644 /tmp/akali-admin.service /etc/systemd/system/akali-admin.service &&
  sudo rm -rf /var/www/akali-admin/* && sudo tar -C /var/www/akali-admin -xzf /tmp/web.tgz &&
  sudo install -m 644 /tmp/Caddyfile /etc/caddy/Caddyfile &&
  rm /tmp/akali-admin /tmp/akali-admin.service /tmp/web.tgz /tmp/Caddyfile &&
  sudo systemctl daemon-reload && sudo systemctl enable akali-admin >/dev/null 2>&1 &&
  sudo systemctl restart akali-admin && sudo systemctl reload caddy && sleep 1 &&
  systemctl is-active akali-admin && curl -s http://127.0.0.1:9090/healthz'
echo
