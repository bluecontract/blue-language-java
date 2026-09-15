# PR #36: pomiar tożsamości

Wygenerowano z jednego corpus z niezależnym direct oracle.

Implementacja końcowa: `4f686c768dcfa3ad3326f3d1c07cba3f6d24944e`.

- Liczba wierszy na wykonanie: 132.
- Zmiany direct ID: **0**. Niewyjaśnione zmiany: **0**.
- Końcowe rozbieżności z oracle: **0**.
- Mini/MyOS: bramka odłożona przez użytkownika, niewykonana.

## Aktualny next

Commit: `952b90ba5748f6b3605482700608bd0429839a83`.

Rozbieżności z oracle przed naprawą: **60**.

- `currency-inline-custom`: canonical-custom-type; pola: sourceBlueId, canonicalInput.
- `currency-inline-text`: canonical-custom-type; pola: sourceBlueId, canonicalInput.
- `currency-reference-text`: canonical-custom-type; pola: sourceBlueId, canonicalInput.
- `dictionary-invalid-reference`: dictionary-value-type-validation; pola: sourceBlueId, sourceError, canonicalInput, resolved, resolutionError.
- `dictionary-valid-reference`: dictionary-completed-payload; pola: resolved.
- `empty-object-inline`: present-empty-object; pola: sourceBlueId, canonicalInput.
- `list-invalid-reference`: list-item-type-validation; pola: sourceBlueId, sourceError, canonicalInput, resolved, resolutionError.
- `list-whole-reference`: complete-referenced-list-payload; pola: sourceBlueId, canonicalInput.
- `schema-reference`: schema-materialization-evidence; pola: sourceBlueId, canonicalInput.
- `terms-inline-coffee`: canonical-custom-type; pola: sourceBlueId, canonicalInput.
- `terms-inline-tea`: canonical-custom-type; pola: sourceBlueId, canonicalInput.
- `terms-reference-coffee`: canonical-custom-type; pola: sourceBlueId, canonicalInput.
- `terms-reference-tea`: canonical-custom-type; pola: sourceBlueId, canonicalInput.

## Oryginalny PR #36

Commit: `9eb47786cee058af3868ed581bd9fb4b7e15850c`.

Rozbieżności z oracle przed naprawą: **32**.

- `dictionary-invalid-reference`: dictionary-value-type-validation; pola: sourceBlueId, sourceError, canonicalInput, resolved, resolutionError.
- `dictionary-valid-reference`: dictionary-completed-payload; pola: resolved.
- `empty-object-inline`: present-empty-object; pola: sourceBlueId, canonicalInput.
- `empty-object-reference`: present-empty-object; pola: sourceBlueId, canonicalInput.
- `list-invalid-reference`: list-item-type-validation; pola: sourceError, resolved, resolutionError.
- `schema-reference`: schema-materialization-evidence; pola: sourceBlueId, canonicalInput.

## Pełne dowody

Każdy raport zawiera dokładne wejście i jego direct ID, canonical input, Source ID,
resolved albo kategorię błędu oraz odczyty providera. Tryby: cold, warm-forward,
warm-reverse, pre-resolved. Pole `resolved` jest wiernym wire view; osobne asercje
testu sprawdzają wymagane efektywne typy i wartości. Historyczne checkouty
otrzymały wyłącznie ten sam test i corpus; kod produkcyjny pozostał przypięty
do wskazanych commitów. Hashe wspólnego harness znajdują się w comparison.json.

- [next-matrix.json](next-matrix.json)
- [original-pr36-matrix.json](original-pr36-matrix.json)
- [repaired-matrix.json](repaired-matrix.json)
- [Klasyfikacja każdego zmienionego wiersza](comparison.json)

[Inwentarz zależnych rejestrów, fixture i środowisk](dependency-changes.json) zawiera
także komplet przejrzanych zmian plików pakietu Contracts, związanych hashami bajtów
z commitami przed i po naprawie. Niewyjaśniona zmiana lub aktywne stare wiązanie
przerywa generowanie raportu.
Stare exact ID pozostają związane ze starą treścią; raport nie tworzy aliasów.
