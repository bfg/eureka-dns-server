#
# Dockerfile
#
# Reference: https://docs.docker.com/engine/reference/builder/
#

# inherit official openjdk jre image
FROM          azul/zulu-openjdk:17-jre

# we would like to reuse the same dockerfile without modifications
# when the service artifact file changes
ARG           serviceJar

# create app folder and copy service starter
RUN           mkdir -p /app
COPY          java-service-starter /usr/local/bin

# copy the app archive
COPY          ${serviceJar:-service.jar} /app

# Java 17 ends module system freeride with --illegal-access=warn
ENV           JAVA_TOOL_OPTIONS="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED --add-opens=java.desktop/java.awt.font=ALL-UNNAMED"

USER          nobody
ENTRYPOINT    [ "/usr/local/bin/java-service-starter" ]

# vim:shiftwidth=2 softtabstop=2 expandtab
# EOF
