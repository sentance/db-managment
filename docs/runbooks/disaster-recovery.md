# Аварийное восстановление

## Что нужно хранить вне сервера
| Что | Где лежит | Зачем |
|---|---|---|
| `deploy/secrets/master.key` | менеджер паролей, офлайн | расшифровка всех сохранённых паролей |
| дамп meta-DB | отдельное хранилище | конфигурация панели, роли, audit |
| `deploy/certs/ca.key` (+ crt) | можно не хранить | при утере сертификаты выпускаются заново |
| recovery-коды 2FA | офлайн | вход без аутентификатора |

## Резервная копия панели (до Phase 2 — вручную)
```bash
cd /srv/pgcp/deploy
docker compose exec -T meta pg_dump -U pgcp -Fc pgcp > pgcp-meta-$(date +%F).dump
sudo tar czf pgcp-config-$(date +%F).tgz .env certs/ /var/lib/pgcp/instances --exclude='*/superuser-password'
```
В Phase 2 это будет автоматизировано (дамп и конфиги в S3, мастер-ключ отдельно).

## Сценарии

### Control plane или meta-DB не стартуют
Managed-инстансы и приложения продолжают работать: они не зависят от панели.
1. `docker compose logs control-plane meta`
2. Если повреждена meta-DB: `docker compose stop control-plane`, пересоздайте том `pgcp-meta-data`, поднимите `meta`, затем `pg_restore -U pgcp -d pgcp --clean` из дампа и `docker compose up -d`.

### Сервер потерян целиком
1. Новый VPS → [install.md](install.md), но **положите старый `master.key`** в `deploy/secrets/` до первого запуска.
2. Восстановите дамп meta-DB (см. выше).
3. Каталоги `/var/lib/pgcp/instances/*` и тома данных восстанавливаются из бэкапов инстансов (Phase 2: pgBackRest → restore в новый инстанс).

### Утерян master.key
Сохранённые пароли не расшифровать. Данные в PostgreSQL не затронуты.
1. Сгенерируйте новый ключ (`openssl rand -base64 32 > secrets/master.key`).
2. Сбросьте метаданные секретов, иначе расшифровка будет падать: проще всего заново поднять панель с пустой meta-DB и снова зарегистрировать инстансы.
3. Для managed-инстансов задайте новые пароли `pgcp_admin`/`postgres` через `docker exec -u postgres pgcp-inst-<name> psql` (локально работает peer-аутентификация), затем выполните Rotate для ролей приложений.

### Потерян аутентификатор и recovery-коды
```bash
docker compose exec meta psql -U pgcp -d pgcp -c "delete from admin_user"
docker compose restart control-plane
docker exec pgcp-control-plane cat /var/lib/pgcp-cp/setup-token
```
Затем пройдите setup заново. Инстансы, роли и секреты сохраняются. Запись о сбросе останется в audit-логе только в виде события нового `auth.setup`. Сам сброс выполняется на сервере и требует shell-доступа: это сознательный «break glass».

### Managed-инстанс не поднимается
1. `docker logs pgcp-inst-<name>` или вкладка Logs в панели.
2. Конфигурация: `/var/lib/pgcp/instances/<name>/compose.yml`. Поднять вручную: `docker compose -f compose.yml up -d`.
3. Данные лежат в томе `pgcp-inst-<name>-data` и при удалении инстанса без флага «delete volume» сохраняются.
