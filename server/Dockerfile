FROM eclipse-temurin:21-jdk-alpine

RUN apk add --no-cache nodejs npm chromium

ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser

RUN npm install -g accessibility-checker

# Copy and run the Spring Boot jar
WORKDIR /app
COPY target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]