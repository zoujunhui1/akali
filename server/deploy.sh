#!/bin/sh
# Build the backend for the cloud machine and restart it. Run from anywhere: ./server/deploy.sh
set -e
cd "$(dirname "$0")"
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o /tmp/akali-server-linux ./cmd/akali-server
scp -i ~/.ssh/id_rsa -o BatchMode=yes /tmp/akali-server-linux SSH_USER@SERVER_IP:/tmp/akali-server
ssh -i ~/.ssh/id_rsa -o BatchMode=yes SSH_USER@SERVER_IP \
  'sudo install -m 755 /tmp/akali-server /usr/local/bin/akali-server && rm /tmp/akali-server && sudo systemctl restart akali && sleep 1 && systemctl is-active akali && curl -s http://127.0.0.1:8080/healthz'
echo
