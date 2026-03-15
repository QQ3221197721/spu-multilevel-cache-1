# SPU 多级缓存服务 Makefile
# 常用命令快捷方式

.PHONY: help build test run clean docker-build docker-run k8s-deploy native-build native-run chaos-apply chaos-delete istio-apply

# 默认目标
help:
	@echo "SPU Multi-Level Cache Service"
	@echo ""
	@echo "Usage:"
	@echo "  make build              - 编译项目"
	@echo "  make test               - 运行单元测试"
	@echo "  make run                - 本地运行（开发模式）"
	@echo "  make run-prod           - 本地运行（生产模式）"
	@echo "  make clean              - 清理编译产物"
	@echo "  make package            - 打包 JAR"
	@echo "  make docker-build       - 构建 Docker 镜像"
	@echo "  make docker-run         - 运行 Docker 容器"
	@echo "  make docker-up          - 启动所有依赖服务"
	@echo "  make docker-down        - 停止所有服务"
	@echo "  make k8s-deploy         - 部署到 Kubernetes"
	@echo "  make gatling            - 运行压力测试"
	@echo "  === GraalVM Native Image ==="
	@echo "  make native-build       - GraalVM 原生编译"
	@echo "  make native-run         - 运行原生可执行文件"
	@echo "  make docker-build-native- 构建原生 Docker 镜像"
	@echo "  === Istio Service Mesh ==="
	@echo "  make istio-apply        - 应用 Istio 配置"
	@echo "  make istio-delete       - 删除 Istio 配置"
	@echo "  make istio-traffic      - 查看 Istio 流量状态"
	@echo "  === eBPF 可观测性 ==="
	@echo "  make ebpf-apply         - 部署 eBPF 监控组件"
	@echo "  make ebpf-delete        - 删除 eBPF 监控组件"
	@echo "  === Chaos Mesh ==="
	@echo "  make chaos-apply        - 应用混沌实验"
	@echo "  make chaos-delete       - 删除混沌实验"
	@echo "  make chaos-status       - 查看混沌实验状态"
	@echo "  make chaos-workflow     - 执行混沌工作流"

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

# ============================
# GraalVM Native Image
# ============================

# 原生编译 (需要 GraalVM 21+)
native-build:
	mvn clean package -Pnative -DskipTests -Dspring.aot.enabled=true

# 运行原生可执行文件
native-run:
	./target/spu-multilevel-cache --spring.profiles.active=dev

# 构建原生 Docker 镜像
docker-build-native:
	docker build -f Dockerfile.native -t spu-cache-service-native:latest .

# 运行原生 Docker 容器
docker-run-native:
	docker run -d --name spu-cache-native -p 8080:8080 -e SPRING_PROFILES_ACTIVE=dev spu-cache-service-native:latest

# Spring Boot Buildpacks 原生构建
native-buildpacks:
	mvn spring-boot:build-image -Pnative -DskipTests

# ============================
# Istio Service Mesh
# ============================

# 应用 Istio 全套配置
istio-apply:
	kubectl apply -f k8s/istio/peer-authentication.yaml
	kubectl apply -f k8s/istio/destination-rule.yaml
	kubectl apply -f k8s/istio/virtual-service.yaml
	kubectl apply -f k8s/istio/gateway.yaml
	kubectl apply -f k8s/istio/authorization-policy.yaml
	kubectl apply -f k8s/istio/sidecar.yaml
	@echo "Istio 配置已应用"

# 删除 Istio 配置
istio-delete:
	kubectl delete -f k8s/istio/ --ignore-not-found=true
	@echo "Istio 配置已删除"

# 查看 Istio 流量状态
istio-traffic:
	kubectl get virtualservices -n spu-cache
	kubectl get destinationrules -n spu-cache
	kubectl get peerauthentication -n spu-cache
	istioctl proxy-status

# Istio 金丝雀流量切换 (usage: make istio-canary WEIGHT=20)
istio-canary:
	@CANARY=$${WEIGHT:-10}; STABLE=$$((100-$$CANARY)); \
	echo "切换流量: stable=$$STABLE%%, canary=$$CANARY%%"

# ============================
# eBPF 可观测性
# ============================

# 部署 eBPF 监控组件
ebpf-apply:
	kubectl apply -f k8s/ebpf/cilium-hubble.yaml
	kubectl apply -f k8s/ebpf/ebpf-exporter.yaml
	@echo "eBPF 监控组件已部署"

# 删除 eBPF 监控组件
ebpf-delete:
	kubectl delete -f k8s/ebpf/ --ignore-not-found=true
	@echo "eBPF 监控组件已删除"

# 查看 Hubble 流量
ebpf-hubble:
	hubble observe --namespace spu-cache --last 50

# ============================
# Chaos Mesh 混沌工程
# ============================

# 应用全部混沌实验
chaos-apply:
	kubectl apply -f chaos/pod-chaos.yaml
	kubectl apply -f chaos/network-chaos.yaml
	kubectl apply -f chaos/io-chaos.yaml
	kubectl apply -f chaos/stress-chaos.yaml
	@echo "混沌实验已应用"

# 删除全部混沌实验
chaos-delete:
	kubectl delete -f chaos/ --ignore-not-found=true
	@echo "混沌实验已删除"

# 查看混沌实验状态
chaos-status:
	kubectl get podchaos,networkchaos,iochaos,stresschaos -n spu-cache

# 执行混沌工作流
chaos-workflow:
	kubectl apply -f chaos/workflow.yaml
	@echo "混沌工作流已启动"

# 应用定时混沌调度
chaos-schedule:
	kubectl apply -f chaos/schedule.yaml
	@echo "混沌定时调度已应用"

# 单独执行 Pod Kill 实验
chaos-pod-kill:
	kubectl apply -f chaos/pod-chaos.yaml
	@echo "Pod Kill 实验已启动"

# 单独执行网络混沌实验
chaos-network:
	kubectl apply -f chaos/network-chaos.yaml
	@echo "网络混沌实验已启动"
