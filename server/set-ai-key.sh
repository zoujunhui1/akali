#!/bin/sh
# Put the DeepSeek key on the cloud machine and restart the backend. Run it yourself in a terminal:
#   sh server/set-ai-key.sh
# It asks for the key without showing it (or reads DEEPSEEK_KEY from server/deploy.env if you set it there).
# The key goes straight into the server's private settings file; it is never printed or kept in git.
set -e
cd "$(dirname "$0")"
[ -f deploy.env ] || { echo "missing server/deploy.env (copy deploy.env.example)"; exit 1; }
. ./deploy.env
if [ -z "$DEEPSEEK_KEY" ]; then
  printf "DeepSeek key (typing is hidden): "
  stty -echo
  read -r DEEPSEEK_KEY
  stty echo
  echo
fi
[ -n "$DEEPSEEK_KEY" ] || { echo "no key given"; exit 1; }
# the key travels over ssh stdin, so it never shows up in a command line or process list
printf '%s\n' "$DEEPSEEK_KEY" | ssh -i "${DEPLOY_KEY:-$HOME/.ssh/id_rsa}" -o BatchMode=yes "$DEPLOY_USER@$DEPLOY_HOST" '
  read -r KEY
  sudo sed -i "/^AKALI_AI_KEY=/d" /etc/akali/env
  printf "AKALI_AI_KEY=%s\n" "$KEY" | sudo tee -a /etc/akali/env >/dev/null
  sudo systemctl restart akali && sleep 1 && systemctl is-active akali'
echo "done: the AI key is set (model deepseek-flash; change it with AKALI_AI_MODEL in /etc/akali/env)"
