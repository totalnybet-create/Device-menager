# Device Manager — Full Pakiet v2.1

Aktualny etap projektu: trwały rdzeń zarządzania urządzeniami z SQLite, SQLAlchemy 2.x, Pydantic 2.x i migracjami Alembic.

## Funkcje v2.1
- lista urządzeń,
- dodawanie urządzeń,
- usuwanie urządzeń,
- zmiana statusu,
- trwałość danych po restarcie procesu,
- SQLAlchemy ORM i odseparowane repository CRUD,
- walidacja Pydantic,
- migracje Alembic upgrade/downgrade,
- testy jednostkowe i persistence tests,
- GitHub Actions: dependencies + compile + migrations + tests + smoke run.

## Instalacja
```bash
python -m pip install -r requirements.txt
```

## Migracja bazy
```bash
alembic upgrade head
```

Domyślna baza to `device_manager.db`. Ścieżkę można zmienić przez `DEVICE_MANAGER_DB_PATH`, a pełny URL przez `DATABASE_URL`.

## Uruchomienie
```bash
python app.py
```

## Testy
```bash
python -m unittest discover -s tests -v
```

## Architektura
- `device_manager/database.py` — engine i session factory,
- `device_manager/models.py` — modele ORM,
- `device_manager/schemas.py` — kontrakty i walidacja Pydantic,
- `device_manager/repository.py` — operacje danych niezależne od CLI/API,
- `migrations/` — wersjonowany schemat bazy,
- `app.py` — obecna warstwa wejściowa CLI/demo korzystająca z repository.

## Stan produkcyjny
Krok 1 (trwała baza i modele) jest zaimplementowany na branchu roboczym i wymaga potwierdzenia CI przed scaleniem. Kolejne warstwy — FastAPI, agent urządzeń, auth/uprawnienia i panel — pozostają celowo poza tym checkpointem, aby nie mieszać odpowiedzialności i utrzymać odwracalne zmiany.
