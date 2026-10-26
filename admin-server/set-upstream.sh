#!/bin/sh
# Tell the admin machine where the phone-facing server is and how to log in to its /admin interface.
# Run it yourself: ./admin-server/set-upstream.sh
# The three values (AKALI_SERVER, AKALI_ADMIN_TOKEN, AKALI_APP_TOKEN) are read from mac/.env on this Mac and sent
# over ssh stdin straight into the admin machine's environment file. They are not printed and not kept anywhere else.
set -e
cd "$(dirname "$0")"
[ -f deploy.env ] || { echo "missing admin-server/deploy.env"; exit 1; }
. ./deploy.env
ENVFILE="${AKALI_ENV_FILE:-../mac/.env}"
[ -f "$ENVFILE" ] || { echo "missing $ENVFILE"; exit 1; }
KEY="${ADMIN_KEY:-$HOME/.ssh/id_rsa}"
for k in AKALI_SERVER AKALI_ADMIN_TOKEN AKALI_APP_TOKEN; do
  grep -q "^$k=" "$ENVFILE" || { echo "$k is not in $ENVFILE"; exit 1; }
done
grep -E '^(AKALI_SERVER|AKALI_ADMIN_TOKEN|AKALI_APP_TOKEN)=' "$ENVFILE" | ssh -i "$KEY" -o BatchMode=yes "$ADMIN_USER@$ADMIN_HOST" '
  set -e
  sudo sh -c "umask 077; grep -vE \"^AKALI_(SERVER|ADMIN_TOKEN|APP_TOKEN)=\" /etc/akali-admin/env > /tmp/env.new || true; cat >> /tmp/env.new; \
    chmod --reference=/etc/akali-admin/env /tmp/env.new; chown --reference=/etc/akali-admin/env /tmp/env.new; \
    mv /tmp/env.new /etc/akali-admin/env"
  sudo systemctl restart akali-admin && sleep 1 && systemctl is-active akali-admin'
echo "done"
