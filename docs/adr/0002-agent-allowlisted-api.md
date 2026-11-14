# ADR 0002: Management agent на Java с API из разрешённых операций

**Статус:** принято, 2026-10-21

## Решение
- Agent — отдельный процесс (Java 21, Spring Boot, так выбрал пользователь, стек единый с backend). Это единственный владелец Docker socket.
- API agent'а состоит только из типизированных операций. Нет endpoint'ов «выполнить команду» или «прочитать файл».
- Внешние программы (`docker compose`, `docker build`) запускаются с фиксированным argv без shell, только с валидированными значениями.
- Инварианты проверяются **в agent**, а не только в CP: менять можно только контейнеры с `pgcp.managed=true` и совпадающим `pgcp.instance-id`.
- CP ↔ agent: HTTPS с mTLS (собственный CA из `install.sh`) и проверкой CN клиента. Agent находится в `internal`-сети без публикации портов.

## Phase 2: informational access to existing backup repositories

`POST /v1/backups/inspect` is a separate, narrowly typed capability for a source
explicitly connected in the panel with step-up. It accepts only the PostgreSQL
container name, its full immutable ID and a validated existing stanza name. The
agent verifies that the name still resolves to that ID, verifies it is a running
PostgreSQL container, and executes a fixed, time-limited pgBackRest `info` command
as the PostgreSQL OS user. The command suppresses file logging and raw diagnostics
are discarded. Only allowlisted, parsed metadata is returned.

Generic `execFixed`, lifecycle, configuration and volume mutation still require a
pgcp-managed target. Backup inspection does not enable these operations on external
sources. See [ADR 0006](0006-backup-and-recovery-boundaries.md).

## Альтернативы
- Go-agent: меньше по памяти, но пользователь выбрал единый стек.
- Docker socket proxy (Tecnativa и подобные): ограничивает endpoint'ы, но не семантику («только наши контейнеры»).
- Прямой доступ CP к Docker: противоречит ТЗ.

## Последствия
- Удалённые агенты (Phase 2+) будут использовать тот же контракт и mTLS. Для SQL-доступа к удалённым инстансам `InstanceConnector` получит реализацию через туннель.
