# Архитектура pgcp

## Цели и ограничения

- Личный self-hosted сервис, которому можно доверить production-данные.
- Существующие инсталляции PostgreSQL **никогда** не меняются автоматически.
- Собственный storage engine, механизм бэкапов и CoW не разрабатываются: используются pgBackRest, pg_dump и DBLab.
- Первая версия работает на одном VPS. Удалённые агенты добавляются позже без смены модели.

## Понятия

| Понятие | Что это | Где хранится |
|---|---|---|
| **Server** | VPS с management agent | таблица `server` |
| **Instance** | Кластер PostgreSQL. Физические бэкапы и PITR действуют на весь кластер | `instance` (`MANAGED` или `EXTERNAL`) |
| **Database** | База внутри инстанса | живой каталог PostgreSQL + `managed_database` для созданных pgcp |
| **Role** | Роль PostgreSQL | живой каталог + `managed_role` (зашифрованный пароль) |

Для важных проектов создавайте отдельный инстанс: у него независимый жизненный цикл, бэкапы и восстановление.

## Компоненты

```
 Browser ──HTTPS──► edge proxy (Caddy) ──► control-plane :8080 ──JDBC──► pgcp-meta (PostgreSQL 18)
                                              │      │
                                              │      └─ HTTPS + mTLS ──► agent :8443 ──► /var/run/docker.sock
                                              │                              └─ docker compose -p pgcp-inst-<name>
                                              └─ JDBC (сеть pgcp-instances / сети приложений) ──► PostgreSQL-инстансы
```

### control-plane
Spring Boot 4, Java 21, virtual threads, JdbcClient (без JPA), Flyway.
- **REST API** `/api/**` и встроенная SPA.
- **SQL-администрирование** кластеров выполняется по JDBC через `InstanceConnector`; DDL сосредоточен в `PgAdmin`. Это закреплено ArchUnit-тестами.
- **Jobs** — все потенциально опасные операции. Таблица `job` и журнал `job_event`, захват через `FOR UPDATE SKIP LOCKED`. Уникальный частичный индекс по `resource_key` допускает только одну активную операцию на инстанс, конфликтующая получает 409. Обработчики идемпотентны: после падения CP задача перезапускается до 3 раз.
- **Audit**: append-only (UPDATE/DELETE/TRUNCATE запрещены триггером), записи связаны SHA-256 hash-цепочкой, проверка через `GET /api/audit/verify`.
- **Доступа к Docker нет** (закреплено ArchUnit).

### agent
Единственный компонент с Docker socket. Работает от root внутри своего контейнера.
- Типизированный API (см. `agent-api`): host facts и метрики, обнаружение PG-контейнеров, создание, start/stop/restart, удаление managed-инстанса, логи. **Нет endpoint'ов, принимающих команду или путь.**
- Инварианты внутри agent, не зависящие от CP:
  - lifecycle-операции и `exec` разрешены только контейнерам с `pgcp.managed=true` и совпадающим `pgcp.instance-id`;
  - чужой контейнер с тем же именем блокирует создание;
  - том удаляется только при совпадении меток.
- Каждый инстанс — отдельный compose-проект в `/var/lib/pgcp/instances/<name>/compose.yml`. Если панель и agent недоступны, его можно поднять вручную командой `docker compose up -d`. Используется `restart: unless-stopped`.
- Внешние программы запускаются только через `ProcessBuilder` с фиксированным argv, без shell.
- Долгие операции идемпотентны по `operationId` и журналируются на диск. CP вычисляет `operationId` детерминированно из id job, поэтому повтор job подхватывает ту же операцию.

### Managed PostgreSQL
- Образ `pgcp-postgres:<major>-<hash>`: официальный `postgres:<major>-trixie` (glibc) + pgBackRest из apt.postgresql.org. Тег содержит хэш build context, поэтому изменение Dockerfile не подменяет образ работающих инстансов.
- `initdb --data-checksums`. pg_hba: `peer` для локального `postgres`, остальное только `scram-sha-256`; при публикации порта наружу — только `hostssl`.
- TLS всегда включён. Сертификат выпускает внутренний CA agent'а, CA можно скачать для `sslmode=verify-full`.
- Пароль суперпользователя нужен только для initdb. После первого healthy-старта agent заменяет его на диске заглушкой.
- Рутинные операции CP выполняет от `pgcp_admin`: CREATEDB, CREATEROLE, pg_monitor, pg_signal_backend, без superuser. Суперпользователь используется точечно, например для исправления схемы public в PG14.
- Параметры PostgreSQL берутся из allowlist с типами. Пресеты рассчитываются от лимита памяти.
- Сеть по умолчанию — только `pgcp-instances`. Опционально подключаются сети приложений (например `coolify`), публикация на `127.0.0.1` или публичный порт.

### External-инстансы
- Регистрация выполняет только чтение: проверку подключения и `version()`.
- `MONITOR_ONLY` (по умолчанию) — только чтение. `MANAGE_OBJECTS` (включается со step-up) разрешает создавать базы и роли.
- Никогда не выполняются: рестарт, `ALTER SYSTEM`, изменение `archive_command` или pg_hba, обновление версии.
- По умолчанию защита `PRODUCTION`.

## Безопасность

| Требование | Реализация |
|---|---|
| Вход администратора | Argon2id + **обязательный TOTP** (RFC 6238, защита от повторного использования кода) + 10 одноразовых recovery-кодов |
| Первая настройка | одноразовый токен в файле 0600 внутри контейнера; в лог пишется только путь |
| Сессии | HttpOnly, Secure, SameSite=Strict cookie; CSRF (cookie → `X-XSRF-TOKEN`); смена id сессии при входе |
| Перебор | 5 неудачных попыток за 15 минут на IP или пользователя → 429 |
| Опасные действия | **step-up**: свежий TOTP на 5 минут для reveal пароля, DROP, удаления, ослабления защиты, MANAGE_OBJECTS |
| Секреты | AES-256-GCM, назначение секрета привязано как AAD. Мастер-ключ в файле вне БД (docker secret); id ключа хранится у каждого шифротекста для ротации |
| Пароли ролей | отправляются в PostgreSQL как SCRAM-верификатор (`PGConnection.alterUserPassword`): plaintext не попадает в логи сервера и `pg_stat_statements` |
| Показ паролей | только в ответе на создание или ротацию и через reveal со step-up, всегда с `Cache-Control: no-store`; в URL, логах и audit их нет (проверяется тестами) |
| SQL-инъекции | идентификаторы проходят через `PgIdent.quote`, новые имена — по строгому regex, значения передаются bind-параметрами |
| Сеть | agent и meta-DB находятся во `internal`-сети без выхода наружу; CP ↔ agent через mTLS с проверкой CN; панель публикуется только на loopback или bridge-адресе за HTTPS-прокси |
| Заголовки | CSP `default-src 'self'`, `frame-ancestors 'none'`, `Referrer-Policy: no-referrer` |
| Production | защита PRODUCTION: удаление запрещено, остальные действия требуют step-up; DROP DATABASE — ввод имени + step-up + отказ при активных подключениях без явного флага |

## Ресурсы

Бюджет памяти на tgok: meta-DB около 150 МБ (лимит 256), control plane около 400 МБ (`-Xmx256m`, лимит 512), agent около 200 МБ (`-Xmx160m`, лимит 320). Перед созданием инстанса agent проверяет MemAvailable с запасом и свободный диск.

## Что будет на следующих этапах

- **Phase 2**: в managed-образе уже есть pgBackRest. Agent будет генерировать `pgbackrest.conf` с секретами через env-файл 0600 и `repo-cipher-type=aes-256-cbc`, держать локальный планировщик (бэкапы идут при недоступной панели) и outbox результатов. Логические дампы будут создаваться pg_dump в эфемерных контейнерах. Restore — только в новую базу или новый инстанс; у восстановленного инстанса архивация WAL отключена, чтобы он не писал в stanza источника. Restore-verify — в образе той же libc и major, что у источника. Telegram-алерты.
- **Phase 3**: интерфейс `BranchProvider` с реализацией `LogicalCloneProvider`.
- **Phase 4**: `DblabCloneProvider` с проверкой возможностей хоста (ZFS).
