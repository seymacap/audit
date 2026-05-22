FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jdk-jammy

RUN apt-get update && apt-get install -y \
    chromium-browser \
    nodejs \
    npm \
    fonts-liberation \
    libatk-bridge2.0-0 \
    libatk1.0-0 \
    libcups2 \
    libxkbcommon0 \
    libxcomposite1 \
    libxrandr2 \
    libgbm1 \
    libgtk-3-0 \
    libnss3 \
    libasound2 \
    libx11-xcb1 \
    libxdamage1 \
    libxfixes3 \
    libpango-1.0-0 \
    libpangocairo-1.0-0 \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN npm install -g @ibm-accessibility-checker/cli

WORKDIR /app

COPY --from=builder /build/target/*.jar app.jar

ENTRYPOINT ["java","-jar","app.jar"]