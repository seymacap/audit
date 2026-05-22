FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update && apt-get install -y \
    chromium-browser \
    chromium-chromedriver \
    nodejs \
    npm \
    wget \
    curl \
    unzip \
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

ENV CHROME_BIN=/usr/bin/chromium-browser
ENV CHROMEDRIVER_PATH=/usr/bin/chromedriver

WORKDIR /app

COPY target/*.jar app.jar

RUN npm install -g @ibm-accessibility-checker/cli

ENTRYPOINT ["java", "-jar", "app.jar"]