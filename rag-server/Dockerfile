# 构建阶段：Maven + JDK 17
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# 国内网络可在构建时加 --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public 加速
ARG MAVEN_MIRROR=""
RUN if [ -n "$MAVEN_MIRROR" ]; then \
        mkdir -p /root/.m2 && \
        printf '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><mirrors><mirror><id>mirror</id><url>%s</url><mirrorOf>central</mirrorOf></mirror></mirrors></settings>' "$MAVEN_MIRROR" > /root/.m2/settings.xml; \
    fi

COPY pom.xml .
# 先解析依赖做层缓存，源码变更时无需重新下载
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -q -DskipTests package

# 运行阶段：仅 JRE
FROM eclipse-temurin:17-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY --from=build /app/target/ai-nexus-server-1.0.0.jar app.jar
EXPOSE 8888
ENTRYPOINT ["java", "-jar", "app.jar"]
