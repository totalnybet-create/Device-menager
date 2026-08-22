# Device Manager — Full Pakiet v2.3

Aktualny etap projektu: trwały system zarządzania urządzeniami z REST API i jawnym agentem telemetrycznym.

## Funkcje v2.3
- trwała baza SQLite,
- SQLAlchemy 2.x ORM + repository CRUD,
- walidacja Pydantic 2.x,
- migracje Alembic upgrade/downgrade,
- warstwa biznesowa `DeviceService`,
- FastAPI: health, readiness i pełny CRUD urządzeń,
- agent urządzeń ze stałym UUID,
- idempotentna rejestracja agenta,
- heartbeat z `last_seen_at`,
- ograniczona telemetria: hostname, platforma, typ urządzenia i wersja agenta,
- retry z exponential backoff,
- pojedynczy lokalny bufor heartbeat przy utracie sieci,
- testy jednostkowe, persistence i integracyjne API/agenta,
- GitHub Actions: dependencies + compile + migrations + tests + CLI/API/agent smoke.

## Instalacja
```bash
python -m pip install -r requirements.txt
```

## Migracja bazy
```bash
alembic upgrade head
```

Domyślna baza to `device_manager.db`. Ścieżkę można zmienić przez `DEVICE_MANAGER_DB_PATH`, a pełny URL przez `DATABASE_URL`.

## CLI administratora
```bash
python app.py
```

## API
Najpierw wykonaj migrację bazy, następnie:
```bash
python -m uvicorn device_manager.api:app --host 127.0.0.1 --port 8000
```

Endpointy administratorskie:
- `GET /health`
- `GET /ready`
- `GET /devices`
- `GET /devices/{id}`
- `POST /devices`
- `PATCH /devices/{id}`
- `DELETE /devices/{id}`

Endpointy agenta:
- `POST /agents/register`
- `POST /agents/heartbeat`

Dokumentacja OpenAPI po uruchomieniu API: `/docs` oraz `/openapi.json`.

## Agent urządzenia
Rejestracja:
```bash
python -m device_manager.agent register --api http://127.0.0.1:8000
```

Heartbeat:
```bash
python -m device_manager.agent heartbeat --api http://127.0.0.1:8000
```

Domyślny stan agenta jest przechowywany jawnie w `~/.device-manager/`. Katalog można zmienić przez `DEVICE_MANAGER_AGENT_STATE_DIR`.

Agent nie instaluje ukrytej trwałości, nie wykonuje zdalnych poleceń i nie zbiera haseł, treści plików, MAC/IMEI ani innych danych tego typu. Raportuje tylko ograniczone informacje potrzebne do identyfikacji i stanu urządzenia.

## Testy
```bash
python -m unittest discover -s tests -v
```

## Architektura
- `device_manager/database.py` — engine i session factory,
- `device_manager/models.py` — modele ORM,
- `device_manager/schemas.py` — kontrakty i walidacja Pydantic,
- `device_manager/repository.py` — operacje danych,
- `device_manager/service.py` — logika biznesowa urządzeń,
- `device_manager/agent_service.py` — logika rejestracji i heartbeatów,
- `device_manager/api.py` — adapter HTTP FastAPI,
- `device_manager/agent.py` — jawny klient telemetryczny,
- `migrations/` — wersjonowany schemat bazy,
- `app.py` — lokalne CLI administratora.

## Ważne bezpieczeństwo
Krok 3 nadal nie zawiera uwierzytelniania i autoryzacji. API oraz endpointy agenta należy traktować jako lokalne/deweloperskie i nie wystawiać publicznie do Internetu. Osobne poświadczenia agentów, auth użytkowników, role i audyt bezpieczeństwa są zaplanowane w Kroku 4.

## Roadmapa
- Krok 1: trwała baza i modele — wykonany, CI PASS.
- Krok 2: service layer + FastAPI — wykonany, CI PASS.
- Krok 3: agent urządzeń — aktualny checkpoint.
- Krok 4: auth i uprawnienia.
- Krok 5: panel zarządzania.
