#!/bin/sh
# Install on a machine: ./install.sh <ssh-key> <user@host>
# Remove with: sudo systemctl disable --now keepalive && sudo rm -r /opt/keepalive /etc/systemd/system/keepalive.service
set -e
cd "$(dirname "$0")"
scp -i "$1" -o BatchMode=yes keepalive.py keepalive.service "$2:/tmp/"
ssh -i "$1" -o BatchMode=yes "$2" 'sudo mkdir -p /opt/keepalive && sudo install -m 755 /tmp/keepalive.py /opt/keepalive/keepalive.py && sudo install -m 644 /tmp/keepalive.service /etc/systemd/system/keepalive.service && rm /tmp/keepalive.py /tmp/keepalive.service && sudo systemctl daemon-reload && sudo systemctl enable --now keepalive'
