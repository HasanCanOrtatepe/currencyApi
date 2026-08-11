# syntax=docker/dockerfile:1.7
#
# T34 Faz 7 — sahte kur satıcısı imajı.
#
# CRM'in kök Containerfile'ından AYRIDIR ve olmalıdır: o dosya çok modüllü reaktörü bilir
# (MODULE build argümanı, common-lib katmanı). Bu proje reaktöre girmez, tek modüldür ve
# kendi yaşam döngüsüne sahiptir — build tanımının da ayrı olması bunun doğal sonucudur.

FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# Bağımlılık çözümü kaynak koddan ayrılır: Java değişikliği Maven indirmelerini geçersizleştirmez.
COPY pom.xml ./
RUN --mount=type=cache,id=currency-api-maven-repo,target=/root/.m2 \
    mvn -B -ntp dependency:go-offline

COPY src src
RUN --mount=type=cache,id=currency-api-maven-repo,target=/root/.m2 \
    mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/target/currency-api-*.jar app.jar
EXPOSE 8095
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
