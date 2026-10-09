FROM maven:3.9.6-eclipse-temurin-8 AS build
WORKDIR /workspace
COPY account/pom.xml account/pom.xml
COPY account/src account/src
RUN mvn -q -f account/pom.xml -DskipTests package
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /workspace/account/target/account-service-1.0.0-exec.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
