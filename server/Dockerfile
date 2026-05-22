FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y curl \
    # Chromium system dependencies
    libnss3 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libxkbcommon0 \
    libxcomposite1 \
    libxdamage1 \
    libxfixes3 \
    libxrandr2 \
    libgbm1 \
    libasound2 \
    libpango-1.0-0 \
    libpangocairo-1.0-0 \
    libcairo2 \
    libgtk-3-0 \
    binutils \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y nodejs \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN npm install -g accessibility-checker

RUN CHROMIUM=$(find /usr/lib/node_modules/accessibility-checker -name 'chrome' -o -name 'chromium' 2>/dev/null | head -1) \
    && echo "=== Chromium binary: $CHROMIUM ===" \
    && if [ -n "$CHROMIUM" ]; then ldd "$CHROMIUM" | grep "not found" || echo "All libs found"; else echo "No chromium binary found"; fi

COPY --from=build /app/target/*.jar app.jar

ENV SERVER_PORT=${PORT:-8080}

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]