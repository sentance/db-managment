# ADR 0003: Отдельный compose-проект на каждый managed-инстанс

**Статус:** принято, 2026-10-21

## Решение
Agent генерирует `/var/lib/pgcp/instances/<name>/compose.yml` (вместе с `config/`) и управляет инстансом через `docker compose -p pgcp-inst-<name>`. Каталог монтируется в agent **по тому же пути**, что и на хосте, потому что пути в compose-файле разрешает Docker daemon.

## Почему
- Инстанс переживает потерю панели и agent'а: `restart: unless-stopped`, а при необходимости его можно поднять вручную командой `docker compose up -d`.
- Конфигурация инстанса читаема и видна в diff.
- Именованные тома с метками `pgcp.managed` и `pgcp.instance-id` защищают от удаления чужих данных.

## Детали
- Тег образа `pgcp-postgres:<major>-<hash контекста>`: при изменении Dockerfile работающие инстансы не меняют образ неявно.
- Точка монтирования данных совпадает с `VOLUME` официального образа: `/var/lib/postgresql/data` до PG17 и `/var/lib/postgresql` начиная с PG18.
- Обёртка `pgcp-entrypoint.sh` копирует TLS-ключ, pg_hba и файл пароля с правами, которых требует PostgreSQL, а затем вызывает официальный entrypoint без изменений.
