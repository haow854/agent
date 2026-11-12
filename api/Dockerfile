FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/docAgent-1.0.0.jar /app/docAgent.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/docAgent.jar"]
