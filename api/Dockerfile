FROM eclipse-temurin:17-jre-noble

WORKDIR /app

# jar 由 deploy/build.sh 在开发机上构建
COPY target/docAgent-1.0.0.jar /app/docAgent.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/docAgent.jar"]
