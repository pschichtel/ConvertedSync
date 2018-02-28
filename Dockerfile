FROM hseeberger/scala-sbt AS build
RUN mkdir /build
COPY . /build/
WORKDIR /build
RUN sbt assembly

FROM openjdk:8-jre-alpine
RUN adduser -D -h /app convertedsync
USER convertedsync
WORKDIR /app
COPY --from=build /build/target/scala-*/ConvertedSync-*.jar convertedsync.jar
COPY converters converters/
COPY adapters adapters/
ENTRYPOINT ["java", "-jar", "convertedsync.jar"]