FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -DoutputFile=target/mvn-dependency-list.log -B -DskipTests clean dependency:list install

FROM debian:bookworm-slim
WORKDIR /app

RUN apt-get update && apt-get install -y \
    openjdk-17-jre-headless \
    nodejs npm \
    chromium \
    --no-install-recommends && \
    rm -rf /var/lib/apt/lists/*

ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
ENV CHROMEDRIVER_SKIP_DOWNLOAD=true
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

COPY package*.json ./
RUN npm install accessibility-checker --ignore-scripts

COPY --from=build /app/target/*.jar app.jar
COPY aceconfig.js .

ENTRYPOINT ["java", "-jar", "app.jar"]