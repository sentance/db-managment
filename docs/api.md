# API control plane

Полная машиночитаемая спецификация: [openapi.json](openapi.json). Её генерирует springdoc, файл обновляется тестом `ControlPlaneIT`. На работающей панели она доступна после входа: `/api/docs` и Swagger UI `/api/docs/ui`.

## Общие правила
- База: `/api`. JSON. Аутентификация — cookie-сессия `pgcp_session`.
- **CSRF**: для любых не-GET запросов передавайте значение cookie `XSRF-TOKEN` в заголовке `X-XSRF-TOKEN`. Cookie выставляется на первом GET.
- **Ошибки**: `{"code": "...", "message": "...", "fields": {...}|null}`.
  `UNAUTHENTICATED` 401 · `INVALID_CREDENTIALS` 401 · `FORBIDDEN` 403 · `STEP_UP_REQUIRED` 403 · `NOT_FOUND` 404 ·
  `CONFLICT` 409 (в том числе «на ресурсе уже идёт операция») · `RATE_LIMITED` 429 · `POSTGRES_ERROR` 502 · `UNAVAILABLE` 503 (agent недоступен).
- **Step-up**: при `STEP_UP_REQUIRED` вызовите `POST /api/auth/step-up {"code":"123456"}` и повторите запрос. Подтверждение действует 5 минут.
- **Jobs**: опасные операции отвечают `202 {"jobId": "..."}`. Статус и журнал — `GET /api/jobs/{id}`.
- Ответы с паролями отдаются только с `Cache-Control: no-store`.

## Аутентификация
| Метод | Путь | Описание |
|---|---|---|
| GET | `/auth/status` | `setupRequired`, `authenticated`, `stepUpValidUntil`, `recoveryCodesLeft` |
| POST | `/auth/setup` | `{token, username, password}` → `{totpSecret, otpauthUri}`; только пока нет администратора |
| POST | `/auth/setup/totp` | `{code}` → вход + 10 recovery-кодов (показываются один раз) |
| POST | `/auth/login` | `{username, password, code}`; `code` — TOTP или recovery-код `xxxxx-xxxxx` |
| POST | `/auth/logout` | |
| POST | `/auth/step-up` | `{code}` → `{validUntil}` |
| POST | `/auth/recovery-codes` | step-up; новые 10 кодов, старые аннулируются |

## Серверы и мониторинг
| Метод | Путь | Описание |
|---|---|---|
| GET | `/dashboard` | серверы с метриками, счётчики, предупреждения, последние jobs |
| GET | `/servers`, `/servers/{id}` | факты хоста (CPU, RAM, диски, ФС, Docker) и метрики |
| GET | `/servers/{id}/containers` | обнаруженные PostgreSQL-контейнеры (только чтение) |
| GET | `/instances/{id}/monitoring` | подключения, долгие запросы, блокировки, архиватор WAL, статистика по базам |
| GET | `/instances/{id}/monitoring/activity` | сессии `pg_stat_activity` |
| GET | `/instances/{id}/monitoring/locks` | ожидания блокировок |
| GET | `/settings/info` | public host, ссылка на SQL-редактор, поддерживаемые версии, allowlist параметров |
| GET | `/tls/instances-ca` | CA-сертификат managed-инстансов (для `verify-full`) |

## Инстансы
| Метод | Путь | Описание |
|---|---|---|
| GET | `/instances`, `/instances/{id}` | |
| POST | `/instances/managed` | создание (job). Тело: `name, pgMajor, memoryMb, cpus, protection, exposure (NONE\|LOCALHOST\|PUBLIC), hostPort, extraNetworks[], settings{}, initialDatabase{name, owner, createReadOnlyRole}` → `{instance, jobId, credentials[]}` |
| POST | `/instances/external` | регистрация существующего PG: `name, host, port, maintenanceDb, username, password, sslMode, mode, protection, containerName`; `MANAGE_OBJECTS` требует step-up |
| POST | `/instances/{id}/actions` | `{action: START\|STOP\|RESTART}` (job); STOP/RESTART для PRODUCTION требуют step-up |
| POST | `/instances/{id}/delete` | step-up; `{confirmName, deleteVolume}`. PRODUCTION запрещено. Для external — только снятие с учёта |
| PUT | `/instances/{id}/protection` | `{protection}`; ослабление PRODUCTION требует step-up |
| PUT | `/instances/{id}/external-mode` | step-up; `{mode}` |
| GET | `/instances/{id}/logs?tail=` | логи контейнера |
| GET | `/instances/settings-allowlist` | разрешённые параметры PostgreSQL и их типы |

## Базы
| Метод | Путь | Описание |
|---|---|---|
| GET | `/instances/{id}/databases` | размер, владелец, подключения, флаги managed/system |
| GET | `/instances/{id}/databases/{name}` | + расширения, роли с CONNECT |
| POST | `/instances/{id}/databases` | `{name, owner?, createReadOnlyRole}` (job) → пароли и connection string (один раз) |
| POST | `/instances/{id}/databases/{name}/rename` | `{newName}` (job; отказ при активных подключениях) |
| POST | `/instances/{id}/databases/{name}/drop` | step-up; `{confirmName, terminateConnections}` (job) |
| GET | `/instances/{id}/databases/{name}/connection?role=` | хост, порт, URI с маской пароля для каждого endpoint (Docker-сеть, host, public) |

## Роли
| Метод | Путь | Описание |
|---|---|---|
| GET | `/instances/{id}/roles` | роли, атрибуты, доступные базы, managed/locked/protected |
| POST | `/instances/{id}/roles` | `{name, grants:[{database, access: READ_ONLY\|READ_WRITE}]}` → пароль (один раз) |
| PUT | `/instances/{id}/roles/{role}/access` | `{database, access}`; `access: null` отзывает доступ |
| POST | `/instances/{id}/roles/{role}/rotate-password` | новый пароль (один раз) |
| POST | `/instances/{id}/roles/{role}/reveal-password` | step-up; сохранённый пароль + URI, запись в audit |
| PUT | `/instances/{id}/roles/{role}/lock` | `{locked}`: NOLOGIN + завершение сессий / LOGIN |
| GET | `/instances/{id}/roles/{role}/dependencies` | что мешает DROP ROLE (по базам) |
| POST | `/instances/{id}/roles/{role}/drop` | step-up; `{confirmName}` (job; отказ, если роль чем-то владеет) |

## Backup catalogue (Phase 2 milestone 1)

| Method | Path | Behaviour |
|---|---|---|
| GET | `/backups` | All active registered instances, including unknown/unconfigured coverage; cached snapshots and current evidence states |
| PUT | `/backups/sources/{instanceId}` | Step-up; `{containerName, containerId, stanza, staleAfterHours}` binds the registered PostgreSQL container's full immutable ID to an existing pgBackRest stanza |
| POST | `/backups/sources/{instanceId}/refresh` | Read existing metadata and archive statistics, persist a normalised snapshot, return updated evidence. A failed inspection returns evidence state with a redacted error and cached history |
| POST | `/backups/sources/{instanceId}/disconnect` | Step-up; remove the binding/snapshot only; 204 |

These operations do not grant `MANAGE_OBJECTS`, change source configuration, take
over schedules, or delete repository objects. Freshness and verification are
separate: this milestone always reports `NEVER_VERIFIED`. See the
[operator runbook](runbooks/existing-backups.md).

The agent endpoint is `POST /v1/backups/inspect {containerName, containerId, stanza}`
over existing mTLS. Its response is the parsed catalogue only, with numeric
repository status codes, cipher types, archive ranges and completed backup entries.
It accepts no arbitrary command, configuration path or tool options.

## Encrypted logical backups (Phase 2 milestone 2)

| Method | Path | Behaviour |
|---|---|---|
| GET | `/backups/destinations` | Public destination settings and connection-test state; no credentials or identity |
| POST | `/backups/destinations` | Step-up; `{name,endpoint,region,bucket,accessKey,secretKey}` → 201 `{destination,recoveryIdentity}`; identity shown once with `no-store` |
| POST | `/backups/destinations/{id}/test` | Step-up; 202 `{jobId}`; scoped test object write/read/delete |
| GET | `/backups/logical` | Logical backup history and verification timestamps |
| POST | `/backups/logical/{instanceId}` | Step-up; `{database,destinationId,containerId}` consents to one export → 202 `{id,jobId}` |
| POST | `/backups/logical/{backupId}/restore` | Step-up; `{instanceName,database,owner}` creates a fresh isolated destination → 202 `{id,jobId}` |
| GET | `/backups/restores` | Restore history, target instance IDs and verification results |

Agent mTLS endpoints: `POST /v1/logical-backups` submits a typed durable worker
request; `GET /v1/logical-backups/{id}` reconciles status/results; `POST
/v1/logical-backups/recovery-key` generates a key without journalling it. Secrets
travel only in the protected request and temporary credential file. See
[logical backups](runbooks/logical-backups.md) and [ADR 0007](adr/0007-logical-backup-encryption.md).

## Daily logical policies (Phase 2 milestone 3a)

| Method | Path | Behaviour |
|---|---|---|
| GET | `/backups/policies` | Credential-free desired settings, revision sync, observed execution and health |
| POST | `/backups/policies` | Step-up; `{name,instanceId,database,destinationId,containerId,dailyTime,timezone,enabled,runImmediately,maxAgeHours}` → 201 policy with pending sync |
| PUT | `/backups/policies/{id}` | Step-up; same body; new revision, explicit full-container-ID consent and refreshed credential cache; source database/instance/destination fixed |
| PUT | `/backups/policies/{id}/enabled` | Step-up; `{enabled}`; pause/resume requested revision; an active run finishes |

`dailyTime` is `HH:mm`; `timezone` is an IANA zone; `maxAgeHours` is 24–8760.
First-run opt-in applies only to policy creation. The agent owns execution and
uses one catch-up after downtime. Scheduled results appear in `/backups/logical`
with a null `jobId`. Health is unknown when observations are stale/unavailable.

Agent mTLS: `PUT /v1/backup-policies` accepts an export-only credential template
and increasing revision; its response and `GET /v1/backup-policies` contain status
only. `GET /v1/backup-policies/events` returns up to 100 durable completion events;
`DELETE /v1/backup-policies/events/{id}` acknowledges one committed import. No
arbitrary commands, paths, retention deletion or recovery identity are accepted.
See [backup schedules](runbooks/backup-schedules.md) for offline credentials,
pause semantics, restart guarantees and limits.

## Jobs и audit
| Метод | Путь | Описание |
|---|---|---|
| GET | `/jobs?status=&resourceKey=&limit=` | список |
| GET | `/jobs/{id}` | `{job, events[]}` |
| GET | `/audit?limit=&beforeId=` | журнал |
| GET | `/audit/verify` | проверка hash-цепочки |

## Agent API (внутренний, mTLS)
Контракт в `agent-api/`. Префикс `/v1`: `GET host/facts`, `GET host/metrics`, `GET containers?postgresOnly=`, `GET containers/{name}/logs`, `POST images/ensure`, `POST instances`, `GET instances/{name}`, `POST instances/{name}/lifecycle`, `POST instances/{name}/delete`, `GET operations/{id}`, `GET tls/instances-ca`. Health — `http://127.0.0.1:8081/actuator/health` внутри контейнера.
