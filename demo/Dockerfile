FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw ./
COPY demo demo
RUN sed -i 's/\r$//' mvnw && bash mvnw -B -f demo/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/demo/target/relayforge-demo-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
