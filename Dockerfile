# 多阶段构建 - 构建阶段
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# 复制 Maven 配置
COPY pom.xml .
COPY src ./src

# 构建应用（跳过测试加速构建）
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests -Dmaven.javadoc.skip=true

# 运行阶段
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="ecommerce-team"
LABEL description="SPU Multi-Level Cache Service"
LABEL version="1.0.0"

WORKDIR /app

# 创建非 root 用户
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# 从构建阶段复制 JAR
COPY --from=builder /app/target/*.jar app.jar

# 复制 Lua 脚本
COPY src/main/resources/lua /app/lua

# 设置时区
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata && \
    ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo $TZ > /etc/timezone

# JVM 参数
ENV JAVA_OPTS="-Xms2g -Xmx2g \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=50 \
    -XX:+UseStringDeduplication \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/app/logs/heapdump.hprof \
    -Djava.security.egd=file:/dev/./urandom"

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# 切换到非 root 用户
USER appuser

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
