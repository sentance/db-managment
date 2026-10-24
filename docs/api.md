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

## Jobs и audit
| Метод | Путь | Описание |
|---|---|---|
| GET | `/jobs?status=&resourceKey=&limit=` | список |
| GET | `/jobs/{id}` | `{job, events[]}` |
| GET | `/audit?limit=&beforeId=` | журнал |
| GET | `/audit/verify` | проверка hash-цепочки |

## Agent API (внутренний, mTLS)
Контракт в `agent-api/`. Префикс `/v1`: `GET host/facts`, `GET host/metrics`, `GET containers?postgresOnly=`, `GET containers/{name}/logs`, `POST images/ensure`, `POST instances`, `GET instances/{name}`, `POST instances/{name}/lifecycle`, `POST instances/{name}/delete`, `GET operations/{id}`, `GET tls/instances-ca`. Health — `http://127.0.0.1:8081/actuator/health` внутри контейнера.
