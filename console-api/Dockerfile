# syntax=docker/dockerfile:1.6
#
# Dockerfile for sigdep-hub console-api.
#
# Build context: the root of sigdep-hub. CI does `mvn install` for
# sigdep-contracts then `mvn -pl console-api -am package` before
# `docker build`.

FROM eclipse-temurin:17-jre-jammy

RUN groupadd --system --gid 1001 sigdep \
 && useradd  --system --uid 1001 --gid sigdep \
              --home-dir /opt/sigdep --shell /bin/false sigdep

WORKDIR /opt/sigdep

COPY console-api/target/console-api-*.jar /opt/sigdep/console-api.jar
RUN chown sigdep:sigdep /opt/sigdep/console-api.jar

USER sigdep

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q -O - http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/opt/sigdep/console-api.jar"]
