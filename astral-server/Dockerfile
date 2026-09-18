# syntax=docker/dockerfile:1
# 多阶段构建
# 第一阶段: 编译 (Java 21)
# 【已实测，勿改回 alpine】部署服务器上 eclipse-temurin:21-jdk-alpine 与
# maven:3.9-eclipse-temurin-21-alpine 里的 java 连 `java -version` 都起不来，报
# "Error occurred during initialization of VM / Failed setting boot class path"
# （镜像 env 干净，无 CLASSPATH 等干扰；与 maven 无关，是 JDK alpine 变体本身的问题）。
# 而 eclipse-temurin:21-jre-alpine（运行阶段）实测正常，所以仅构建阶段必须用 Ubuntu 基础镜像。
# 构建阶段不进最终镜像，两者体积差异对产物无影响。
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 默认 requestTimeout=1800s。Central 不通时单次请求能卡 30 分钟（旧版 go-offline 实测 ~33 分钟就是这个）。
# maven.config 也会设同样的值；这里用 ENV 兜底，避免 .mvn 没拷进来时超时失效。
ENV MAVEN_OPTS="-Daether.connector.connectTimeout=10000 -Daether.connector.requestTimeout=60000 -Daether.connector.http.retryHandler.count=2 -Dmaven.wagon.http.connectionTimeout=10000 -Dmaven.wagon.http.readTimeout=60000"

# Maven 走阿里云镜像。海外机器若阿里云更慢：把下面 RUN 的 -s .mvn/settings.xml 去掉即可回退官方 Central。
# 注意：settings 必须放在 cache mount 之外。若 mount 整个 /root/.m2，这层 COPY 会被盖住。
COPY .mvn .mvn

# 复制根 pom
COPY pom.xml .

# 复制各模块 pom（源码变更时这一层仍命中缓存；真正的依赖缓存靠下面的 BuildKit cache mount）
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
# 注意：根 pom 的 <modules> 新增模块时，此处必须同步补齐，否则 mvn 无法读取反应堆
# （缺失模块时报 "Child module ... does not exist"，构建直接失败）
COPY astral-server/pom.xml astral-server/

# 不要 RUN mvn dependency:go-offline：
# 多模块反应堆里 com.astral:*（尤其是 astral-schema 这个 maven-plugin）此时还不存在，
# go-offline 会去 Central 解析这些内部构件，失败后按默认 1800s 超时重试，实测可卡 ~33 分钟。
# 依赖下载合并进下面的 package。

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
COPY astral-server/src astral-server/src

# 编译打包（仅 server 及其依赖）。不加 clean：上下文已排除 target/，clean 只是空跑。
#
# --mount=type=cache 的 target=/root/.m2/repository 是构建容器内部路径，
# 不是宿主机 /root/.m2。服务器上没有这个目录完全正常。
# 真实缓存由 BuildKit 管，落在 Docker 数据目录（Linux 一般是
# /var/lib/docker/buildkit），用 `docker buildx du` 能看到。
# 加 id=astral-maven 让缓存有稳定名字，二次构建复用同一份仓库。
# 不要用 docker compose build --no-cache，否则这层缓存作废。
RUN --mount=type=cache,id=astral-maven,target=/root/.m2/repository \
    mvn -B -s .mvn/settings.xml -DskipTests package -pl astral-server -am

# 第二阶段: 运行 (Java 21 JRE)
# 用 alpine 变体：约 170MB，Ubuntu 基础的 21-jre 约 330MB，省约 150MB。
# 【已实测】docker run --rm eclipse-temurin:21-jre-alpine java -version 在部署服务器正常
# （Temurin 21.0.12）。注意只有 JRE 变体可用，JDK / maven 的 alpine 变体会启动失败（见文件头注释）。
# 若需回退到 Debian 写法，替换本阶段前三行为：
#   FROM eclipse-temurin:21-jre
#   RUN groupadd -r astral && useradd -r -g astral astral
#   RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends tzdata curl && \
#       ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone && rm -rf /var/lib/apt/lists/*
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 创建非 root 用户
RUN addgroup -S astral && adduser -S astral -G astral

# 复制 jar 包
COPY --from=builder /build/astral-server/target/astral-server-*.jar app.jar

# 设置时区（alpine 用 apk；curl 供 HEALTHCHECK 使用）
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
