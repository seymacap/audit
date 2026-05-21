#!/bin/bash
set -e

echo "Installing accessibility-checker..."
npm install -g accessibility-checker

echo "Building Spring Boot app..."
chmod +x mvnw
./mvnw -DoutputFile=target/mvn-dependency-list.log -B -DskipTests clean dependency:list install