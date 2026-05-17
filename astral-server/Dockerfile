# 多阶段构建
# 第一阶段: 编译
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# 复制pom文件
COPY pom.xml .
COPY astral-common/pom.xml astral-common/
COPY astral-dao/pom.xml astral-dao/
COPY astral-server/pom.xml astral-server/

# 下载依赖
RUN mvn dependency:go-offline -B

# 复制源码
COPY astral-common/src astral-common/src
COPY astral-dao/src astral-dao/src
COPY astral-server/src astral-server/src

# 编译打包
RUN mvn clean package -DskipTests -B

# 第二阶段: 运行
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 创建非root用户
RUN addgroup -S astral && adduser -S astral -G astral

# 复制jar包
COPY --from=builder /build/astral-server/target/astral-server-*.jar app.jar

# 复制SQL脚本
COPY sql/ /app/sql/

# 设置时区
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

# 暴露端口
EXPOSE 8080

# 切换到非root用户
USER astral

# JVM参数优化
ENV JAVA_OPTS="-Xms512m -Xmx1024m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/app/logs \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0"

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
