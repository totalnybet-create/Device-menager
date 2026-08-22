# Device Manager — deployment produkcyjny

## Zalecany profil
- PostgreSQL 16+ przez `DATABASE_URL`.
- Kontener `Dockerfile`, uruchamiany jako użytkownik non-root.
- TLS terminowany na reverse proxy / load balancerze przed aplikacją.
- Sekrety dostarczane przez secret manager / zmienne środowiskowe, nigdy przez repozytorium.
- Wiele replik: jedna kontrolowana migracja, następnie repliki z `RUN_MIGRATIONS=0`.

## Wymagane sekrety i konfiguracja
```bash
DATABASE_URL='postgresql+psycopg://USER:PASSWORD@HOST:5432/device_manager'
DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN='LOSOWY_DLUGI_SEKRET'
```

Opcjonalnie:
```bash
HOST='0.0.0.0'
PORT='8000'
RUN_MIGRATIONS='1'
FORWARDED_ALLOW_IPS='127.0.0.1'
```

`FORWARDED_ALLOW_IPS` należy rozszerzać wyłącznie o znane adresy zaufanego reverse proxy.

## Build
```bash
docker build -t device-manager:2.5 .
```

## Jedna instancja
```bash
docker run --rm -p 8000:8000 \
  -e DATABASE_URL="$DATABASE_URL" \
  -e DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN="$DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN" \
  device-manager:2.5
```

## Wiele replik
Najpierw wykonaj migrację dokładnie raz:
```bash
docker run --rm \
  -e DATABASE_URL="$DATABASE_URL" \
  device-manager:2.5 alembic upgrade head
```

Następnie repliki aplikacji uruchamiaj z:
```bash
RUN_MIGRATIONS=0
```

## Health checks
- liveness: `GET /health`
- readiness / DB: `GET /ready`

## Bootstrap użytkownika
Po migracji utwórz konto administratorskie w kontrolowanym środowisku:
```bash
DATABASE_URL="$DATABASE_URL" \
python -m device_manager.admin create-user --name admin --role admin
```

Token jest wyświetlany tylko raz. W bazie znajduje się wyłącznie jego SHA-256 hash.

## Reverse proxy / TLS
Publiczny ruch musi trafiać przez HTTPS. Nie wystawiaj bezpośrednio portu Uvicorn do Internetu. Proxy powinno:
- wymuszać TLS,
- ograniczać rozmiar requestów,
- stosować współdzielony rate limiting,
- przekazywać nagłówki proxy wyłącznie z zaufanej warstwy,
- zbierać logi bez bearer tokenów i enrollment secretów.

## Backup i rollback
- PostgreSQL: automatyczne backupy + test odtworzenia.
- Przed migracją produkcyjną wykonaj backup/snapshot.
- Kod można cofnąć do poprzedniego obrazu kontenera.
- Schemat posiada migracje Alembic `upgrade`/`downgrade`; downgrade uruchamiaj świadomie po ocenie utraty danych.

## Ograniczenia świadome
Wbudowany limiter auth jest per-process. Przy wielu replikach obowiązkowy jest dodatkowy limiter na gateway/shared store. SQLite pozostaje dobrym trybem lokalnym, ale docelowy deployment wieloużytkownikowy powinien korzystać z PostgreSQL.
