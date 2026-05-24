# syntax=docker/dockerfile:1.6
#
# Dockerfile for sigdep-hub ingestion-api.
#
# Build context: the root of sigdep-hub. CI does `mvn install` for
# sigdep-contracts then `mvn -pl ingestion-api -am package` for the
# multi-module hub build, see .github/workflows/release.yml.

FROM eclipse-temurin:17-jre-jammy

RUN groupadd --system --gid 1001 sigdep \
 && useradd  --system --uid 1001 --gid sigdep \
              --home-dir /opt/sigdep --shell /bin/false sigdep

WORKDIR /opt/sigdep

# Liquibase migrations + the fat jar are bundled inside ingestion-api ;
# the resources are read from the classpath at startup.
COPY ingestion-api/target/ingestion-api-*.jar /opt/sigdep/ingestion-api.jar
RUN chown sigdep:sigdep /opt/sigdep/ingestion-api.jar

USER sigdep

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q -O - http://localhost:8090/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/opt/sigdep/ingestion-api.jar"]
