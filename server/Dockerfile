FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -DoutputFile=target/mvn-dependency-list.log -B -DskipTests clean dependency:list install

FROM eclipse-temurin:21-jre
WORKDIR /app

# Install Node.js, Chromium and dependencies
RUN apt-get update && apt-get install -y \
    nodejs npm chromium \
    --no-install-recommends && \
    rm -rf /var/lib/apt/lists/*

RUN snap install chromium

ENV CHROMEDRIVER_SKIP_DOWNLOAD=true
ENV PUPPETEER_SKIP_CHROMIUM_DOWNLOAD=true
ENV PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium

# Install accessibility-checker locally
COPY package*.json ./
RUN npm install accessibility-checker --ignore-scripts

# Copy built jar and config
COPY --from=build /app/target/*.jar app.jar
COPY aceconfig.js .

ENTRYPOINT ["java", "-jar", "app.jar"]