# 로컬 빌드 → VM 전송 → 원격 이미지 재빌드(컴파일 없음, 몇 초)+재기동까지 한 번에.
# VM에서 직접 컴파일하던 기존 redeploy.sh보다 훨씬 빠르다 (컴파일은 이 스크립트가 로컬에서 미리 끝냄).
#
# 사용: powershell -File deploy\oracle\local-deploy.ps1 -KeyPath C:\Users\DM\.ssh\ssh-key-2026-08-10.key
#
# 주의: 이 스크립트로 배포한 뒤에는 VM에서 그냥 redeploy.sh만 다시 돌려도 코드가 갱신되지 않는다
# (그 자리에 이미 올려둔 app.jar를 그대로 재사용할 뿐) — 코드를 바꿀 때마다 항상 이 스크립트를 써야 한다.
param(
    [Parameter(Mandatory=$true)][string]$KeyPath,
    [string]$VmHost = "ubuntu@161.33.131.190"
)
$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\.."

if (-not (Test-Path $KeyPath)) { throw "개인키를 찾을 수 없음: $KeyPath" }

Write-Host "[1/3] 로컬 빌드 (./gradlew bootJar)..." -ForegroundColor Cyan
& "$root\gradlew.bat" bootJar
if ($LASTEXITCODE -ne 0) { throw "빌드 실패" }

$jar = Get-ChildItem "$root\build\libs\mo_gaia_project_be-*.jar" |
    Where-Object { $_.Name -notlike "*-plain.jar" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw "빌드된 jar를 build\libs 에서 찾을 수 없음" }
Write-Host "  -> $($jar.Name) ($([math]::Round($jar.Length/1MB,1))MB)"

Write-Host "[2/3] VM으로 전송 (scp)..." -ForegroundColor Cyan
scp -i $KeyPath $jar.FullName "${VmHost}:~/mo_gaia_project_be/deploy/oracle/app.jar"
if ($LASTEXITCODE -ne 0) { throw "전송 실패" }

Write-Host "[3/3] 원격 이미지 재빌드 + 재기동 (redeploy.sh)..." -ForegroundColor Cyan
ssh -i $KeyPath $VmHost "bash ~/mo_gaia_project_be/deploy/oracle/redeploy.sh"
if ($LASTEXITCODE -ne 0) { throw "원격 배포 실패" }

Write-Host "완료" -ForegroundColor Green
