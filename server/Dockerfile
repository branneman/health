FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build
COPY . .
RUN echo "server.baseUrl=https://placeholder" >> local.properties
RUN ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
ARG GIT_SHA=unknown
ENV GIT_SHA=$GIT_SHA
WORKDIR /app
COPY --from=build /build/server/build/install/server .
CMD ["bin/server"]
