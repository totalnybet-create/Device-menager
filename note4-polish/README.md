# Note 4 Polski

Mała aplikacja dla Samsung Galaxy Note 4 (SM-N910x) z Androidem 5/6.

## Co robi

- wykrywa model, wersję Androida i aktywny język,
- próbuje ustawić systemowy locale na `pl-PL`,
- jeśli telefon ma root, próbuje automatycznie nadać sobie `CHANGE_CONFIGURATION`,
- bez roota pokazuje jedną komendę ADB potrzebną do jednorazowego nadania uprawnienia,
- nie flashuje firmware, nie kasuje danych i nie modyfikuje partycji systemowych.

## Jednorazowa komenda ADB (telefon bez roota)

```text
adb shell pm grant pl.siedlar.note4polski android.permission.CHANGE_CONFIGURATION
```

Po wykonaniu komendy uruchom aplikację i naciśnij **WŁĄCZ POLSKI JĘZYK**.

## Ograniczenie techniczne

Aplikacja może włączyć polski locale tylko w zakresie tłumaczeń, które istnieją w zainstalowanym firmware. Jeśli konkretny ROM/operator usunął polskie zasoby Samsunga, pełne polskie menu wymaga zgodnego polskiego firmware dokładnie dla wariantu SM-N910F / SM-N910C / innego modelu.
