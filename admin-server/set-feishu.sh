#!/bin/sh
# Give the admin machine read access to the Feishu vocabulary table (used to make each lesson's vocab.json).
# Run it yourself: ./admin-server/set-feishu.sh
# The Feishu app id and secret come from the lark-mcp entry in ~/.claude.json, the table ids from mac/.env.
# They go over ssh stdin straight into the admin machine's environment file; nothing is printed.
set -e
cd "$(dirname "$0")"
[ -f deploy.env ] || { echo "missing admin-server/deploy.env"; exit 1; }
. ./deploy.env
KEY="${ADMIN_KEY:-$HOME/.ssh/id_rsa}"
python3 - <<'PY' | ssh -i "$KEY" -o BatchMode=yes "$ADMIN_USER@$ADMIN_HOST" '
  set -e
  sudo sh -c "umask 077; grep -vE \"^FEISHU_\" /etc/akali-admin/env > /tmp/env.new || true; cat >> /tmp/env.new; \
    chmod --reference=/etc/akali-admin/env /tmp/env.new; chown --reference=/etc/akali-admin/env /tmp/env.new; \
    mv /tmp/env.new /etc/akali-admin/env"
  sudo systemctl restart akali-admin && sleep 1 && systemctl is-active akali-admin'
import json, re, sys
from pathlib import Path
stack, env = [json.load(open(Path.home() / ".claude.json"))], None
while stack:
    o = stack.pop()
    if isinstance(o, dict):
        if "lark-mcp" in o:
            env = o["lark-mcp"]["env"]
            break
        stack.extend(o.values())
    elif isinstance(o, list):
        stack.extend(o)
if not env:
    sys.exit("no lark-mcp entry in ~/.claude.json")
src = Path("../mac/.env").read_text(encoding="utf-8")
app = re.search(r'^FEISHU_BITABLE_TOKEN="?([^"\n]+)"?', src, re.M)
table = re.search(r'^FEISHU_TABLE_ID="?([^"\n]+)"?', src, re.M)
if not (app and table):
    sys.exit("FEISHU_BITABLE_TOKEN / FEISHU_TABLE_ID not found in mac/.env")
print(f"FEISHU_APP_ID={env['APP_ID']}")
print(f"FEISHU_APP_SECRET={env['APP_SECRET']}")
print(f"FEISHU_BITABLE_TOKEN={app.group(1)}")
print(f"FEISHU_TABLE_ID={table.group(1)}")
PY
echo "done"
