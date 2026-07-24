FROM maven:3.9.9-amazoncorretto-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:resolve dependency:resolve-plugins
COPY src ./src
RUN mvn -q -DskipTests package

FROM amazoncorretto:21-alpine
RUN addgroup -S rsvqa && adduser -S rsvqa -G rsvqa
WORKDIR /opt/rsvqa
COPY --from=build /workspace/target/rs-vqa-gateway-0.3.0.jar app.jar
RUN mkdir -p /opt/rsvqa/uploads && chown -R rsvqa:rsvqa /opt/rsvqa
USER rsvqa
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/opt/rsvqa/app.jar"]
