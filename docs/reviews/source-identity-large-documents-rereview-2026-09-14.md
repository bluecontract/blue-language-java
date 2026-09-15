# Source BlueId — ponowny przegląd po optymalizacji

> Raport historyczny. Aktualny kontrakt, poprawki i pomiary PR #36 opisuje
> [raport naprawy kontekstowych referencji](pr36-contextual-reference-repair.pl.md).

Nowsza weryfikacja po naprawach: [przegląd R3](source-identity-large-documents-r3-2026-09-14.md).
Poniższy raport opisuje wcześniejszą wersję zmian.

14 września 2026. Przegląd niezacommitowanych zmian względem
`806536457fd2ff284159fe65439973aa0f02ca4f` oraz zamrożonej wersji z
[poprzedniego przeglądu](source-identity-large-documents-2026-09-14.md).

**Ocena: duża poprawa czasu, ale cztery nowe problemy wymagają naprawy przed
commitem.** Dwa niezależne P1 dotyczą tożsamości zagnieżdżonych wartości,
jeden P1 dotyczy mutowalnego evidence, a P2 dotyczy zaniżania zatrzymanej pamięci.
Wyników obu osi przeglądu nie łączę: Spec — dwa P1; Standards — P1 i P2.

**Spec: P1 — po materializacji nie są dodawane nowe węzły poddrzewa.**

Miejsce: [CompletedValueValidator.java:524](../../blue-language-core/src/main/java/blue/language/merge/CompletedValueValidator.java#L524),
wywołania helpera w liniach 451 i 465.

Warunek materializacji wymaga, żeby kandydat należał już do `subtree`.
Po materializacji `addSubtreeNodes(subtree, candidate.node)` dostaje ten sam
korzeń, więc `!nodes.add(current)` natychmiast przerywa jego przejście.
Nowo dołączone dzieci nie trafiają do zbioru i ich oczekujące referencje są pomijane.

Reprodukcja:

- `Currency` dziedziczy po `Text`.
- `Holder.entries` ma `type: List`, `itemType: Currency`.
- Dokładnie opublikowana lista zawiera referencję do
  `{name: "Authored label", type: Currency, value: PLN}`.
- Źródło podaje `entries` jako referencję do tej listy.

Obecny CII zawiera nazwę i typ elementu, ale **gubi `value: PLN`**. Postać inline
zachowuje wartość i daje `qe8zgxQjHuzXB1ELzc1YSxNwvX36mN6yECKgLTdr4Mi`;
referencja daje `AamJmJVmZrg4m2ugLBB8wQCvJy96Mj2sJ6uFLr3FDbgp`.
Błąd występuje na zimnym i ciepłym cache oraz po osobnym resolve przed Source.
HEAD i poprzednia wersja poprawek zachowują parytet w tym przypadku.

Zmiana kontrolna wyłącznie odświeżająca członkostwo nowych potomków przywraca
wartość i prawidłowy identyfikator. Nie naprawia następnego problemu.
Naruszona reguła: specyfikacja §13.4 wymaga zachowania wszystkich wkładów
instancji, których nie można wyprowadzić z łańcucha typów
([specyfikacja:2498](../../blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md#L2498)).
Jest to kontekst z efektywnym `itemType`, objęty wymaganym parytetem.

**Spec: P1 — kandydat zmieniony na oczekujący po pierwszym przeglądzie zostaje
trwale pominięty.**

Miejsce: [CompletedValueValidator.java:437](../../blue-language-core/src/main/java/blue/language/merge/CompletedValueValidator.java#L437).

Kursory `definitionIndex` i `candidateIndex` przesuwają się tylko naprzód.
Materializacja zewnętrznej referencji może jednak ustawić `pendingReferenceBlueId`
na kandydacie już istniejącym i wcześniej obejrzanym. Nawet kolejny obrót
`while (progressed)` nie wróci do tego kandydata.

Reprodukcja: `Holder.amount: Amount`, gdzie `Amount.currency: Currency` ma
`schema.required: true`. Źródło wskazuje referencję do surowego obiektu
`{currency: {blueId: scalarId}}`. Scalar jest poprawną wartością Currency
z nazwą `Authored label` i wartością `PLN`.

Inline działa. Referencja na zimnym i ciepłym cache rzuca:

```text
Canonical and resolved roots do not match the supplied canonical type identity evidence
```

Poprzednia wersja wyznaczała dla obu postaci
`2sXurGseZggoUrmKCcdeLLxS3xet62qKbGRNCWzCY4Js`. Błąd powtarza się także
przy scalarze bez jawnego typu Currency.

Zmiana kontrolna przywracająca ponowne rozpatrzenie wcześniejszych kandydatów
naprawia ten przypadek, ale nie naprawia utraty wartości z listy. Obie zmiany
kontrolne razem naprawiają oba przypadki. Usunięcie ograniczenia `candidateMark`
nie daje dodatkowej różnicy w tych próbach. Nie proponuję powrotu do pełnych
kwadratowych skanów: produkcyjna kolejka musi reagować na zmianę stanu istniejącego
kandydata i na pojawienie się nowych potomków.

Naruszona reguła: Source musi ustalić wszystkie zależności tożsamości i zachować
odroczone obowiązki, §8.1.1
([specyfikacja:1216](../../blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md#L1216)).

**Standards: P1 — publiczny lookup oddaje mutowalny stan zatrzymany w evidence.**

Miejsce: [VerifiedReferenceContents.java:71](../../blue-language-core/src/main/java/blue/language/merge/VerifiedReferenceContents.java#L71).

`find()` zachowuje `content.toNode()` w `nodes` i za każdym razem zwraca ten sam
obiekt. Synchronizacja inicjalizacji mapy nie zabezpiecza obiektu przekazanego
wywołującemu. Publiczny `findVerifiedReferenceContent` obiecuje odłączoną dokładną
treść, a [zasady własności](../architecture/immutability-and-runtime-state.md)
przypisują mutowalne `Node` wywołującemu i wymagają odłączonych kopii z accessorów.

Reprodukcja używa wyłącznie publicznych API: pobranie
`snapshot.canonicalTypeIdentities().findVerifiedReferenceContent(valueId)`,
ustawienie `name("Caller mutation")` i ponowny lookup. Obecny kod zwraca ten sam
zmieniony obiekt, który **nie hashuje się już do zweryfikowanego BlueId**.
W wariancie z `Holder.child: Currency` ponowna rekonstrukcja CII z tego samego
snapshotu i tego samego źródła zmienia identyfikator:

```text
przed: zXgfZHGib8zDxFsesGzj4fbLdo1ZLMcTxjRD8SM2WiR
po:    2oGRiRVJ9RD5xBnTUTGDLu7zVyHAx4Dgupd3cbUbyACC
```

Poprzednia implementacja zwraca odrębne kopie i zachowuje tożsamość.
W prostszym wariancie z podstawowym Text lookup też zostaje uszkodzony, lecz
rekonstrukcja zachowuje referencję i nie zmienia wynikowego identyfikatora.
Dowód zmiany CII pochodzi z wariantu Currency.

Współdzielone evidence powinno zachowywać niemutowalną treść. Ewentualne
memoizowanie materializacji musi respektować granice własności i publiczne
obietnice API.

**Standards: P2 — pominięte treści nie zawsze są rozliczane w innym wpisie cache.**

Miejsce: [VerifiedReferenceContents.java:156](../../blue-language-core/src/main/java/blue/language/merge/VerifiedReferenceContents.java#L156),
dodatkowa zatrzymywana materializacja w liniach 74–75.

Waga uwzględnia tylko klucze i mapę. Registry silnie zatrzymuje jednak pełne
`FrozenNode`, a po lookupie także mutowalne kopie. Własny wpis zależności może
zostać odrzucony lub wyparty z cache niezależnie od snapshotu rodzica.

Potwierdzone trzema próbami:

- Izolowany registry zatrzymuje treść o szacowanej wadze **1 781 254 B**, ale
  zgłasza **184 B**, także po utworzeniu mutowalnej materializacji.
- Publiczne API snapshotu z **wyłączonymi cache** zatrzymuje treść o 4097 polach,
  szacowaną na **1 781 910 B**, a całe evidence zgłasza **4452 B**. Poprzednia
  implementacja zgłasza **1 786 362 B**. Nie istnieje tu inny wpis cache,
  któremu można przypisać brakującą wagę.
- W rzeczywistym cache z limitem jednego wpisu usunięto własny wpis dziecka,
  zachowano wpis rodzica, a jego evidence nadal zatrzymuje dziecko. Po uwzględnieniu
  współdzielenia z korzeniami rodzica pozostaje **1 638 426 B dodatkowej szacowanej
  wagi zależności**, podczas gdy całe evidence zgłasza **4480 B**. Późniejszy
  lookup zapisuje kolejną mutowalną materializację bez zwiększenia tej wagi.

Liczby dotyczą funkcji szacującej wagę `FrozenNode`, nie pomiaru żywej sterty.
Dowodzą pominięcia zatrzymywanej części grafu. Liczenie współdzielonych obiektów
musi być zgodne z ich rzeczywistą własnością i usuwaniem; obecne założenie
narusza kontrakt wagi grafu evidence oraz zasadę ograniczonych cache.

**Wydajność po poprawkach.**

Ta sama metoda i fixtures co poprzednio: osobne procesy JVM, lokalny provider,
Temurin 25.0.4.1, `-Xms64m -Xmx512m -Xss2m`. Poniżej mediany trzech ciepłych
wywołań. HEAD i obecny patch zmierzono ponownie; poprzedni patch pochodzi
z wcześniejszego zapisanego przebiegu.

- 1000 różnych referencji × 50 pól: **HEAD 0,660 s → poprzedni patch 5,949 s →
  obecny 1,025 s**. Ostatnie pojedyncze ciepłe wywołanie obecnego patcha: 0,964 s.
  Alokacje obecnie około 2,294 GB zamiast wcześniejszych 9,831 GB; HEAD 1,840 GB.
  Regresja około 9× zniknęła, ale w tym przypadku pozostaje około **55%** narzutu.
- 300 × 50 pól: **94,8 → 140,8 ms** względem HEAD; poprzednio około 734 ms.
  Obecnie **zero ponownych pobrań** dokumentów na ciepłym cache, poprzednio 300.
- 1000 małych różnych referencji: **205 → 166 ms**, pobrania 0 → 0.
- 1000 powtórzeń jednej referencji: **212 → 164 ms**, pobrania 0 → 0.
- 1000 surowych Amount po referencji: **579 → 421 ms**, pobrania 0 → 0.
- 3000 małych różnych referencji: **819 → 628 ms**; oba cache ponownie pobierają
  wszystkie wartości przy tej wielkości.
- 5000 pól inline: **1077 → 920 ms**; mieszane drzewo z 1024 liśćmi:
  **402 → 363 ms**.

To pomiary diagnostyczne, bez statystycznego progu regresji. GB alokacji oznacza
sumę alokacji na wątku, a nie wymagany rozmiar sterty. Czasy nie obejmują żadnych
kontrolnych modyfikacji kodu użytych do ustalenia przyczyn błędów.

**Kontrole, które przeszły.**

- 183 testy core, 59 wybranych testów głównych dotyczących cache, rekurencji,
  fragmentacji, tożsamości wybranego zakresu i własności modułów.
- Kontrole cykli pakietów core i contracts.
- Osiem różnych dokumentów Commerce w dziewięciu przypadkach, lista 1000
  referencji do odrębnych kopii Product oraz dziesięć dużych dokumentów inline.
  Brak nowych wyjątków lub zmian identyfikatorów w tych wcześniejszych fixtures.
- Product, BasketProduct i `10-full-merge`: świeży runtime rozgrzany przez
  `resolveDefinition` zachowuje Source BlueId. Przykładowe czasy HEAD → obecnie:
  Product 224 → 171 ms, BasketProduct 776 → 529 ms, dziesięć snapshotów inline
  2,021 → 1,325 s.
- Lista 10 000 nieograniczonych referencji bez `itemType` oraz lista 1000 dużych
  dokumentów Product: **zero pobrań treści**, na zimnym i ciepłym cache.
- Tożsamości w powtórzonym dotychczasowym zestawie syntetycznym i Commerce
  zgadzają się z poprzednim patchem. Nowe błędy ujawniły dodatkowe, małe
  reprodukcje opisane powyżej.

Nie wykonywano workflow Commerce ani pełnego `releaseCheck`. Java 8 dostępna
lokalnie ma niesprawną instalację; testy uruchomiono, jak wcześniej, przez
tymczasowy override na JDK 25. Zmienione źródła produkcyjne kompilują się
do Java 8. Zadania `apiBaselineDiff` wygenerowały raporty zawierające dodatki API;
nie traktuję ich jako potwierdzenia zgodności z niezmienionym baseline.
Zielone testy istniejącego zestawu nie obejmują nowych reprodukcji.

**Materiały do odtworzenia.**

Katalog: /private/tmp/blue-large-identity-review-20260914-r2 (archiwalna ścieżka: `/private/tmp/blue-large-identity-review-20260914-r2`).

- `ContextAttachedReferenceProbeV2.java` oraz logi z sufiksami `head`, `previous`,
  `current` — obie regresje zagnieżdżonej materializacji, kontrolny słownik.
- `causal-refresh.log`, `causal-revisit.log`, `causal-refresh-revisit.log` —
  niezależne potwierdzenie przyczyn; to wyłącznie warianty diagnostyczne.
- `ReferenceEvidenceOwnershipCurrencyProbe.java` i
  `ownership-currency-{previous,current}.jsonl` — mutacja przez publiczne API
  i zmiana rekonstrukcji CII.
- `PublicEvidenceWeightProbe.java`, `public-evidence-weight-{previous,current}.log`
  oraz `ReferenceEvidenceEvictionProbe.java`, `evidence-eviction-current.jsonl` —
  zaniżona waga i rzeczywiste wyparcie zależności.
- `summary.json` — mediany i skróty SHA wyników; pliki `*-results.jsonl` —
  pełne pomiary syntetyczne i Commerce.
- `core-tests.json`, `focused-tests.json` — wykonane zestawy testów.
- `reviewed-source-sha256.json` — skróty zmienionych plików, **w tym nowego
  nieśledzonego `VerifiedReferenceContents.java`**; `reviewed-source/` — kopie.
- `build-identity.json`, `reviewed.patch`, `classpaths.json` — zamrożony kod
  i identyfikacja porównania. Śledzony diff ma SHA-256
  `15e5596677d37324ac40d2a0d240c1077f799349bca76a315ca15b6fc4a9d6a6`.

Przegląd nie zmienia kodu produkcyjnego i nie tworzy commita.
