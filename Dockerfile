# Image de développement de GE-Televerse-Back.
#
# Elle sert à exercer le service en conteneur hors de l'infrastructure de l'État :
# base OpenJDK 21 publique, Ghostscript installé depuis les dépôts de la
# distribution. Elle N'EST PAS le livrable déployé — l'image de production part
# d'une base durcie et est construite par la chaîne d'intégration interne, dont la
# définition ne vit pas dans ce dépôt (cf. CONTRIBUTING.md).
#
# Construire :
#   mvn -DskipTests package
#   docker build -t ge-televerse-back:dev .
#
# Lancer en profil dev (Hazelcast embarqué, S3 simulé sur disque, pas de ClamAV) :
#   docker run --rm -p 8080:8080 -p 9000:9000 \
#     -e SPRING_PROFILES_ACTIVE=dev ge-televerse-back:dev

FROM eclipse-temurin:21-jre

# Ghostscript (binaire `gs`) est résolu via le PATH par GhostscriptService. Sans
# lui l'application démarre, mais la recompression est désactivée et la découpe des
# PDF multi-pages retombe sur PDFBox — qui abîme les polices en sous-ensemble.
# curl n'est là que pour le HEALTHCHECK plus bas.
# KICS : on n'épingle pas les versions apt. Cette image de dev repose sur le tag
# flottant eclipse-temurin:21-jre, et une version épinglée casserait le build dès
# qu'Ubuntu fait tourner son archive. L'image de production est construite ailleurs.
# kics-scan ignore-line
RUN apt-get update \
    && apt-get install -y --no-install-recommends ghostscript curl \
    && rm -rf /var/lib/apt/lists/*

COPY target/ge-televerse-*.jar /opt/ge-televerse.jar

# 8080 : API métier. 9000 : surface de gestion (management.server.port) —
# actuator/prometheus, jamais publiée vers l'extérieur en déploiement réel.
EXPOSE 8080 9000

# Utilisateur non root. Le uid 185 reprend celui de l'image de production, pour
# que les deux images se comportent pareil vis-à-vis des permissions de fichiers.
USER 185

ENV JAVA_OPTS="-Duser.timezone=UTC -XX:MaxRAMPercentage=75"

# Cible le port de gestion 9000 (cf. management.server.port), pas le port métier.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --output /dev/null http://127.0.0.1:9000/actuator/health/liveness

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/ge-televerse.jar"]
