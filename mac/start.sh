#!/bin/sh
# Start akali studio and open it in the browser.
cd "$(dirname "$0")"
[ -x .venv/bin/python ] || { echo "先创建环境：python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"; exit 1; }
(sleep 2 && open "http://127.0.0.1:18765") &
exec .venv/bin/python studio.py
