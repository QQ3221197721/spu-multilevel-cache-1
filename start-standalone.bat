@echo off
chcp 65001 >nul
REM ========================================
REM SPU Multi-Level Cache - Quick Start (No Docker)
REM ========================================

echo.
echo  ====================================
echo   SPU Multi-Level Cache Service
echo   (Standalone Mode - No Docker)
echo  ====================================
echo.

REM Check Java
where java >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Java not found. Please install JDK 17+.
    pause
    exit /b 1
)

echo [INFO] Starting application in standalone mode...
echo [INFO] Redis/Memcached will use embedded mock if not available.
echo.

REM Set profiles for standalone mode
set SPRING_PROFILES_ACTIVE=dev,standalone

REM Check if mvn exists
where mvn >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Maven not found. Using mvnw instead...
    call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev,standalone
) else (
    call mvn spring-boot:run -Dspring-boot.run.profiles=dev,standalone
)

pause
