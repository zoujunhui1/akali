#!/bin/sh
# Copy the Python tools to the admin machine and (re)build their virtualenv. Run: ./tools/deploy.sh
# The machine's address comes from admin-server/deploy.env (not in git).
set -e
cd "$(dirname "$0")"
[ -f ../admin-server/deploy.env ] || { echo "missing admin-server/deploy.env"; exit 1; }
. ../admin-server/deploy.env
KEY="${ADMIN_KEY:-$HOME/.ssh/id_rsa}"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
tar -czf "$TMP/tools.tgz" --exclude __pycache__ --exclude tests --exclude deploy.sh ./*.py requirements.txt
scp -q -i "$KEY" -o BatchMode=yes "$TMP/tools.tgz" "$ADMIN_USER@$ADMIN_HOST:/tmp/"
ssh -i "$KEY" -o BatchMode=yes "$ADMIN_USER@$ADMIN_HOST" '
  set -e
  sudo mkdir -p /opt/akali-tools &&
  sudo tar -C /opt/akali-tools -xzf /tmp/tools.tgz && rm /tmp/tools.tgz &&
  [ -x /opt/akali-tools/venv/bin/python ] || sudo python3 -m venv /opt/akali-tools/venv &&
  sudo /opt/akali-tools/venv/bin/pip install -q -r /opt/akali-tools/requirements.txt &&
  sudo chown -R root:root /opt/akali-tools && sudo chmod -R a+rX /opt/akali-tools &&
  /opt/akali-tools/venv/bin/python -c "import trafilatura; print(\"tools ready\")"'
