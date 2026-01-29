#!/bin/bash
# ========================================
# SPU 多级缓存服务 - 快速启动脚本 (Linux/Mac)
# ========================================

set -e

echo ""
echo "===================================="
echo "  SPU Multi-Level Cache Service"
echo "===================================="
echo ""

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo "[ERROR] Docker not found. Please install Docker first."
    exit 1
fi

# 检查 Java
if ! command -v java &> /dev/null; then
    echo "[ERROR] Java not found. Please install JDK 17+."
    exit 1
fi

# 检查 Maven
if ! command -v mvn &> /dev/null; then
    echo "[ERROR] Maven not found. Please install Maven 3.8+."
    exit 1
fi

echo "[1/4] Starting infrastructure services..."
docker-compose up -d redis memcached rocketmq mysql
if [ $? -ne 0 ]; then
    echo "[ERROR] Failed to start Docker services."
    exit 1
fi

echo "[2/4] Waiting for services to be ready (30s)..."
sleep 30

echo "[3/4] Initializing database..."
docker exec -i spu-mysql mysql -uroot -proot123 < scripts/init-db.sql 2>/dev/null || true

echo "[4/4] Starting application..."
echo ""
echo "Starting with profile: dev"
echo ""

mvn spring-boot:run -Dspring-boot.run.profiles=dev
