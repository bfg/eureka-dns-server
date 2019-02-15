#
# Dockerfile
#
# Reference: https://docs.docker.com/engine/reference/builder/
#

# inherit official openjdk jre image
FROM          openjdk:11-jre-slim

# we would like to reuse the same dockerfile without modifications
# when the service artifact file changes
ARG         serviceJar

# create app folder and copy service starter
RUN         mkdir -p /service
COPY        java-service-starter /usr/bin

# copy the app archive
COPY        ${serviceJar:-service.jar} /service

USER        nobody
ENTRYPOINT  [ "/usr/bin/java-service-starter" ]

# vim:shiftwidth=2 softtabstop=2 expandtab
# EOF
