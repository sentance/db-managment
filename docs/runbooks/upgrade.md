# Обновление pgcp

```bash
cd /srv/pgcp && git pull
cd deploy && docker compose up -d --build
```
- Миграции metadata DB (Flyway) применяются при старте control plane. Перед обновлением сделайте дамп meta-DB (см. disaster-recovery.md, раздел «Резервная копия панели»).
- Managed-инстансы при обновлении pgcp **не перезапускаются**. Если изменился Dockerfile образа PostgreSQL, новый тег (`pgcp-postgres:<major>-<hash>`) используется только для новых инстансов.
- Обновление pgBackRest: поменяйте `PGBACKREST_VERSION` в `images/postgres/Dockerfile` после проверки на тестовом инстансе.

## Откат
`git checkout <предыдущий тег> && docker compose up -d --build`. Если новая версия применила миграцию, сначала восстановите дамп meta-DB, сделанный перед обновлением.
