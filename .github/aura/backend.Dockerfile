FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -Dmaven.test.skip=true package
FROM eclipse-temurin:21-jre-jammy
ENV LANG=C.UTF-8 JAVA_OPTS="-Xms256m -Xmx512m -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
RUN apt-get update && apt-get install -y --no-install-recommends fontconfig fonts-dejavu curl sqlite3
WORKDIR /app
COPY --from=build /build/target/*.jar /app/app.jar
EXPOSE 6365
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
