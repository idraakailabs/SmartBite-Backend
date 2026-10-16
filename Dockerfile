# Build stage. Dependencies resolve in their own layer, so a code-only change doesn't re-download them.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
# Run as an unprivileged user.
RUN useradd --system --uid 10001 --no-create-home smartbite
COPY --from=build /app/target/*.jar app.jar
USER smartbite
EXPOSE 8080
# The heap follows the instance size (75% of the container's memory) instead of a fixed -Xmx. On an
# out-of-memory error the JVM exits so Render restarts it. Add flags with the JAVA_TOOL_OPTIONS variable.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-XX:+UseG1GC", "-jar", "app.jar"]
