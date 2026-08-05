#!/usr/bin/env bash
# 오라클 VM(Ubuntu) 최초 1회 설정 — Docker 설치 + 방화벽 개방 + 소스 클론 + 서비스 기동
# 사용: VM에 SSH 접속 후
#   curl -fsSL https://raw.githubusercontent.com/cocu7710-del/mo_gaia_project_be/master/deploy/oracle/setup.sh | bash
set -euo pipefail

# 1. Docker 설치
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | sudo sh
fi

# 2. 인스턴스 방화벽 80 포트 개방 — OCI Ubuntu 이미지는 iptables 기본 REJECT 규칙이 있음
if ! sudo iptables -C INPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null; then
  sudo iptables -I INPUT 5 -p tcp --dport 80 -j ACCEPT
fi
sudo netfilter-persistent save 2>/dev/null || true

# 3. 소스 클론 (이미 있으면 갱신)
if [ ! -d "$HOME/mo_gaia_project_be" ]; then
  git clone https://github.com/cocu7710-del/mo_gaia_project_be.git "$HOME/mo_gaia_project_be"
else
  git -C "$HOME/mo_gaia_project_be" pull
fi

# 4. 빌드 + 기동 (부팅 시 자동 재시작: restart: unless-stopped)
cd "$HOME/mo_gaia_project_be/deploy/oracle"
sudo docker compose up -d --build

echo
echo "완료. 1~2분 뒤 http://<VM 공인 IP> 로 접속하세요."
echo "상태 확인: sudo docker compose -f ~/mo_gaia_project_be/deploy/oracle/docker-compose.yml ps"
