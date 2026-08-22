# Device Manager — Full Pakiet v2.2

Aktualny etap projektu: trwały rdzeń zarządzania urządzeniami z warstwą usługową i REST API FastAPI.

## Funkcje v2.2
- trwała baza SQLite,
- SQLAlchemy 2.x ORM + repository CRUD,
- walidacja Pydantic 2.x,
- migracje Alembic upgrade/downgrade,
- warstwa biznesowa `DeviceService`,
- FastAPI: health, readiness i pełny CRUD urządzeń,
- testy jednostkowe, persistence i integracyjne API,
- GitHub Actions: dependencies + compile + migrations + tests + CLI/API smoke.

## Instalacja
```bash
python -m pip install -r requirements.txt
```

## Migracja bazy
```bash
alembic upgrade head
```

Domyślna baza to `device_manager.db`. Ścieżkę można zmienić przez `DEVICE_MANAGER_DB_PATH`, a pełny URL przez `DATABASE_URL`.

## CLI
```bash
python app.py
```

## API
Najpierw wykonaj migrację bazy, następnie:
```bash
python -m uvicorn device_manager.api:app --host 127.0.0.1 --port 8000
```

Endpointy:
- `GET /health`
- `GET /ready`
- `GET /devices`
- `GET /devices/{id}`
- `POST /devices`
- `PATCH /devices/{id}`
- `DELETE /devices/{id}`

Dokumentacja OpenAPI po uruchomieniu API: `/docs` oraz `/openapi.json`.

## Testy
```bash
python -m unittest discover -s tests -v
```

## Architektura
- `device_manager/database.py` — engine i session factory,
- `device_manager/models.py` — modele ORM,
- `device_manager/schemas.py` — kontrakty i walidacja Pydantic,
- `device_manager/repository.py` — operacje danych,
- `device_manager/service.py` — reguły biznesowe i błędy domenowe,
- `device_manager/api.py` — adapter HTTP FastAPI,
- `migrations/` — wersjonowany schemat bazy,
- `app.py` — CLI korzystające z trwałej warstwy danych.

## Ważne bezpieczeństwo
Krok 2 celowo nie zawiera jeszcze uwierzytelniania i autoryzacji. API należy traktować jako lokalne/deweloperskie i nie wystawiać publicznie do Internetu. Auth, role i osobne poświadczenia agentów są zaplanowane w Kroku 4.

## Roadmapa
- Krok 1: trwała baza i modele — wykonany, CI PASS.
- Krok 2: service layer + FastAPI — aktualny checkpoint.
- Krok 3: agent urządzeń.
- Krok 4: auth i uprawnienia.
- Krok 5: panel zarządzania.
