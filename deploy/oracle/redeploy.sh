#!/usr/bin/env bash
# 재배포 — VM에서 실행: 최신 소스 pull + 이미지 재빌드 + 재기동 (DB 데이터는 유지).
# app은 이제 VM에서 컴파일하지 않는다(Dockerfile.runtime) — 여기서 "재빌드"는 이미
# deploy/oracle/app.jar에 올라와 있는 jar를 그대로 담는 것뿐이다. 그 jar 자체를 최신
# 코드로 갱신하려면 로컬에서 local-deploy.ps1을 써야 한다 (이 스크립트를 원격에서 대신 실행해준다).
# 이 스크립트를 단독으로 돌리면(로컬 빌드·전송 없이) 코드가 안 바뀐 채로 같은 jar만 재기동된다.
set -euo pipefail
git -C "$HOME/mo_gaia_project_be" pull
cd "$HOME/mo_gaia_project_be/deploy/oracle"
sudo docker compose up -d --build
sudo docker image prune -f
sudo docker builder prune -f
