# Device Manager — Full Pakiet v2.0

Aktualny etap projektu: działający rdzeń CLI do zarządzania lokalną listą urządzeń.

## Funkcje v2.0
- lista urządzeń,
- dodawanie urządzeń,
- usuwanie urządzeń,
- zmiana statusu,
- automatyczne nadawanie kolejnego ID,
- testy jednostkowe,
- GitHub Actions: compile check + unit tests + smoke run.

## Uruchomienie
```bash
python app.py
```

## Testy
```bash
python -m unittest discover -s tests -v
```

## Stan produkcyjny
Ta wersja nie jest jeszcze pełnym systemem zarządzania rzeczywistymi urządzeniami. Dane są przechowywane wyłącznie w pamięci procesu; brak trwałej bazy danych, agenta urządzeń, uwierzytelniania, API i panelu operatorskiego. Te warstwy będą dodawane etapami na branchach roboczych po przejściu checkpointów testowych.
