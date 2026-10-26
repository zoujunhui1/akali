#!/bin/sh
# Build the backend for the cloud machine and restart it. Run from anywhere: ./server/deploy.sh
# The machine's address and ssh user come from server/deploy.env (not in git, see deploy.env.example).
set -e
cd "$(dirname "$0")"
[ -f deploy.env ] || { echo "missing server/deploy.env (copy deploy.env.example)"; exit 1; }
. ./deploy.env
KEY="${DEPLOY_KEY:-$HOME/.ssh/id_rsa}"
ARCH="${DEPLOY_ARCH:-amd64}"
CGO_ENABLED=0 GOOS=linux GOARCH="$ARCH" go build -ldflags="-s -w" -o /tmp/akali-server-linux ./cmd/akali-server
scp -i "$KEY" -o BatchMode=yes /tmp/akali-server-linux "$DEPLOY_USER@$DEPLOY_HOST:/tmp/akali-server"
ssh -i "$KEY" -o BatchMode=yes "$DEPLOY_USER@$DEPLOY_HOST" \
  'sudo install -m 755 /tmp/akali-server /usr/local/bin/akali-server && rm /tmp/akali-server && sudo systemctl restart akali && sleep 1 && systemctl is-active akali && curl -s http://127.0.0.1:8080/healthz'
echo
