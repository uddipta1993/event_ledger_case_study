FROM maven:3.9.6-eclipse-temurin-8 AS build
WORKDIR /workspace
COPY pom.xml pom.xml
COPY account/pom.xml account/pom.xml
COPY account/src account/src
COPY gateway/pom.xml gateway/pom.xml
COPY gateway/src gateway/src
RUN mvn -q -pl gateway -am -DskipTests package
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /workspace/gateway/target/event-gateway-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
