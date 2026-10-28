#!/bin/sh
set -eu
if [ "${RENEWED_LINEAGE:-}" = "/etc/letsencrypt/live/peygir.hydrocode.ir" ]; then
    /usr/sbin/nginx -t
    /bin/systemctl reload nginx
fi
