# pgcp — PostgreSQL Control Plane

Self-hosted панель для управления PostgreSQL на собственных VPS с UX в духе Neon: managed-инстансы в Docker, базы, роли и права, connection strings, мониторинг. На следующих этапах добавятся бэкапы и PITR (pgBackRest), восстановление и ветки баз.

Принцип проекта — **переиспользовать надёжные инструменты, а не писать свои**: PostgreSQL из официальных образов, pgBackRest для физических бэкапов, pg_dump/pg_restore для логических, DBLab Engine для copy-on-write веток. Обоснование — в [docs/architecture.md](docs/architecture.md) и [docs/adr/](docs/adr/).

## Статус

| Этап | Содержание | Статус |
|---|---|---|
| Phase 0 | Исследование, архитектура, инвентаризация VPS | ✅ [docs/vps-inventory-tgok.md](docs/vps-inventory-tgok.md) |
| Phase 1 | Auth + 2FA, managed-инстансы, базы, роли, connection strings, external-инстансы, jobs, audit, мониторинг | ✅ |
| Phase 2 | pgBackRest, логические бэкапы, S3/R2, расписания, PITR, restore, проверка восстановления, Telegram | следующий |
| Phase 3 | Ветки через pg_dump/pg_restore, TTL | — |
| Phase 4 | Fast branching через DBLab (нужен ZFS) | — |

## Компоненты

```
web/            Vue 3 + TypeScript + PrimeVue — собирается в static-ресурсы control plane
control-plane/  Java 21 / Spring Boot 4 — REST API, auth, jobs, audit, шифрование секретов, SQL-администрирование
agent/          Java 21 / Spring Boot 4 — единственный компонент с доступом к Docker; API только из разрешённых операций
agent-api/      общий контракт CP ↔ agent
images/postgres Dockerfile managed-образа: официальный postgres + pgBackRest из apt.postgresql.org
deploy/         compose.yml, install.sh, smoke-test.sh, .env.example
docs/           архитектура, ADR, API, runbooks
```

## Быстрый старт (локально или на VPS)

```bash
cd deploy
./install.sh                       # секреты, mTLS-сертификаты, .env (ничего вне deploy/ не трогает)
docker compose up -d --build
docker exec pgcp-control-plane cat /var/lib/pgcp-cp/setup-token
```

Панель слушает `127.0.0.1:8480`. Перед ней нужен HTTPS reverse proxy, подробности в [docs/runbooks/install.md](docs/runbooks/install.md). Для проверки по plain HTTP на localhost поставьте `PGCP_COOKIE_SECURE=false` в `deploy/.env`.

Сквозная проверка установки: `deploy/smoke-test.sh --cleanup`. Скрипт создаёт инстанс и базу, подключается по TLS, перезапускает стек, проверяет сохранность данных, а затем удаляет инстанс.

## Разработка

```bash
./gradlew check                         # unit + интеграционные тесты (нужен Docker: Testcontainers)
./gradlew :agent:test -PdockerTests     # + тесты agent на реальном Docker
cd web && npm ci && npm run dev         # UI на :5173, проксирует /api на :8080
cd web && npm test                      # vitest
```

Документация: [архитектура](docs/architecture.md) · [API](docs/api.md) · [установка](docs/runbooks/install.md) · [обновление](docs/runbooks/upgrade.md) · [аварийное восстановление](docs/runbooks/disaster-recovery.md) · [agent недоступен](docs/runbooks/agent-down.md)
