@echo off
REM ============================================================
REM SPU 多级缓存服务 — 压力测试自动化脚本 (Windows)
REM ============================================================
REM 用法:
REM   scripts\stress-test.bat [BASE_URL] [DURATION_SEC] [CONCURRENCY]
REM
REM 参数:
REM   BASE_URL     服务地址 (默认 http://localhost:8080)
REM   DURATION_SEC 测试持续时间/秒 (默认 30)
REM   CONCURRENCY  并发工作者数 (默认 10)
REM ============================================================

setlocal enabledelayedexpansion

set BASE_URL=%1
if "%BASE_URL%"=="" set BASE_URL=http://localhost:8080

set DURATION=%2
if "%DURATION%"=="" set DURATION=30

set CONCURRENCY=%3
if "%CONCURRENCY%"=="" set CONCURRENCY=10

set REPORT_DIR=stress-test-reports
if not exist "%REPORT_DIR%" mkdir "%REPORT_DIR%"

for /f "tokens=2 delims==" %%a in ('wmic OS Get localdatetime /value') do set "dt=%%a"
set TIMESTAMP=%dt:~0,8%_%dt:~8,6%
set REPORT_FILE=%REPORT_DIR%\stress_test_%TIMESTAMP%.txt

echo ============================================================
echo  SPU Cache Service Stress Test (Windows)
echo  URL:         %BASE_URL%
echo  Duration:    %DURATION%s
echo  Concurrency: %CONCURRENCY%
echo  Report:      %REPORT_FILE%
echo ============================================================

echo SPU Cache Service Stress Test > "%REPORT_FILE%"
echo URL: %BASE_URL% >> "%REPORT_FILE%"
echo Duration: %DURATION%s >> "%REPORT_FILE%"
echo Concurrency: %CONCURRENCY% >> "%REPORT_FILE%"
echo. >> "%REPORT_FILE%"

REM ============================================================
REM Phase 0: Health Check
REM ============================================================
echo [INFO] Phase 0: Health check...
curl -s -o NUL -w "%%{http_code}" "%BASE_URL%/actuator/health" > "%TEMP%\health.txt" 2>NUL
set /p HEALTH=<"%TEMP%\health.txt"
if "%HEALTH%"=="200" (
    echo [OK]   Service is healthy
) else (
    echo [FAIL] Service is NOT healthy (HTTP %HEALTH%). Aborting.
    exit /b 1
)

REM ============================================================
REM Phase 1: Single Request Baseline
REM ============================================================
echo [INFO] Phase 1: Single request baseline...
echo --- Phase 1: Single Request Baseline --- >> "%REPORT_FILE%"

for %%S in (10001 10002 10003 99999) do (
    for /f "tokens=*" %%R in ('curl -s -o NUL -w "HTTP/%%{http_code} time=%%{time_total}s" "%BASE_URL%/api/spu/detail/%%S" 2^>NUL') do (
        echo   SPU %%S: %%R
        echo   SPU %%S: %%R >> "%REPORT_FILE%"
    )
)

REM ============================================================
REM Phase 2: Cache Warmup
REM ============================================================
echo [INFO] Phase 2: Cache warmup...
for /L %%i in (10001,1,10010) do (
    curl -s -o NUL "%BASE_URL%/api/spu/detail/%%i" 2>NUL
)
echo [OK]   Warmup complete

REM ============================================================
REM Phase 3: Sequential Stress Test (PowerShell Parallel)
REM ============================================================
echo [INFO] Phase 3: Stress test (%CONCURRENCY% workers, %DURATION%s)...
echo --- Phase 3: Stress Test --- >> "%REPORT_FILE%"

set TOTAL=0
set SUCCESS=0
set FAIL=0

REM Use PowerShell for timed concurrent requests
powershell -NoProfile -Command ^
  "$baseUrl = '%BASE_URL%'; " ^
  "$duration = %DURATION%; " ^
  "$concurrency = %CONCURRENCY%; " ^
  "$end = (Get-Date).AddSeconds($duration); " ^
  "$total = 0; $ok = 0; $fail = 0; " ^
  "1..$concurrency | ForEach-Object -Parallel { " ^
    "$localTotal = 0; $localOk = 0; " ^
    "while ((Get-Date) -lt $using:end) { " ^
      "$spuId = 10001 + (Get-Random -Maximum 100); " ^
      "try { " ^
        "$r = Invoke-WebRequest -Uri \"$using:baseUrl/api/spu/detail/$spuId\" -TimeoutSec 5 -ErrorAction Stop; " ^
        "$localOk++; " ^
      "} catch { $_ | Out-Null } " ^
      "$localTotal++; " ^
    "} " ^
    "\"$localTotal $localOk\"; " ^
  "} -ThrottleLimit $concurrency | ForEach-Object { " ^
    "$parts = $_ -split ' '; " ^
    "$total += [int]$parts[0]; $ok += [int]$parts[1]; " ^
  "}; " ^
  "\"TOTAL=$total SUCCESS=$ok FAIL=$($total - $ok)\" " > "%TEMP%\stress_result.txt" 2>NUL

for /f "tokens=1-3 delims= " %%a in ('type "%TEMP%\stress_result.txt"') do (
    for /f "tokens=2 delims==" %%x in ("%%a") do set TOTAL=%%x
    for /f "tokens=2 delims==" %%x in ("%%b") do set SUCCESS=%%x
    for /f "tokens=2 delims==" %%x in ("%%c") do set FAIL=%%x
)

echo   Total:   %TOTAL%
echo   Success: %SUCCESS%
echo   Failed:  %FAIL%
echo   Total: %TOTAL% >> "%REPORT_FILE%"
echo   Success: %SUCCESS% >> "%REPORT_FILE%"
echo   Failed: %FAIL% >> "%REPORT_FILE%"

REM ============================================================
REM Phase 4: Cache Statistics
REM ============================================================
echo [INFO] Phase 4: Cache statistics...
echo --- Phase 4: Cache Statistics --- >> "%REPORT_FILE%"
curl -s "%BASE_URL%/api/spu/cache/stats" >> "%REPORT_FILE%" 2>NUL
echo. >> "%REPORT_FILE%"

REM ============================================================
REM Summary
REM ============================================================
echo.
echo ============================================================
echo  STRESS TEST COMPLETE
echo  Total Requests: %TOTAL%
echo  Success:        %SUCCESS%
echo  Failed:         %FAIL%
echo  Report:         %REPORT_FILE%
echo ============================================================

if %FAIL% GTR 0 (
    echo [WARN] Some requests failed
) else (
    echo [OK]   All requests succeeded
)

endlocal
