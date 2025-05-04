# Use a lightweight JDK base image
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# copy gradle wrapper and build files
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./

# download dependencies
RUN chmod +x gradlew
RUN ./gradlew --no-daemon dependencies

# copy source and build
COPY src src
RUN ./gradlew --no-daemon bootJar

# final image
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/gpu-service-BE-*.jar app.jar

# 複製 customize-yaml 目錄到容器中
COPY customize-yaml /app/customize-yaml

# 設置環境變數，指定使用k8s配置文件
ENV SPRING_PROFILES_ACTIVE=k8s

# 設置JVM參數以優化容器中的記憶體使用
ENV JAVA_OPTS="-Xmx2g -Xms1g -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# 啟動應用程序
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]