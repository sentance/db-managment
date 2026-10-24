# Agent недоступен

Признаки: на Dashboard «management agent unreachable», jobs создания и lifecycle падают с `UNAVAILABLE`.

**Что продолжает работать:** все managed-инстансы и приложения. Agent не находится на пути данных.

1. `docker compose ps agent` и `docker compose logs --tail=200 agent`
2. Частые причины:
   - нет доступа к `/var/run/docker.sock` (Docker перезапускался) → `docker compose restart agent`;
   - истёк срок или изменился сертификат mTLS → проверьте `certs/` (срок 825 дней). Перевыпуск: удалите `certs/agent.*` и `certs/control-plane.*`, запустите `./install.sh`, затем `docker compose up -d`;
   - нехватка памяти (`ExitOnOutOfMemoryError`) → увеличьте `mem_limit` и `-Xmx` в compose.
3. Прерванные операции: при рестарте agent незавершённые операции помечаются как failed («interrupted»). Job в CP завершится ошибкой. Создание инстанса безопасно повторить с тем же именем: agent продолжит с того места, где остановился. Перед этим проверьте `docker ps -a | grep pgcp-inst`.
4. Ручное управление инстансом без agent: `cd /var/lib/pgcp/instances/<name> && docker compose up -d` (или `stop`).
