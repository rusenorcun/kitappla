# --- 1. aşama: derleme ---
# Kaynak koddan jar üretilir. Bu aşama son imaja dahil edilmez;
# böylece Maven, JDK ve kaynak kod sunucuya hiç gitmez.
FROM maven:3.9-eclipse-temurin-21 AS derleme
WORKDIR /derleme

# Önce yalnızca pom.xml kopyalanır: kaynak kod değişse de bağımlılıklar
# yeniden indirilmez, katman önbelleğinden gelir.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# --- 2. aşama: çalıştırma ---
# Yalnızca JRE: imaj küçük kalır ve derleme araçları saldırı yüzeyine girmez.
FROM eclipse-temurin:21-jre-alpine

# Sağlık kontrolü ve Türkçe karakterler için
RUN apk add --no-cache curl tzdata

# Uygulama root olarak çalışmaz
RUN addgroup -S kitappla && adduser -S -G kitappla -h /uygulama kitappla
WORKDIR /uygulama

COPY --from=derleme /derleme/target/kitappla-*.jar uygulama.jar

# Yüklenen dosyalar; compose buraya kalıcı birim bağlar (veritabanı ayrı PostgreSQL konteynerinde).
RUN mkdir -p /uygulama/uploads && chown -R kitappla:kitappla /uygulama
USER kitappla

ENV LANG=C.UTF-8 \
    TZ=Europe/Istanbul \
    # Yığın, konteynere verilen belleğin %75'i: compose'da bellek sınırı olmadığında makinenin
    # tüm RAM'ine göre büyür (80 GB'lık makinede ~29 GB). Bellek taşarsa süreç temizce
    # kapanır ve restart politikası yeniden başlatır.
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+ParallelRefProcEnabled"

EXPOSE 8080

HEALTHCHECK --interval=20s --timeout=4s --start-period=60s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/saglik || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar uygulama.jar"]
