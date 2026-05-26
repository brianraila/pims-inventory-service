FROM 559104660845.dkr.ecr.eu-west-1.amazonaws.com/amazoncorretto:17-alpine3.22-jdk

LABEL maintainer="Brian Amolo <bamolo@safaricom.co.ke>"

RUN apk --no-cache upgrade \
    && apk --no-cache add tzdata \
    && cp /usr/share/zoneinfo/Africa/Nairobi /etc/localtime \
    && echo "Africa/Nairobi" > /etc/timezone \
    && addgroup -S pims && adduser -S -G pims pims

COPY --chown=pims:pims target/ms-pims-inventory-service-1.0.0-SNAPSHOT.jar app.jar

USER pims

EXPOSE 8082

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8082/actuator/health || exit 1

ENTRYPOINT ["java", "-Djava.security.egd=file:/dev/./urandom", "--enable-native-access=ALL-UNNAMED", "-Dio.netty.tryReflectionSetAccessible=true", "-jar", "/app.jar"]
