#!/bin/bash
# The JRE image includes Bash; no curl or extra runtime package is required.
set -euo pipefail
exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8080}
printf 'GET /actuator/health/readiness HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
IFS= read -r status <&3
[[ "$status" == 'HTTP/1.1 200 '* || "$status" == 'HTTP/1.0 200 '* ]]
