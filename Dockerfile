FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress -DskipTests dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:17-jre-jammy

WORKDIR /app
RUN useradd --system --uid 10001 --create-home campusdeal
COPY --from=build /workspace/target/campus-deal-0.0.1-SNAPSHOT.jar /app/campusdeal.jar
RUN mkdir -p /app/data/uploads && chown -R campusdeal:campusdeal /app

USER campusdeal
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/campusdeal.jar"]
