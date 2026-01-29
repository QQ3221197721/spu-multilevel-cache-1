# SPU 多级缓存服务 Makefile
# 常用命令快捷方式

.PHONY: help build test run clean docker-build docker-run k8s-deploy

# 默认目标
help:
	@echo "SPU Multi-Level Cache Service"
	@echo ""
	@echo "Usage:"
	@echo "  make build        - 编译项目"
	@echo "  make test         - 运行单元测试"
	@echo "  make run          - 本地运行（开发模式）"
	@echo "  make run-prod     - 本地运行（生产模式）"
	@echo "  make clean        - 清理编译产物"
	@echo "  make package      - 打包 JAR"
	@echo "  make docker-build - 构建 Docker 镜像"
	@echo "  make docker-run   - 运行 Docker 容器"
	@echo "  make docker-up    - 启动所有依赖服务"
	@echo "  make docker-down  - 停止所有服务"
	@echo "  make k8s-deploy   - 部署到 Kubernetes"
	@echo "  make gatling      - 运行压力测试"

# 编译
build:
	mvn clean compile -DskipTests

# 测试
test:
	mvn test

# 测试覆盖率
test-coverage:
	mvn test jacoco:report
	@echo "Coverage report: target/site/jacoco/index.html"

# 本地运行
run:
	mvn spring-boot:run -Dspring-boot.run.profiles=dev

run-prod:
	mvn spring-boot:run -Dspring-boot.run.profiles=prod

# 清理
clean:
	mvn clean

# 打包
package:
	mvn clean package -DskipTests

# Docker 构建
docker-build: package
	docker build -t spu-cache-service:latest .

# Docker 运行
docker-run:
	docker run -d --name spu-cache \
		-p 8080:8080 \
		-e SPRING_PROFILES_ACTIVE=dev \
		spu-cache-service:latest

# 启动依赖服务
docker-up:
	docker-compose up -d

# 停止服务
docker-down:
	docker-compose down

# 查看日志
docker-logs:
	docker-compose logs -f spu-cache-service

# K8s 部署
k8s-deploy:
	kubectl apply -f k8s/deployment.yaml
	kubectl apply -f k8s/ingress.yaml

k8s-delete:
	kubectl delete -f k8s/ingress.yaml
	kubectl delete -f k8s/deployment.yaml

# 压力测试
gatling:
	cd gatling && mvn gatling:test

# 初始化数据库
init-db:
	docker exec -i spu-mysql mysql -uroot -proot123 < scripts/init-db.sql

# 代码格式化
format:
	mvn spotless:apply

# 代码检查
lint:
	mvn spotless:check
