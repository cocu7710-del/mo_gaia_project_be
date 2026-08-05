#!/usr/bin/env bash
# 재배포 — git push 후 VM에서 실행: 최신 소스 pull + 재빌드 + 재기동 (DB 데이터는 유지)
set -euo pipefail
git -C "$HOME/mo_gaia_project_be" pull
cd "$HOME/mo_gaia_project_be/deploy/oracle"
sudo docker compose up -d --build
sudo docker image prune -f
