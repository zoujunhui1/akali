#!/bin/sh
# Set the admin login password. Run it yourself: ./admin-server/set-password.sh <username>
# The password is typed here (not shown), travels over ssh stdin, and only its bcrypt hash is stored.
set -e
cd "$(dirname "$0")"
[ -f deploy.env ] || { echo "missing admin-server/deploy.env"; exit 1; }
. ./deploy.env
[ -n "$1" ] || { echo "usage: $0 <username>"; exit 1; }
KEY="${ADMIN_KEY:-$HOME/.ssh/id_rsa}"
printf 'New password (at least 12 characters): '
stty -echo; read -r PW; stty echo; echo
printf 'Again: '
stty -echo; read -r PW2; stty echo; echo
[ "$PW" = "$PW2" ] || { echo "the two passwords differ"; exit 1; }
printf '%s\n' "$PW" | ssh -i "$KEY" -o BatchMode=yes "$ADMIN_USER@$ADMIN_HOST" \
  "sudo -u akali-admin sh -c 'set -a; . /etc/akali-admin/env; set +a; exec /usr/local/bin/akali-admin set-password \"\$0\"' '$1'"
