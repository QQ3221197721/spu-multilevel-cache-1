@echo off
chcp 65001 >nul
REM ========================================
REM SPU Multi-Level Cache - Quick Start
REM ========================================

echo.
echo  ====================================
echo   SPU Multi-Level Cache Service
echo  ====================================
echo.

REM Check Docker
where docker >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Docker not found. Please install Docker first.
    pause
    exit /b 1
)

REM Check Java
where java >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Java not found. Please install JDK 17+.
    pause
    exit /b 1
)

echo [1/4] Starting infrastructure services...
docker-compose up -d redis-node-1 redis-node-2 redis-node-3 memcached-1 memcached-2 memcached-3
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Failed to start Docker services.
    pause
    exit /b 1
)

echo [2/4] Waiting for services to be ready (30s)...
timeout /t 30 /nobreak >nul

echo [3/4] Initializing Redis cluster...
docker-compose up -d redis-cluster-init

echo [4/4] Starting application...
echo.
echo Starting with profile: dev
echo.

call mvn spring-boot:run -Dspring-boot.run.profiles=dev

pause
