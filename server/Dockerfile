FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y curl gnupg2 ca-certificates \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && curl -fsSL https://ftp-master.debian.org/keys/archive-key-11.asc \
       | gpg --dearmor -o /etc/apt/trusted.gpg.d/debian-bullseye.gpg \
    && echo "deb [arch=amd64 signed-by=/etc/apt/trusted.gpg.d/debian-bullseye.gpg] http://deb.debian.org/debian bullseye main" \
       > /etc/apt/sources.list.d/bullseye.list \
    && apt-get update \
    && apt-get install -y -t bullseye chromium \
    && rm /etc/apt/sources.list.d/bullseye.list \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

RUN npm install -g accessibility-checker

COPY --from=build /app/target/*.jar app.jar

ENV SERVER_PORT=${PORT:-8080}

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]