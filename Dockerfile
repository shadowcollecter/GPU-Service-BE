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
ENTRYPOINT ["java","-jar","/app/app.jar"]