# Device Manager — Full Pakiet v2.5

Aktualny etap projektu: pełny checkpoint funkcjonalny — trwała baza, REST API, jawny agent telemetryczny, auth/RBAC oraz responsywny panel operatorski.

## Funkcje v2.5
- SQLite + SQLAlchemy 2.x + Pydantic 2.x + Alembic,
- pełny CRUD urządzeń przez FastAPI,
- jawny agent: rejestracja, heartbeat, retry/backoff i lokalny bufor offline,
- role `admin`, `operator`, `read-only`,
- osobne tokeny użytkowników i agentów; w DB wyłącznie SHA-256 hash,
- enrollment agentów chroniony sekretem środowiskowym,
- audyt operacji i limiter błędnych prób auth,
- panel webowy: lista, wyszukiwanie, filtr statusu, statystyki, create/edit/delete zgodnie z rolą,
- loading/error/empty states, mobile layout i podstawowa accessibility,
- panel bez zewnętrznych bibliotek/CDN, z CSP i security headers,
- CI: migracje, pełne testy, JavaScript syntax check i smoke CLI/API/panel/agent/admin.

## Instalacja i baza
```bash
python -m pip install -r requirements.txt
alembic upgrade head
```

Domyślna baza to `device_manager.db`. Można ustawić `DEVICE_MANAGER_DB_PATH` albo pełny `DATABASE_URL`.

## Utworzenie użytkownika
```bash
python -m device_manager.admin create-user --name admin --role admin
```

Role:
- `read-only` — odczyt,
- `operator` — odczyt, tworzenie i aktualizacja,
- `admin` — pełny CRUD.

Token jest pokazany tylko przy utworzeniu. W bazie znajduje się wyłącznie jego hash.

## Uruchomienie API i panelu
```bash
python -m uvicorn device_manager.api:app --host 127.0.0.1 --port 8000
```

Panel: `/panel`.

Panel prosi o bearer token użytkownika i trzyma go tylko w pamięci bieżącej karty — nie zapisuje go do `localStorage`, `sessionStorage` ani cookie. Wszystkie operacje panelu przechodzą przez ten sam backendowy RBAC co bezpośrednie API.

Publiczne endpointy diagnostyczne:
- `GET /health`
- `GET /ready`

Profil zalogowanego użytkownika:
- `GET /me`

Endpointy zarządzania:
- `GET /devices`
- `GET /devices/{id}`
- `POST /devices`
- `PATCH /devices/{id}`
- `DELETE /devices/{id}`

OpenAPI: `/docs` i `/openapi.json`.

## Enrollment i agent
Na serwerze ustaw sekret enrollmentu:
```bash
export DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN='wygenerowany-losowy-sekret'
```

Rejestracja:
```bash
DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN='...' \
python -m device_manager.agent register --api http://127.0.0.1:8000
```

Heartbeat:
```bash
python -m device_manager.agent heartbeat --api http://127.0.0.1:8000
```

Agent przechowuje lokalny stan domyślnie w `~/.device-manager/`. Nie instaluje ukrytej trwałości, nie wykonuje zdalnych poleceń i nie zbiera haseł, treści plików, MAC/IMEI.

## Testy
```bash
python -m unittest discover -s tests -v
node --check device_manager/panel/panel.js
```

## Architektura
- `device_manager/database.py` — engine/session,
- `device_manager/models.py` — devices/principals/credentials/audit,
- `device_manager/repository.py` — persistence,
- `device_manager/service.py` — logika urządzeń,
- `device_manager/auth.py` — tokeny/RBAC/audit,
- `device_manager/security.py` — limiter auth,
- `device_manager/api.py` — FastAPI, RBAC i hosting panelu,
- `device_manager/panel/` — HTML/CSS/JS panelu bez third-party runtime,
- `device_manager/agent_service.py` — rejestracja/heartbeat,
- `device_manager/agent.py` — agent telemetryczny,
- `device_manager/admin.py` — bootstrap użytkowników,
- `migrations/` — wersjonowany schemat.

## Roadmapa
- Krok 1: baza i modele — CI PASS.
- Krok 2: service layer + FastAPI — CI PASS.
- Krok 3: agent — CI PASS.
- Krok 4: auth/RBAC/security — CI PASS.
- Krok 5: panel zarządzania — aktualny checkpoint.

## Stan przed finalnym gate
Po Kroku 5 wykonujemy końcowy audyt produkcyjny: pełna regresja, migracje/rollback, bezpieczeństwo, responsywność, accessibility, performance, error handling i deployment smoke. Multi-worker production wymaga współdzielonego rate limitera/gateway oraz TLS przed API.