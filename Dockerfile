FROM eclipse-temurin:17-jre-alpine

# wget is used by the HEALTHCHECK below
RUN apk add --no-cache wget

RUN mkdir -p /app
WORKDIR /app
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} /app/app.jar

# Run as a non-root user (fixed UID/GID so it matches the k8s securityContext)
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app && chown -R app:app /app
USER 10001

EXPOSE 5000

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:5000/pubmed/ping || exit 1

CMD java -Djava.security.egd=file:/dev/./urandom $JAVA_OPTIONS -jar /app/app.jar
