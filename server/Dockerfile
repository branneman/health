FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY . .
RUN rm -f gradle/gradle-daemon-jvm.properties && ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/server/build/install/server .
CMD ["bin/server"]
