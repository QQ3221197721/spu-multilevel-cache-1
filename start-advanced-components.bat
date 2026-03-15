@echo off
REM 高级组件启动脚本 (Windows版本)
REM 用于启用所有分布式和中间件高级功能

echo 🚀 启动高级组件系统...

REM 设置JVM参数
set JAVA_OPTS=-Xmx4g -Xms2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
set SPRING_PROFILES_ACTIVE=advanced-components

REM 创建必要的目录
if not exist "data\bloom" mkdir "data\bloom"
if not exist "logs" mkdir "logs"

REM 启动应用
echo 正在启动应用，配置文件: application-advanced-components.yml
echo 启用的高级组件:
echo   ✓ V6 智能路由和预测缓存
echo   ✓ V7 高级智能序列化
echo   ✓ V8 布隆过滤器优化
echo   ✓ V10 混沌工程测试
echo   ✓ V15 量子纠缠缓存
echo   ✓ V16 表面码量子纠错
echo   ✓ 零拷贝序列化
echo   ✓ 端到端追踪增强

REM 运行应用
java %JAVA_OPTS% -jar target\spu-multilevel-cache-*.jar

echo 应用启动完成，查看日志请运行: type logs\application.log
pause