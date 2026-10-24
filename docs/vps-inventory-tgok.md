# Инвентаризация VPS `tgok` (Phase 0)

Снята **только чтением** 2026-10-21 по SSH под пользователем `deploy`. На сервере ничего не менялось.

## Хост
- Ubuntu 24.04.5 LTS, ядро 6.8.0-147
- 6 vCPU, 11 GiB RAM: занято около 7 GiB, доступно около 4.7 GiB, **swap 2.5 из 4 GiB занят**
- Диск: `/dev/sda1` ext4 199 GB, свободно 139 GB. ZFS нет, LVM на корне нет
- Docker 29.4.1, Compose v5.1.3

## Что уже работает
- **Coolify 4.4.6** управляет большинством контейнеров (`coolify.managed=true`, конфиги в `/data/coolify/...`).
- **Caddy 2.11.2** (`edge-proxy-caddy-1`) занимает 80/443, конфиг в `/srv/edge-proxy/config`. Сервисы публикуются на `172.18.0.1:<port>`, Caddy проксирует на них.
- Мониторинг: Prometheus, Grafana, node-exporter, cAdvisor, postgres-exporter.
- CloudBeaver (SQL UI), pgbackweb (логические бэкапы).

## PostgreSQL

| Контейнер | Версия | Образ (libc) | Базы | Бэкапы |
|---|---|---|---|---|
| `shared-postgres-ucwxemohue3uevnevpa63hiq` | 16.14 | `postgres-pgbackrest:16` (Alpine/musl) | intelsmm 4 GB, tgcatalog 1.6 GB, cherga, reklama, seo_audit, downloader, nuked | pgBackRest 2.57, stanza `shared`, R2, full по воскресеньям + diff ежедневно, WAL archive работает, **cipher: none** |
| `w1jqg0toweyovks39f8j6yfa` | 18.4 | `postgres:18-alpine` | task_db | нет, archive_mode=off |
| `coolify-db` | 15.19 | `postgres:15-alpine` | coolify | нет |
| `pgbackweb-db-…` | 17.10 | `postgres` | pgbackweb | нет |

## Выводы для pgcp
1. Стек pgcp ставится **вне Coolify** (`/srv/pgcp`), чтобы Coolify не пересоздавал контейнеры.
2. Свой Caddy не ставится: в существующий Caddy добавляется site block. Это делается **только после отдельного разрешения**.
3. Существующие PG регистрируются как External `MONITOR_ONLY` с защитой `PRODUCTION`. Для доступа CP подключается к сети `coolify` (меняется только контейнер pgcp).
4. Все существующие инстансы собраны на Alpine. Физический restore stanza `shared` возможен **только в Alpine-образ PG 16** (collations musl ≠ glibc). Это будет проверяться в Phase 2.
5. Рекомендация вне pgcp: зашифровать репозиторий pgBackRest (новый repo2 с `repo2-cipher-type`). Выполняется вручную.
6. Fast branching (DBLab) на tgok недоступен без отдельного блочного тома под ZFS-пул.
7. Памяти мало: инстансы по умолчанию создаются с лимитом 512–1024 МБ, перед созданием проверяется MemAvailable.
