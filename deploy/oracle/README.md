# 오라클 클라우드 Always Free 배포 가이드

Render(15분 유휴 시 잠듦) 대신 오라클 Always Free VM에서 24시간 무료 구동한다.
DB(Postgres)까지 VM 안에서 Docker로 함께 돌리므로 Neon도 필요 없어진다.

## 1. 계정 생성 (최초 1회, 수동)

1. https://signup.cloud.oracle.com 에서 가입 — 본인 인증용 신용/체크카드 필요 (Always Free만 쓰면 과금 없음)
2. 홈 리전은 **South Korea Central (Seoul)** 권장 — 이후 변경 불가
3. 가입 완료 후 콘솔(https://cloud.oracle.com) 로그인

## 2. VM 생성 (최초 1회, 수동)

콘솔 → Compute → Instances → **Create instance**:

- **Image**: Ubuntu 24.04 (또는 22.04) — *aarch64* 이미지
- **Shape**: `VM.Standard.A1.Flex` (Ampere ARM) — **2 OCPU / 12GB RAM** 권장
  (Always Free 한도는 계정 전체 합산 4 OCPU / 24GB)
  - "Out of capacity" 오류가 나면 시간을 바꿔 재시도하거나 AD(가용 도메인)를 바꿔본다
- **SSH keys**: "Generate a key pair" → **개인키(.key) 반드시 다운로드** 후 저장
- 생성 후 인스턴스 상세 화면의 **Public IP** 를 메모

### 네트워크(클라우드 방화벽) 80 포트 개방

인스턴스 상세 → Virtual cloud network 링크 → Security Lists → Default Security List → **Add Ingress Rules**:

- Source CIDR: `0.0.0.0/0` / IP Protocol: `TCP` / Destination Port Range: `80`

## 3. 서버 구성 (최초 1회, 명령 한 줄)

PowerShell에서 SSH 접속 (다운로드한 개인키 사용):

```powershell
ssh -i C:\path\to\ssh-key.key ubuntu@<Public IP>
```

접속 후:

```bash
curl -fsSL https://raw.githubusercontent.com/cocu7710-del/mo_gaia_project_be/master/deploy/oracle/setup.sh | bash
```

Docker 설치 → 인스턴스 방화벽 개방 → 소스 클론 → DB+앱 빌드/기동까지 자동.
첫 빌드는 5~10분 걸릴 수 있다. 끝나면 `http://<Public IP>` 접속.

## 4. 이후 배포 흐름

1. 로컬에서 평소처럼 커밋 (FE 변경은 `npm run deploy-be`로 static 동기화 포함) → `git push`
2. VM에서: `bash ~/mo_gaia_project_be/deploy/oracle/redeploy.sh`

한 줄로 하려면 로컬 PowerShell에서:

```powershell
ssh -i C:\path\to\ssh-key.key ubuntu@<Public IP> "bash ~/mo_gaia_project_be/deploy/oracle/redeploy.sh"
```

## 운영 명령 (VM에서)

```bash
cd ~/mo_gaia_project_be/deploy/oracle
sudo docker compose ps          # 상태
sudo docker compose logs -f app # 앱 로그
sudo docker compose restart app # 앱만 재시작
sudo docker compose down        # 전체 중지 (DB 데이터는 볼륨에 유지)
```

- VM 재부팅 시 컨테이너 자동 시작 (`restart: unless-stopped`)
- DB 데이터는 `gaia-pgdata` 도커 볼륨에 저장 — 컨테이너 재빌드해도 유지
- DB 비밀번호를 바꾸려면 `deploy/oracle/.env`에 `DB_PASSWORD=...` 작성 후 `docker compose up -d` (최초 기동 전에 정하는 것을 권장)

## 사용량 확인 (Always Free 한도 초과 여부)

Always Free 한도를 넘어도 자동 청구되지 않고 그 작업만 거부된다 — 그래도 여유를 보고 싶으면:

### VM 안에서 (SSH 접속 후)

```bash
htop              # CPU·메모리 실시간 사용률 (q로 종료)
df -h              # 디스크(스토리지) 사용량
sudo docker stats  # DB·앱 컨테이너별 자원 사용량 (Ctrl+C로 종료)
```

### 오라클 콘솔에서

- ☰ → **Billing & Cost Management → Cost Analysis** — 실제 청구 금액 (Always Free만 쓰면 $0 유지)
- ☰ → **Governance & Administration → Limits, Quotas and Usage** — 인스턴스 수·OCPU·스토리지 등 Always Free 한도 대비 실사용량
- **Cost Management → Budgets** — 예산 알림 설정(선택) — 유료 전환 실수 시 이메일 경고

## HTTPS 적용 (DuckDNS 무료 도메인 + Caddy 자동 인증서)

`docker-compose.yml`에 이미 `caddy` 서비스가 구성돼 있다 — Let's Encrypt 인증서를 자동 발급·갱신한다.

### 1. 무료 도메인 발급 (사용자가 직접, 브라우저)

1. https://www.duckdns.org 접속 → 로그인(Google/GitHub 등)
2. 원하는 서브도메인 입력 후 **add domain** (예: `gaia-project` → `gaia-project.duckdns.org`)
3. **current ip**를 VM 공인 IP(`161.33.131.190`)로 입력 → **update ip**

### 2. 클라우드 방화벽 443 포트 개방

인스턴스 상세 → Virtual cloud network → Security Lists → Default Security List → **Add Ingress Rules**:
- Source CIDR: `0.0.0.0/0` / IP Protocol: `TCP` / Destination Port Range: `443`

### 3. VM에서 도메인 설정 + 재배포

```bash
cd ~/mo_gaia_project_be/deploy/oracle
echo "DOMAIN=gaia-project.duckdns.org" >> .env   # 1번에서 만든 실제 도메인으로
bash redeploy.sh
```

(기존 VM은 setup.sh가 80 포트만 열어뒀을 수 있음 — 443이 하나도 안 열려 있다면 한 번만: `sudo iptables -I INPUT 5 -p tcp --dport 443 -j ACCEPT && sudo netfilter-persistent save`)

1~2분 뒤 `https://gaia-project.duckdns.org` 로 접속 — 자물쇠 아이콘 뜨면 완료. `http://`로 접속해도 caddy가 자동으로 `https://`로 리다이렉트한다.

## 마이그레이션 마무리 (오라클 정상 확인 후)

1. Render 대시보드에서 서비스 Suspend/Delete
2. Neon 프로젝트 삭제 (기존 게임 데이터를 옮길 필요가 있으면 삭제 전에 `pg_dump` → VM에서 restore)
