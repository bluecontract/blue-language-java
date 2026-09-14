# Source BlueId — weryfikacja R4

14 września 2026. Niezacommitowane zmiany względem HEAD
`806536457fd2ff284159fe65439973aa0f02ca4f`, porównane także z zamrożoną wersją
[R3](source-identity-large-documents-r3-2026-09-14.md).

**Pozostał jeden P1 dotyczący referencji do całej listy.** Wcześniejszy P1
z odziedziczoną stałą wartością i P2 z memoizowanym grafem są zamknięte.

## Spec

**P1 — po materializacji listy helper nadal pomija jej nowe elementy.**

Miejsce: [CompletedValueValidator.java:547](/Users/przemekkowalewski/Sites/blue-language-java/blue-language-core/src/main/java/blue/language/merge/CompletedValueValidator.java:547).

`addSubtreeNodes(subtree, candidate.node)` dostaje korzeń, który już należy
do `subtree`. Warunek `!nodes.add(current)` natychmiast kończy przejście,
więc nowe dzieci nie trafiają do zbioru. Fallback odszukuje je po ścieżce,
ale odrzuca przez `!subtree.contains(live)` w linii 492. Nowy zbiór
`materializedReferenceTargets` nie odświeża członkostwa w `subtree`.

Powtórzyłem oryginalną reprodukcję oraz wariant ze **standardowym
`BasicNodeProvider` i jawnym `type: List`** zarówno na opublikowanej liście,
jak i na liście inline. Oba warianty nadal gubią wartość.

Minimalny kształt:

- `Currency` dziedziczy po `Text`.
- `Holder.entries` deklaruje `type: List`, `itemType: Currency`.
- Opublikowany skalar to `{name: "Authored label", type: Currency, value: PLN}`.
- Opublikowana lista ma `type: List` i jeden element: referencję do tego skalara.
- Źródło Holder wskazuje całą listę po referencji.

Inline daje `qe8zgxQjHuzXB1ELzc1YSxNwvX36mN6yECKgLTdr4Mi`.
Referencja daje `AamJmJVmZrg4m2ugLBB8wQCvJy96Mj2sJ6uFLr3FDbgp`:
w CII zostają nazwa oraz typ elementu, ale znika `value: PLN`.
Nie ma wyjątku odrzucającego listę jako niepoprawną fixture.
Błąd występuje na zimno, na ciepło i po `resolve` w niezależnym runtime.

Nowy test `shouldCompleteReferencesInsideAReferencedTypedList` publikuje
listę zawierającą **inline Amount z własnym typem**. Wtedy potrzeba pobrania
`Amount.currency` jest znana podczas osobnej rezolucji treści listy.
W nieprzechodzącej reprodukcji elementy są referencjami do skalarów,
a wymaganie Currency pochodzi dopiero z `Holder.entries.itemType`.
To wymaga dopełnienia nowych elementów po dołączeniu listy do kontekstu.

Samodzielna reprodukcja z asercją:
[TypedListCurrencyReferenceRepro.java](/private/tmp/blue-large-identity-review-20260914-r4/TypedListCurrencyReferenceRepro.java).
Na aktualnym kodzie kończy się kodem 1 i trzema rozbieżnościami tożsamości.
Wariant diagnostyczny zmieniający wyłącznie przejście w `addSubtreeNodes`
przechodzi wszystkie trzy przypadki, z kodem 0. Nowy znacznik materializacji
i pozostałe warunki fallbacku pozostają w nim bez zmian.
To potwierdzenie przyczyny, nie zweryfikowana poprawka produkcyjna.

Pierwszy, wolniejszy patch R1 również przechodzi ten wariant z jawnym List.
HEAD ma dla jawnie typowanej listy wcześniejszą rozbieżność identyfikatorów,
ale zachowuje odwołanie do pełnej listy; nie gubi wartości w pokazany sposób.
Utrata wartości pojawiła się w optymalizacji patcha i pozostaje niezamknięta.

Reguła: §13.4 wymaga zachowania wkładów instancji niewynikających z typu
([specyfikacja:2498](/Users/przemekkowalewski/Sites/blue-language-java/blue-language-core/src/main/resources/specifications/blue-language-specification-1.0.md:2498)).
Efektywny `itemType` sprawia, że ten przypadek podlega parytetowi typowanej
wartości. Kontrola używa jawnego List, więc wynik nie zależy od interpretacji
skróconego zapisu listy.

## Standards

**Brak nowych potwierdzonych problemów.**

`VerifiedReferenceContents` zatrzymuje teraz wyłącznie zamrożone treści.
`find` tworzy świeży, odłączony graf bez jego memoizacji. Powtórne lookupy
i mutacja ich wyników nie zmieniają treści evidence ani CII. Waga obejmuje
zatrzymane ciała; publiczny snapshot w reprodukcji nadal poprawnie zgłasza
1 786 362 B dla treści o estymowanej wadze 1 781 910 B.

`materializedReferenceTargets` należy do pojedynczego stanu rezolucji,
nie trafia do snapshotów ani cache. Stan jest zwalniany w `finally`
kończącym sesję, również po błędzie. Nie znalazłem potwierdzonego problemu
z retencją między wywołaniami lub współbieżnością.

**Zamknięty P1 z odziedziczoną wartością.** Oryginalny
`InheritedDefaultReferenceProbe` przechodzi wszystkie cztery kształty:
pole/lista × Currency bez/z ustalonym `PLN`, na zimno, na ciepło i po
niezależnym resolve. Autorska etykieta pozostaje. Wcześniejszy przypadek
pomijanego kandydata z `schema.required` również nadal przechodzi.

**Wydajność.** Oddzielne procesy JVM dla HEAD i aktualnego patcha, te same
fixtures, lokalny provider, Temurin 25.0.4.1, `-Xms64m -Xmx512m -Xss2m`.
Mediany trzech wywołań po pierwszym obliczeniu, HEAD → patch:

- `typed_unique 1000 × 50`: **652 → 879 ms**, około **35%** narzutu;
  alokacje **1,841 → 2,319 GB**, oba warianty ponownie pobierają 1000 treści.
- `typed_unique 300 × 50`: **102 → 137 ms**, około **34%** narzutu;
  alokacje **314 → 453 MB**, oba warianty mają zero ponownych pobrań.
- `typed_shared 1000`: **200 → 159 ms**, około **20%** szybciej;
  alokacje **789 → 527 MB**, bez ponownych pobrań.

Regresja 10× nie wróciła. To pomiary diagnostyczne, bez statystycznego progu
regresji; różnice względem R3 mieszczą się w skali zmienności wcześniejszych
prób. Alokacje oznaczają sumę alokacji na wątku, nie żywą stertę. Wariant
diagnostyczny naprawiający przejście poddrzewa nie uczestniczył w benchmarku.

**Zakres kontroli.** Ponownie wykonane **186 testów core**: zero błędów
i pominięć; kontrola cykli pakietów core również przeszła. Pełne liczby pozostałych
modułów pochodzą z relacji autora — nie uruchamiałem ponownie całej serii.
Testy wykonano na JDK 25 z tymczasowym override launchera, nie na Java 8.

Lista **10 000 referencji bez `itemType` nadal powoduje zero pobrań treści**
na zimno i na ciepło. Pięć poziomów zagnieżdżonych referencji nadal zachowuje
parytet inline/reference i stabilną tożsamość.

Powtórzono też 11 wcześniej przygotowanych przypadków z Commerce: dziewięć
prób pojedynczych dokumentów, listę 1000 referencji do Product i dziesięć
dużych dokumentów inline. Brak błędów i zmian identyfikatorów względem R3;
lista referencji do Product ma zero pobrań treści. Pojedyncze dokumenty
zachowują Source BlueId po późniejszym `resolveDefinition`. Nie wykonywano
workflow Commerce. Wyniki są w `protocol-results.jsonl` i `protocol-summary.json`.

**Materiały.** Katalog
[/private/tmp/blue-large-identity-review-20260914-r4](/private/tmp/blue-large-identity-review-20260914-r4)
zawiera reprodukcje i logi, wariant `causal-refresh`, mediany w
`confirmation-summary.json`, surowe pomiary, wyniki testów, zamrożone źródła
i manifest SHA-256 obejmujący także nieśledzone pliki produkcyjne i testowe.
`delta-from-r3.patch` pokazuje sześć plików zmienionych od R3.

Śledzony diff HEAD ma SHA-256
`698638b0c72dc22e8a4908452af9a3eb2c36ce137a05a54c18aadde9497be27c`.
Kod produkcyjny i testy repozytorium pozostały niezmienione przez przegląd.

Spec: jeden P1; Standards: zero ustaleń.
