# syntax=docker/dockerfile:1
# Database backup sidecar (ADR 0041). Built with infra/deploy/backup as the context. Same Postgres/pgvector image as the database so
# pg_dump and pg_restore always match the server major version (the digest is bumped together with docker-compose.yml).
FROM pgvector/pgvector:pg16@sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b

# awscli only for the optional off-host copy (any S3-compatible store: R2, S3, ...).
RUN apt-get update \
 && apt-get install -y --no-install-recommends awscli ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY . /usr/local/bin/
RUN mkdir -p /usr/local/lib/awscli-plugins /usr/local/etc \
 && mv /usr/local/bin/awscli/no_expect.py /usr/local/lib/awscli-plugins/ \
 && mv /usr/local/bin/awscli/config /usr/local/etc/aws-config \
 && rm -rf /usr/local/bin/awscli \
 && chmod 0755 /usr/local/bin/*.sh \
 && mkdir -p /backups && chown postgres:postgres /backups

# The postgres user of the base image (not root).
ENV AWS_CONFIG_FILE=/usr/local/etc/aws-config
USER postgres
VOLUME /backups
HEALTHCHECK --interval=5m --timeout=20s --start-period=10m --retries=1 CMD ["/usr/local/bin/backup-status.sh"]
ENTRYPOINT []
CMD ["/usr/local/bin/backup-scheduler.sh"]
