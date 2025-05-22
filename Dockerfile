# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jre
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} app.jar
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app.jar \"$@\"", "--"]