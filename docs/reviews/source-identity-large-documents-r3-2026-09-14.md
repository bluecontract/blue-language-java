# Source BlueId — weryfikacja czterech poprawek

Nowsza weryfikacja: [przegląd R4](source-identity-large-documents-r4-2026-09-14.md).
Poniższy raport opisuje wcześniejszą wersję zmian.

14 września 2026. Niezacommitowane zmiany względem
`806536457fd2ff284159fe65439973aa0f02ca4f`, sprawdzone ponownie po
[poprzednim przeglądzie](source-identity-large-documents-rereview-2026-09-14.md).

**Nie wszystkie problemy są zamknięte.** Potwierdzam naprawę wcześniejszego
przypadku z `required`, izolację wyników lookupu oraz doliczenie zamrożonych
treści do wagi evidence. Nadal działa reprodukcja utraty wartości w liście.
Dodatkowo naprawa podpinania żywego węzła nie obejmuje wartości odziedziczonej
z typu, a waga pomija memoizowaną mutowalną kopię treści.

## Spec

**P1 — nowo dołączone elementy listy nadal nie trafiają do zbioru poddrzewa.**

Miejsce: [CompletedValueValidator.java:549](/Users/przemekkowalewski/Sites/blue-language-java/blue-language-core/src/main/java/blue/language/merge/CompletedValueValidator.java:549).

Po materializacji helper dostaje korzeń już obecny w `subtree`, więc
`!nodes.add(current)` kończy przejście, zanim odwiedzi nowe dzieci. Fallback
odszukuje żywy węzeł po ścieżce, ale wymaga jego obecności w tym samym,
nieodświeżonym zbiorze. Ponowny przegląd kandydatów tego nie zmienia.

To **ta sama reprodukcja**, która wykryła pierwszy P1 w poprzednim raporcie:

- `Currency` dziedziczy po `Text`.
- `Holder.entries` deklaruje `type: List`, `itemType: Currency`.
- Dokładnie opublikowana lista zawiera referencję do
  `{name: "Authored label", type: Currency, value: PLN}`.
- Źródło podaje `entries` jako referencję do całej tej listy.

Postać inline ma Source BlueId
`qe8zgxQjHuzXB1ELzc1YSxNwvX36mN6yECKgLTdr4Mi`.
Referencja nadal daje `AamJmJVmZrg4m2ugLBB8wQCvJy96Mj2sJ6uFLr3FDbgp`:
CII zachowuje nazwę i typ elementu, ale **gubi `value: PLN`**. Wynik powtarza
się na zimnym i ciepłym cache oraz po osobnym `resolve` przed Source.
HEAD i pierwsza, wolniejsza wersja poprawek zachowywały tu parytet.

Nowy test `shouldCompleteReferencesNestedInInlineTypedChildrenAndListItems`
ma zewnętrzną listę zapisaną inline, więc nie obejmuje tego przypadku.
Wariant diagnostyczny odświeżający członkostwo nowych potomków przywraca
prawidłową wartość i identyfikator. Nie naprawia następnego punktu.

Reguła: §13.4 wymaga zachowania wszystkich wkładów instancji, których nie da
się wyprowadzić z łańcucha typów
([specyfikacja:2498](/Users/przemekkowalewski/Sites/blue-language-java/blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md:2498)).
Lista ma efektywny `itemType`; ten przypadek podlega wymaganemu parytetowi.

**P1 — odziedziczona wartość blokuje dopełnienie oczekującej referencji.**

Miejsce: [CompletedValueValidator.java:494](/Users/przemekkowalewski/Sites/blue-language-java/blue-language-core/src/main/java/blue/language/merge/CompletedValueValidator.java:494).

Fallback odrzuca żywy węzeł z istniejącym `value`, `items` albo `properties`.
Taka zawartość może pochodzić z typu, mimo że wkład referencji nadal oczekuje
na materializację. Odrzucenie gubi autorskie metadane referencji.

Reprodukcja rozszerza nowy przypadek testowy o stałą wartość w typie:

- `Currency` dziedziczy po `Text` i ustala `value: PLN`.
- `Amount.currency` ma typ `Currency` oraz `schema.required: true`.
- Wartość waluty jest opublikowana jako
  `{name: "Authored label", type: Text, value: PLN}`.
- Inline `Amount` wskazuje tę walutę po referencji. Amount znajduje się
  pod typowanym polem Holder albo jako element `List.itemType: Amount`.

W wariancie pola CII gubi całą autorską część `entries`, w tym nazwę waluty.
Source BlueId inline to `GMgQbUMRRtY41V3cnESBaud4V8ubenAi9Tqwp9xZoKMr`,
a przez referencję — `Ghb1L8hBVqKmDP7w5k3jB3ZoxoCy6gb9LVgqFxgJE3ES`.
W wariancie listy inline działa, a referencja rzuca:

```text
Canonical and resolved roots do not match the supplied canonical type identity evidence
```

Oba wyniki powtarzają się na zimno, na ciepło i po niezależnym resolve.
Kontrola bez stałego `Currency.value` przechodzi w obu kształtach.

To **niedomknięcie naprawy typed inline/reference**, a nie regresja wprowadzona
wyłącznie w ostatniej iteracji. HEAD już miał tu rozbieżność identyfikatorów;
pierwsza i druga wersja niezacommitowanego patcha również gubiły wkład pola
i rzucały wyjątek dla listy. Nowy fallback naprawia wariant bez odziedziczonej
wartości, ale pozostawia wariant ze stałą `PLN`.

Diagnostyczne usunięcie wyłącznie trzech warunków odrzucających odziedziczony
payload przywraca parytet w obu kształtach i wszystkich trybach cache.
Nie naprawia pierwszego P1. Obie zmiany kontrolne razem naprawiają oba przypadki.
To potwierdzenie przyczyn, nie gotowa, zweryfikowana poprawka produkcyjna.

Reguła: §13.4 wymienia instancyjne `name` wśród zachowywanych wkładów
([specyfikacja:2500](/Users/przemekkowalewski/Sites/blue-language-java/blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md:2500)).

## Standards

**P2 — waga nadal pomija memoizowaną mutowalną kopię treści.**

Miejsce: [VerifiedReferenceContents.java:72](/Users/przemekkowalewski/Sites/blue-language-java/blue-language-core/src/main/java/blue/language/merge/VerifiedReferenceContents.java:72),
estymator w tym pliku od linii 153.

`find` poprawnie zwraca świeży klon, ale najpierw zatrzymuje osobny graf
`content.toNode()` w mapie `nodes`. Estymator uwzględnia już `contents`
i zamrożone ciała, natomiast pomija `nodes`. Waga snapshotu jest ustalana
przed późniejszym utworzeniem tej kopii.

Powtórzona reprodukcja używa rzeczywistego cache z limitem jednego wpisu:
własny wpis dużego dziecka zostaje wyparty, wpis rodzica pozostaje i jego
evidence zatrzymuje treść dziecka. Dla 4096 pól:

- evidence zgłasza **2 367 764 B** przed i po lookupie;
- liczba memoizowanych ciał rośnie z **0 do 1**;
- graf registry rośnie według estymatora repo z **2 363 588 do 3 379 708 B**;
- przyrost unikalnego grafu, również po uwzględnieniu współdzielenia
  z korzeniami rodzica, wynosi **1 016 120 B**.

Pomiary wykorzystują kopię `FrozenNodeRetainedWeight` przyjmującą `Object...`
zamiast `FrozenNode...`; reguły naliczania i identyfikacja współdzielonych
obiektów pozostały bez zmian. Jest to estymacja grafu, nie pomiar żywej sterty.
Reprodukcja odczytuje rzeczywistą retencję przez refleksję, a lookup wykonuje
przez metodę evidence.

Doliczenie zamrożonych ciał naprawiło największą część poprzedniego P2:
publiczny snapshot z wyłączonym cache raportuje teraz **1 786 362 B** zamiast
**4452 B** dla treści szacowanej na **1 781 910 B**. Pozostaje osobny koszt
leniwej kopii. Możliwe kierunki to materializacja bez zatrzymywania tej kopii
albo zarezerwowanie jej kosztu przed przyjęciem wpisu do cache.

Naruszony kontrakt: metoda wagi obejmuje zatrzymany graf evidence
([CanonicalTypeIdentityLookup.java:179](/Users/przemekkowalewski/Sites/blue-language-java/blue-language-core/src/main/java/blue/language/identity/CanonicalTypeIdentityLookup.java:179)),
a runtime ma ograniczone cache
([immutability-and-runtime-state.md:12](/Users/przemekkowalewski/Sites/blue-language-java/docs/architecture/immutability-and-runtime-state.md:12)).

**Potwierdzone naprawy.** Wcześniejszy przypadek pominiętego kandydata z
`Amount.currency.schema.required` ma teraz parytet. Powtórny lookup zwraca
inny obiekt, a mutacja wyniku nie zmienia kolejnego lookupu ani rekonstrukcji
CII. Rekonstruktor pobiera treść raz. Ciała `FrozenNode` są już wliczane
do wagi snapshotu.

**Wydajność.** Te same fixtures oraz oddzielne procesy JVM dla HEAD i obecnego
kodu, lokalny provider, Temurin 25.0.4.1, `-Xms64m -Xmx512m -Xss2m`.
Poniżej mediany trzech kolejnych wywołań po pierwszym obliczeniu:

- `typed_unique 1000 × 50`: **676 → 933 ms**, około **38%** narzutu.
  Alokacje: **1,856 → 2,336 GB** na wywołanie. Oba warianty ponownie pobierają
  1000 treści, bo ten zbiór nie mieści się w użytym cache.
- `typed_unique 300 × 50`: **96,7 → 149,3 ms**, około **54%** narzutu.
  Alokacje: **314 → 459 MB**. Oba warianty mają zero ponownych pobrań.
- `typed_shared 1000`: **203 → 155 ms**, około **24%** szybciej;
  alokacje **787 → 528 MB**, bez ponownych pobrań.

Dziesięciokrotna regresja nie wróciła. Pozostaje koszt dużych unikalnych
treści; ostatnie pojedyncze wywołanie 300 × 50 trwało 140 ms, ale do porównania
powyżej używam mediany wszystkich trzech próbek. To pomiary diagnostyczne,
bez statystycznego progu regresji. Alokacje oznaczają sumę alokacji na wątku,
nie wymagany rozmiar sterty. Warianty kontrolne nie uczestniczyły w benchmarku.

**Weryfikacja.** Ponownie wykonano 184 testy core: zero błędów, zero pominięć,
oraz kontrolę cykli pakietów core. Pełne liczby root/contracts/conformance/
examples/aggregate/model pochodzą z relacji autora; w tej iteracji nie
uruchamiałem ponownie całej serii. Testy działały na JDK 25 przez tymczasowy
override launchera; nie jest to wykonanie testów na Java 8.

Lista **10 000 referencji bez `itemType` nadal ma zero pobrań treści** na zimno
i na ciepło. Próba pięciu poziomów zagnieżdżonych referencji zachowuje parytet
inline/reference oraz stabilną tożsamość między wywołaniami.

Powtórzono także przygotowany wcześniej zestaw z
`/Users/przemekkowalewski/Sites/myos-commerce-demo/protocol`: dziewięć przypadków
pojedynczych dokumentów (osiem różnych tożsamości), listę 1000 referencji do
odrębnych kopii Product oraz dziesięć dużych dokumentów inline. Wszystkie
przeszły bez wyjątków, zmian ID względem R2 ani rozbieżności między kolejnymi
wywołaniami. Referencje do Product nie spowodowały pobrań treści. Dla pojedynczych
dokumentów wykonano też `resolveDefinition` po obliczeniu Source i ponownie
sprawdzono tożsamość. Te próby nie wykonują workflow Commerce.

**Materiały do odtworzenia.**

Katalog: [/private/tmp/blue-large-identity-review-20260914-r3](/private/tmp/blue-large-identity-review-20260914-r3).

- `ContextAttachedReferenceProbeV2.java`, `ContextAttachedReferenceProbeV2-current.log`:
  oryginalny P1 listy, naprawiony przypadek `required`, kontrolny słownik.
- `InheritedDefaultReferenceProbe.java` i logi dla `head`, `r1`, `r2`, `current`:
  odziedziczona stała wartość i kontrola bez tej wartości.
- `causal-variants/` i logi `refresh`, `allow-inherited-payload`,
  `refresh-allow-inherited-payload`: niezależne próby przyczynowe.
- `ReferenceEvidenceOwnershipCurrencyProbe-current.log`: izolacja lookupu.
- `PublicEvidenceWeightProbe-current.log`: doliczone zamrożone ciało.
- `ReferenceEvidenceMemoWeightProbe.java`, `ReviewRetainedWeight.java`,
  `ReferenceEvidenceMemoWeightProbe-current.log`: pozostała retencja memo.
- `confirmation-summary.json`, `confirmation-results.jsonl`,
  `boundary-results.jsonl`: czasy, alokacje i liczniki pobrań.
- `protocol-summary.json`, `protocol-results.jsonl`: ponowiona kontrola
  wcześniej przygotowanych fixtures Commerce.
- `reviewed-source-sha256.json`, `reviewed-source/`, `classpaths.json`,
  `build-identity.json`, `reviewed.patch`: zamrożone źródła i buildy,
  również nieśledzony `VerifiedReferenceContents.java`.

Śledzony diff ma SHA-256
`3fd75d452a3da916f94b31c94482b4d61d07eac3ccb977754426db7870049eca`.
Przegląd nie zmienia kodu produkcyjnego ani testów repozytorium i nie tworzy commita.

Spec: dwa P1; Standards: jeden P2.
