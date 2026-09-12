# 多阶段构建
# 第一阶段: 编译 (Java 21)
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# 复制根 pom
COPY pom.xml .

# 复制各模块 pom (用于提前下载依赖, 利用 Docker 层缓存)
COPY astral-common/pom.xml astral-common/
COPY astral-schema/pom.xml astral-schema/
COPY astral-dao/pom.xml astral-dao/
COPY astral-log/pom.xml astral-log/
COPY astral-auth/pom.xml astral-auth/
COPY astral-system/pom.xml astral-system/
COPY astral-monitor/pom.xml astral-monitor/
COPY astral-sequence/pom.xml astral-sequence/
COPY astral-plugin-api/pom.xml astral-plugin-api/
COPY astral-plugin/pom.xml astral-plugin/
COPY astral-plugin-demo/pom.xml astral-plugin-demo/
COPY astral-plugin-qt/pom.xml astral-plugin-qt/
# 注意：根 pom 的 <modules> 新增模块时，此处必须同步补齐，否则 mvn 无法读取反应堆
# （缺失模块时报 "Child module ... does not exist"，构建直接失败）
COPY astral-plugin-feedback/pom.xml astral-plugin-feedback/
COPY astral-server/pom.xml astral-server/

# 下载依赖
RUN mvn dependency:go-offline -B

# 复制全部源码
COPY astral-common/src astral-common/src
COPY astral-schema/src astral-schema/src
COPY astral-dao/src astral-dao/src
COPY astral-log/src astral-log/src
COPY astral-auth/src astral-auth/src
COPY astral-system/src astral-system/src
COPY astral-monitor/src astral-monitor/src
COPY astral-sequence/src astral-sequence/src
COPY astral-plugin-api/src astral-plugin-api/src
COPY astral-plugin/src astral-plugin/src
COPY astral-plugin-demo/src astral-plugin-demo/src
COPY astral-plugin-qt/src astral-plugin-qt/src
COPY astral-plugin-feedback/src astral-plugin-feedback/src
COPY astral-server/src astral-server/src

# 编译打包 (仅 server 及其依赖)
RUN mvn clean package -DskipTests -B -pl astral-server -am

# 第二阶段: 运行 (Java 21 JRE)
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 创建非 root 用户
RUN addgroup -S astral && adduser -S astral -G astral

# 复制 jar 包
COPY --from=builder /build/astral-server/target/astral-server-*.jar app.jar

# 设置时区
ENV TZ=Asia/Shanghai
RUN apk add --no-cache tzdata curl && \
    ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 数据目录 (rsa-key.pair / 文件数据库等)
RUN mkdir -p /app/data /app/logs && chown -R astral:astral /app

# 暴露端口
EXPOSE 27000

# 切换到非 root 用户
USER astral

# JVM 参数优化（默认基线：面向 1核/2GiB 学生机）
# 生产环境可通过 docker-compose 的 JAVA_OPTS 覆盖（引用 deploy/prod.env 参数）
ENV JAVA_OPTS="-Xms384m -Xmx384m \
  -XX:MetaspaceSize=96m \
  -XX:MaxMetaspaceSize=128m \
  -XX:+UseSerialGC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/logs \
  -XX:+ExitOnOutOfMemoryError \
  -Duser.timezone=Asia/Shanghai"

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD curl -fsS http://localhost:27000/actuator/health || exit 1

# 启动命令
# SPRING_PROFILES_ACTIVE=prod 由 docker-compose 注入，激活 application-prod.yml（虚拟线程/线程参数/日志降级）
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
