# Device Manager — Full Pakiet v2.4

Aktualny etap projektu: trwały system zarządzania urządzeniami z REST API, jawnym agentem telemetrycznym oraz rozdzielonym uwierzytelnianiem użytkowników i agentów.

## Funkcje v2.4
- SQLite + SQLAlchemy 2.x + Pydantic 2.x + Alembic,
- pełny CRUD urządzeń przez FastAPI,
- jawny agent: rejestracja, heartbeat, retry/backoff i lokalny bufor offline,
- użytkownicy API z rolami `admin`, `operator`, `read-only`,
- osobne tokeny maszynowe agentów,
- tokeny przechowywane w bazie wyłącznie jako SHA-256 hash,
- enrollment agentów chroniony sekretem z konfiguracji środowiska,
- audyt operacji bezpieczeństwa i mutacji,
- limit powtarzających się nieudanych prób uwierzytelnienia,
- CI: migracje, pełne testy i smoke CLI/API/agent/admin.

## Instalacja i baza
```bash
python -m pip install -r requirements.txt
alembic upgrade head
```

Domyślna baza to `device_manager.db`. Można ustawić `DEVICE_MANAGER_DB_PATH` albo pełny `DATABASE_URL`.

## Utworzenie użytkownika API
Po migracji utwórz użytkownika lokalnym CLI:
```bash
python -m device_manager.admin create-user --name admin --role admin
```

Dostępne role:
- `read-only` — odczyt urządzeń,
- `operator` — odczyt, tworzenie i aktualizacja,
- `admin` — pełny CRUD wraz z usuwaniem.

CLI pokazuje bearer token tylko przy utworzeniu. W bazie zapisywany jest wyłącznie jego SHA-256 hash. Token należy przechowywać poza repozytorium i poza logami.

## API
Uruchomienie:
```bash
python -m uvicorn device_manager.api:app --host 127.0.0.1 --port 8000
```

Publiczne endpointy diagnostyczne:
- `GET /health`
- `GET /ready`

Endpointy zarządzania wymagają `Authorization: Bearer <USER_TOKEN>`:
- `GET /devices`
- `GET /devices/{id}`
- `POST /devices`
- `PATCH /devices/{id}`
- `DELETE /devices/{id}`

OpenAPI: `/docs` i `/openapi.json`.

## Enrollment i agent
Serwer musi mieć ustawiony sekret enrollmentu, np.:
```bash
export DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN='wygenerowany-losowy-sekret'
```

Ten sekret służy tylko do rejestracji. Po poprawnym enrollment API wydaje agentowi osobny losowy token maszynowy; agent zapisuje go lokalnie i używa do heartbeatów.

Rejestracja:
```bash
DEVICE_MANAGER_AGENT_ENROLLMENT_TOKEN='...' \
python -m device_manager.agent register --api http://127.0.0.1:8000
```

Heartbeat:
```bash
python -m device_manager.agent heartbeat --api http://127.0.0.1:8000
```

Stan agenta trafia domyślnie do `~/.device-manager/`; katalog można zmienić przez `DEVICE_MANAGER_AGENT_STATE_DIR`. Agent nie instaluje ukrytej trwałości, nie wykonuje zdalnych poleceń i nie zbiera haseł, treści plików, MAC/IMEI.

## Testy
```bash
python -m unittest discover -s tests -v
```

## Architektura
- `device_manager/database.py` — engine/session,
- `device_manager/models.py` — urządzenia, principals, credentials, audit,
- `device_manager/repository.py` — persistence urządzeń,
- `device_manager/service.py` — logika urządzeń,
- `device_manager/auth.py` — tokeny, RBAC, credentiale agentów, audit,
- `device_manager/security.py` — limiter błędnych prób auth,
- `device_manager/api.py` — FastAPI i enforcement uprawnień,
- `device_manager/agent_service.py` — rejestracja/heartbeat,
- `device_manager/agent.py` — jawny agent telemetryczny,
- `device_manager/admin.py` — lokalny bootstrap użytkowników,
- `migrations/` — wersjonowany schemat bazy.

## Roadmapa
- Krok 1: baza i modele — CI PASS.
- Krok 2: service layer + FastAPI — CI PASS.
- Krok 3: agent urządzeń — CI PASS.
- Krok 4: auth, RBAC i bezpieczeństwo — aktualny checkpoint.
- Krok 5: panel zarządzania.

## Stan bezpieczeństwa
Krok 4 zapewnia podstawowe uwierzytelnianie, least-privilege RBAC, rozdzielenie tożsamości człowiek/agent, audyt i lokalny limiter. Dla wdrożenia wielowęzłowego limiter powinien dodatkowo działać na współdzielonej warstwie gateway/Redis, a TLS powinien być terminowany przed API. Pełny gate produkcyjny następuje dopiero po Kroku 5 i końcowym audycie.