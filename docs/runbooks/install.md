# Установка

## Требования
Linux, Docker 24+ с Compose v2, openssl, около 1 ГБ свободной RAM для самой панели плюс память под инстансы.

## Шаги
```bash
git clone <repo> /srv/pgcp && cd /srv/pgcp/deploy
./install.sh
```
`install.sh` создаёт:
- `secrets/master.key` — **сразу сохраните копию офлайн** (менеджер паролей). Без него сохранённые пароли не расшифровать;
- `secrets/meta_password` — пароль metadata DB;
- `certs/` — внутренний CA и mTLS-сертификаты agent и control plane;
- `.env` из `.env.example`.

Проверьте `.env`:
- `PGCP_PANEL_BIND` / `PGCP_PANEL_PORT` — где слушает панель. **Не 0.0.0.0**: только loopback или bridge-адрес за HTTPS-прокси;
- `PGCP_PUBLIC_HOST` — имя хоста для инстансов с публичным портом (попадает в их TLS-сертификаты);
- `PGCP_SQL_EDITOR_URL` — ссылка на CloudBeaver или pgAdmin.

```bash
docker compose up -d --build
docker compose ps                                   # все три сервиса healthy
docker exec pgcp-control-plane cat /var/lib/pgcp-cp/setup-token
```
Откройте панель через HTTPS, введите токен, задайте пароль, отсканируйте QR-код в приложении-аутентификаторе и сохраните recovery-коды. После первой настройки токен удаляется.

Проверка установки: `./smoke-test.sh --cleanup`. На свежей установке скрипт сам выполнит setup и сохранит учётные данные тестового администратора в файл 0600. Для боевой панели запускайте его с `PGCP_ADMIN_USER`, `PGCP_ADMIN_PASSWORD`, `PGCP_TOTP_SECRET` или проверяйте вручную по тем же шагам.

## Особенности tgok (Coolify + edge Caddy)
**Каждый шаг ниже меняет общий сервер. Выполняйте их только осознанно и по одному.**

1. Ставьте в `/srv/pgcp` **вне Coolify**: Coolify не должен управлять контейнерами pgcp.
2. В `.env`: `PGCP_PANEL_BIND=172.18.0.1` (адрес bridge, через который Caddy уже ходит к другим сервисам) и свободный порт, например `PGCP_PANEL_PORT=3170`.
3. Доступ к существующим PG (external) идёт через сеть `coolify`: `cp examples/compose.override.coolify.yml compose.override.yml`. Изменится только контейнер pgcp, контейнеры Coolify остаются как есть.
4. Добавьте site block из `examples/pgcp.caddy.example` в `/srv/edge-proxy/config` и перезагрузите Caddy (`caddy reload`). Это изменение общего прокси.
5. Регистрируйте существующие базы как External `MONITOR_ONLY`, лучше под отдельной ролью с `pg_monitor` (см. ADR 0005).

## После перезагрузки VPS
Все контейнеры имеют `restart: unless-stopped`: панель, agent, meta-DB и managed-инстансы поднимаются сами. Проверка: `docker compose ps` и Dashboard.
