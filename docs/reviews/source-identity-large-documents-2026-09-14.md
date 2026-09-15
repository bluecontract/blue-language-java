# Source BlueId — duże dokumenty i różne struktury

> Raport historyczny. Aktualny kontrakt, poprawki i pomiary PR #36 opisuje
> [raport naprawy kontekstowych referencji](pr36-contextual-reference-repair.pl.md).

Aktualizacja: [ponowny przegląd po optymalizacji](source-identity-large-documents-rereview-2026-09-14.md).
Poniższe pomiary opisują wcześniejszą wersję patcha.

Przegląd niezacommitowanych zmian, 14 września 2026. Porównanie z `HEAD`:
`806536457fd2ff284159fe65439973aa0f02ca4f`.

**Wynik:** są dwie regresje wydajności przy wielu referencjach, których kontekst
wymaga pobrania treści. W badanych dokumentach Commerce nie wystąpiły nowe wyjątki
ani zmiany Source BlueId zależne od rozgrzania cache. Uzgodniona granica pozostaje
zachowana: nieograniczone referencje do dokumentów w listach bez `itemType` nie są
pobierane na potrzeby Source BlueId.

**P1 — snapshot pojedynczej referencji przejmuje treści pozostałych referencji
z całego wywołania.**

[`completeSnapshotForResolvedReference`](../../blue-language-core/src/main/java/blue/language/merge/CanonicalTypeIdentityIndex.java#L594)
przekazuje całą mapę `verifiedReferenceContents` do każdego snapshotu.
Konstruktor kopiuje mapę, a `calculateRetainedWeightBytes` ponownie przechodzi po
wszystkich jej treściach. Dla kolejnych niezależnych dokumentów w typowanej liście
snapshoty zawierają coraz dłuższe prefiksy tej samej mapy. Liczba kopiowanych
wpisów i wywołań ważenia rośnie kwadratowo.

Pomiar liczników w kopii diagnostycznej, jedno zimne wywołanie:

- 100 różnych referencji: 5 150 wpisów skopiowanych do snapshotów i tyle samo
  wywołań ważenia treści.
- 300 referencji: 45 450 wpisów / wywołań.
- 1000 referencji: 501 500 wpisów / wywołań.

To nie jest głębokie kopiowanie każdego `FrozenNode`: współdzielone obiekty nadal
pozostają współdzielone. Kwadratowy koszt dotyczy wpisów map, ich przetwarzania
oraz ponownego liczenia wagi treści. Waga cache obejmuje te treści wielokrotnie,
więc wpisy są wypierane znacznie wcześniej. Limit cache nie zapobiega kosztowi
utworzenia i zważenia snapshotu.

Przy domyślnej polityce cache i 300 dokumentach po 50 pól HEAD zachowuje wszystkie
301 wpisów regionu `verifiedReferences`, a obecny kod tylko 4. Każde kolejne
wywołanie pobiera ponownie wszystkie 300 dokumentów; HEAD pobiera zero. Dzieje się
to również dla zaledwie 100 dokumentów po 50 pól oraz 300 małych dokumentów.

Należy ograniczyć zawartość snapshotu do zależności potrzebnych danej referencji,
zachowując kompletne evidence dla jej zagnieżdżonych referencji. Samo zwiększenie
budżetu cache nie usuwa kwadratowej pracy. Naprawionego transportu
`verifiedReferenceContents` przez `importComplete` i wrappery nie należy usuwać.

**P2 — dopełnianie referencji wielokrotnie skanuje całe poddrzewo i wszystkich
kandydatów.**

[`materializePendingDefinitionReferences`](../../blue-language-core/src/main/java/blue/language/merge/CompletedValueValidator.java#L403)
po każdej dopełnionej referencji odtwarza zbiór wszystkich węzłów poddrzewa
i rozpoczyna przegląd kandydatów od początku. Dodatkowo
[`materializedReference`](../../blue-language-core/src/main/java/blue/language/merge/ReferenceResolver.java#L444)
wywołuje tę procedurę dla każdej nowo rozwiązanej treści, choć lista kandydatów
obejmuje całe wywołanie. Jest to drugi, niezależny koszt kwadratowy.

Dla 100 / 300 / 1000 różnych referencji liczniki pokazują odpowiednio:

- 15 150 / 135 450 / 1 501 500 sprawdzeń kandydatów;
- 201 / 601 / 2001 odbudowań zbioru węzłów poddrzewa;
- przy 50 polach w dokumencie: 272 952 / 2 408 852 / 26 579 502 odwiedzin węzłów.

Problem pozostaje również przy wielokrotnym użyciu **tej samej** referencji.
1000 elementów wskazujących jeden dokument powoduje 502 500 sprawdzeń kandydatów
i 2 004 004 odwiedziny węzłów, mimo jednego pobrania treści na zimnym cache
i zerowych pobrań na ciepłym cache. Snapshoty zawierają wtedy łącznie tylko dwa
wpisy referencji, co rozdziela ten problem od P1.

W tym wariancie ciepłe wywołanie wzrosło z 190 do 240 ms; niezależne powtórzenie
dało 192 do 233 ms. Alokacje wzrosły z około 0,79 do 0,92 GB na wywołanie.
Dopełnianie potrzebuje pracy ograniczonej do oczekujących kandydatów właściwego
poddrzewa, bez ponownego pełnego skanu po każdym elemencie. Musi zachować
dopełnianie zagnieżdżonych referencji przed klonowaniem i zapisem do cache.

**Łączny wpływ na czas i alokacje.**

Poniżej mediany trzech kolejnych ciepłych wywołań `sourceDocumentBlueId` w jednym
procesie JVM, HEAD → obecny kod. Provider działa w pamięci, więc wyniki nie
obejmują kosztu sieci:

- 300 różnych referencji, 1 pole: **66,5 → 113,5 ms**, 229 → 328 MB alokacji,
  pobrania dokumentów 0 → 300.
- 1000 różnych referencji, 1 pole: **195 → 379 ms**, 791 → 1436 MB alokacji,
  pobrania 0 → 1000.
- 300 różnych referencji, 50 pól: **96,9 → 734 ms**, 317 → 1532 MB alokacji,
  pobrania 0 → 300.
- 1000 różnych referencji, 50 pól: **0,661 → 5,949 s**, 1,856 → 9,831 GB
  alokacji. Przy tej wielkości oba warianty przekraczają pojemność cache
  i pobierają wszystkie 1000 dokumentów ponownie; regresja czasu nie wynika
  więc wyłącznie z nowych chybień cache.
- 1000 referencji do surowych wartości Amount: **559 → 1014 ms**,
  1,886 → 3,213 GB alokacji, pobrania 0 → 1000.

Powtórzenie najważniejszych przypadków w nowych JVM potwierdziło efekt:
300 × 50 pól: **110 → 691 ms**; 1000 × 50 pól: **0,653 → 5,914 s**.
Nawet po wyłączeniu cache 1000 małych referencji zwolniło z 254 do 380 ms.
Nie przypisuję całego zmierzonego czasu jednemu z dwóch mechanizmów; liczniki
potwierdzają ich złożoność, a pomiary obejmują całe wywołanie API.

GB/MB powyżej oznaczają **sumę alokacji na wątku wywołującym**, a nie rozmiar
żywej sterty ani wymagany limit pamięci. Wszystkie te przypadki ukończyły się
przy `-Xmx512m`. Wagi zwracane przez statystyki cache są jego szacunkiem,
a nie pomiarem rzeczywiście zatrzymanej pamięci.

**Zgodność z uzgodnioną granicą materializacji.**

- Lista 10 000 różnych referencji bez `itemType`: **zero pobrań treści** zarówno
  na zimnym, jak i na ciepłym cache. Provider rzuca wyjątek przy próbie pobrania
  któregokolwiek dokumentu. Ciepły czas: 1,600 → 1,625 s; alokacje około 7,63 GB
  w obu wersjach. Duży koszt samej listy występuje już na HEAD.
- Lista 1000 referencji do dużych, odrębnych kopii definicji Product z Commerce:
  również **zero pobrań**, ze strażnikiem rzucającym wyjątek. Treści były obecne
  w providerze, ale nie zostały otwarte podczas obliczania tożsamości listy.
- Szeroki obiekt inline z 5000 polami: 1,042 → 1,026 s. Drzewo mieszane
  z 1024 liśćmi, mapami, listami i pustymi obiektami: 397 → 416 ms.
  W tych próbach nie widać regresji podobnej do typowanych referencji.
- Sprawdzono także słownik z `valueType`, powtarzane referencje, surowe Amount
  i zagnieżdżone referencje. Dla dostępnych małych prób typowanego inline/reference
  obecny kod zachował parytet. Pomiar słownika nie pobierał treści wartości
  w trybie deklaracji w żadnej wersji, więc nie służy jako test materializacji
  wszystkich wartości słownika.

**Dokumenty z `myos-commerce-demo/protocol`.**

Próby wykorzystują lokalne źródła z
`/Users/przemekkowalewski/Sites/myos-commerce-demo/protocol`, bez zmian w tym repo.
Załadowano 36 definicji w kolejności zależności, podstawiono identyfikatory runtime
i publikowanych bezpośrednio definicji. 40 zewnętrznych treści providera pochodzi
z przypiętego `blue-repo-java:3.0.0-rc.22` oraz rejestrów typów. Każda została
zweryfikowana przez przeliczenie jej bezpośredniego BlueId. Jawne zależności
fixtures są domknięte; sprawdzono też SHA źródeł i locki dziewięciu pakietów.

Pomiar objął Product, PaymentInstructions, ProductConditionedPaymentInstructions,
Order, BundleProduct, BasketProduct i AfterSaleOrder oraz snapshoty diagnostyczne
`10-full-merge` i `11-package-preview`. To dziewięć przypadków, osiem różnych
dokumentów po parsowaniu: te dwa snapshoty zawierają identyczne wartości JSON.

Wszystkie przypadki ukończyły się bez nowych wyjątków, ze stałym Source BlueId
w obrębie danej wersji. Zmiany identyfikatorów pomiędzy HEAD a nowym kodem są
spodziewane przy zmianie reguły zachowywania typu i same w sobie nie są tu
uznawane za regresję.

Wybrane mediany ciepłego wywołania, HEAD → obecny kod:

- Product: **197 → 203 ms**; rozwiązana postać około 341 KB.
- ProductConditionedPaymentInstructions: **275 → 271 ms**.
- BundleProduct: **354 → 349 ms**; rozwiązana postać około 753 KB.
- BasketProduct: **651 → 651 ms**; rozwiązana postać około **1,31 MB**.
- AfterSaleOrder: **616 → 625 ms**; rozwiązana postać około 938 KB.
- Snapshot `10-full-merge`: **619 → 566 ms**; rozwiązana postać około 987 KB.
- Dziesięć kopii snapshotu inline, łącznie około **551 KB** wejścia w postaci
  wire JSON: **1,827 → 1,898 s**, około 5,56 → 5,52 GB alokacji.

Główny harness sprawdza kolejność Source → Source → Source →
`resolveDefinition(originalSource)` → Source na tym samym runtime. Dodatkowa,
oddzielna próba dla Product, BasketProduct i `10-full-merge` rozpoczyna od
`resolveDefinition` na świeżym runtime, a następnie oblicza Source BlueId
oryginalnego źródła. We wszystkich sześciu porównaniach, HEAD i obecny kod,
wynik zgadza się z tożsamością obliczoną bez wcześniejszego resolve.

To kontrola tożsamości i dostępności API na rzeczywistych strukturach, bez
wykonywania workflow Commerce i pełnego porównania wszystkich pól rozwiązanej
postaci. Po resolve zapisano rozmiar, `kind` i liczbę kontraktów. Powtarzające się
wywołania providera w snapshotach diagnostycznych występują w obu wersjach:
1210 na zimno i 1178 na ciepło. Licznik obejmuje wszystkie zapytania poza
dokumentami oznaczonymi jako dane; nie rozróżnia trafień i chybień, więc nie
oznacza liczby brakujących zależności.

**Ograniczenie istniejące przed tym patchem.**

Syntetyczny łańcuch 20 kolejnych typów Box, gdzie każdy zawiera poprzedni typ,
kończy się `OutOfMemoryError` przy 512 MiB sterty zarówno na HEAD, jak i obecnie.
Przy 10 poziomach wariant po referencjach działa, lecz próba odpowiadającej mu
pełnej postaci inline wyczerpuje stertę w obu wersjach. To rozrost rozwiązanych
typów w tym konkretnym scenariuszu, a nie ogólny limit głębokości dokumentów.
Nie klasyfikuję tego jako regresji wprowadzanej przez przeglądane zmiany.

**Metoda i odtworzenie.**

macOS arm64, Temurin 25.0.4.1, `-Xms64m -Xmx512m -Xss2m`. Zmienione klasy
produkcyjne z HEAD skompilowano z `--release 8` i użyto przed wspólnymi,
niezmienionymi klasami w odrębnym classpath. Obecne skompilowane klasy zamrożono
w katalogu tymczasowym. Procesy HEAD i obecnego kodu uruchamiano kolejno,
bez równoległych testów lub buildów. Syntetyczne próby miały pięć wywołań
rozgrzewających kod na osobnym małym fixture; cache mierzonego dokumentu był
na początku pusty. Wyjątkiem był kosztowny łańcuch Box. Próby Commerce mają
zimne wywołanie i dwa kolejne ciepłe. To pomiary diagnostyczne, nie seria JMH
ani certyfikacja wydajności na wszystkich obsługiwanych JDK.

Instrumentacja dodała wyłącznie statyczne liczniki w kopiach dwóch klas.
W jej próbach wyłączono dodatkowy warmup, sprawdzanie inline i powtórzenia,
żeby liczniki dotyczyły jednego wywołania. Czasów z instrumentacji nie użyto
w porównaniu wydajności.

Wyniki, źródła harnessów, fixtures, classpathy i manifesty są w
/private/tmp/blue-large-identity-review-20260914 (archiwalna ścieżka: `/private/tmp/blue-large-identity-review-20260914`).
Najważniejsze pliki:

- summary.json (archiwalna ścieżka: `/private/tmp/blue-large-identity-review-20260914/summary.json`) —
  mediany, błędy, liczniki i sumy SHA-256 plików wynikowych.
- `smoke-results.jsonl`, `scaling-results.jsonl`, `confirmation-results.jsonl` —
  surowe zimne i ciepłe pomiary syntetyczne.
- `protocol-results.jsonl`, `protocol-fresh-resolution-results.jsonl` —
  rzeczywiste dokumenty i osobny wariant resolve przed Source.
- `instrumentation-results.jsonl` — deterministyczne liczniki pracy.
- `LargeIdentityProbe.java`, `ProtocolIdentityProbe.java`,
  `ProtocolFreshResolutionProbe.java`, `instrument-build.py` — źródła prób.
- `prepare.py`, `run-matrix.py`, `run-protocol.py`, `classpaths.json` —
  przygotowanie i uruchamianie porównania.
- `build-identity.json`, `reviewed.patch`, `instrument-manifest.json` —
  identyfikacja badanego kodu.

Powtórzenie głównych pomiarów na zamrożonych klasach:

```sh
python3 /private/tmp/blue-large-identity-review-20260914/run-matrix.py /private/tmp/blue-large-identity-review-20260914/confirmation.json
```

SHA-256 śledzonego diffu względem HEAD:
`f6fc8eccd7dce2b0ffd2d121a61dce3330cd8da1e839f3f5888b0adf04dedb7c`.
Sprawdzono, że nie zmienił się podczas pomiarów. Przegląd nie modyfikuje
kodu produkcyjnego i nie tworzy commita.
