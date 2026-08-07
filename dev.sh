#!/bin/bash
# Brings the local stack up: postgres, backend, vite. Idempotent.
set -u
pg_isready -p 5432 >/dev/null 2>&1 || su postgres -c "/usr/lib/postgresql/*/bin/pg_ctl -D /var/tmp/ctm-pg -l /var/tmp/ctm-pg/server.log -o '-p 5432' start" >/dev/null 2>&1
until pg_isready -p 5432 >/dev/null 2>&1; do sleep 1; done
curl -sf -o /dev/null http://localhost:8080/actuator/health || {
  cd /home/user/Continuum/backend
  (SPRING_DATASOURCE_USERNAME=continuum SPRING_DATASOURCE_PASSWORD=abhay123 setsid java -jar target/continuum.jar > /tmp/backend.log 2>&1 < /dev/null &)
}
curl -sf -o /dev/null http://localhost:5199/ || {
  cd /home/user/Continuum/frontend
  (setsid npx vite --port 5199 --host 127.0.0.1 > /tmp/vite.log 2>&1 < /dev/null &)
}
