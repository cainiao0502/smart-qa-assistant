# ---- 构建阶段：Maven + JDK 21 ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# 先拷贝 pom 利用 Docker 层缓存下载依赖（依赖不变时跳过下载）
COPY pom.xml .
COPY ragent-framework/pom.xml ragent-framework/
COPY ragent-infra-ai/pom.xml ragent-infra-ai/
COPY ragent-bootstrap/pom.xml ragent-bootstrap/
RUN mvn -B -q -pl ragent-bootstrap -am dependency:go-offline || true

# 再拷贝源码构建
COPY ragent-framework/src ragent-framework/src
COPY ragent-infra-ai/src ragent-infra-ai/src
COPY ragent-bootstrap/src ragent-bootstrap/src
RUN mvn -B -q -pl ragent-bootstrap -am package -DskipTests

# ---- 运行阶段：JRE 21 ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root 运行
RUN useradd --system --create-home ragent
USER ragent

COPY --chown=ragent:ragent data/ data/

# 上传文件目录（挂载卷持久化）
RUN mkdir -p /app/uploads /app/data
VOLUME ["/app/uploads", "/app/data"]

COPY --from=build /app/ragent-bootstrap/target/ragent-bootstrap-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
