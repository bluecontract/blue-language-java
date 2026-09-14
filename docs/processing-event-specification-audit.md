# `$processingEvent` w managed closure — ograniczenia specyfikacji

Data: 2026-09-11. Zakres: dostarczony pakiet pierwotnych źródeł specyfikacji; archiwalna ocena proponowanego kierunku naprawy development#22, przed implementacją poprawki. Audyt nie zmieniał implementacji ani specyfikacji. Blue Contracts oznacza dokument final normative; Coordination ma status candidate. Poniżej „norma” oznacza wymaganie źródła, a „wniosek” lub „zalecany test” — ocenę wynikającą z jego lektury.

Odwołania poniżej podają dokument i numer wiersza w audytowanym pakiecie, który nie jest częścią tego repozytorium. Tożsamości plików audytu:

- Contracts: `specifications/blue-contracts-and-processor-specification-1.0.md`, `sha256:dfb444962a5a17b3a6519e8d148c2bf4a975a921b1fcb1277710052caaecd930`.
- BEX: `specifications/blue-bex-specification-2.0.md`, `sha256:1725878bcb59f2d2a60bae2ada582a18dc964f4dbc61377aaae195a773765f92`.
- Coordination: `specifications/blue-coordination-specification-1.0-candidate.md`, `sha256:0c7d4e41c7ffcc7270619a5d7871c71478901b5adc93106091cfaf825740c059`.
- BEX-H-06: `conformance/bex/fixtures/h/bex-h-06.yaml`, `sha256:ae59f52d1d227e16472c86df67707a0f333145fe7c1101eb88c9749bb81f72c6`.

Stan wdrożonej poprawki opisuje [przewodnik migracji](language-1.0-contracts-kernel-1.0-migration.md#original-processing-event-across-closure-steps).

## Rozstrzygnięcie

**Norma:** dla kroków Document Update, Triggered Event, Lifecycle Event i Embedded Event `$event` jest bieżącym payloadem, a `$processingEvent` pozostaje oryginalnym eventem zewnętrznym całego invocation. Contracts dodatkowo wymaga braku `$processingEvent` w invocation admission i managed revision. To rozróżnienie dotyczy rodzaju przyczyny całego invocation, nie rodzaju bieżącego kroku. Źródła: BEX §5.3 (BEX, wiersz 558), Contracts §4.11 (Contracts, wiersz 2357), Contracts A.20 — lifecycle initialization (Contracts, wiersz 5696).

**Wniosek:** przekazanie niezmiennego oryginalnego eventu do dalszych kroków tego samego zewnętrznego invocation realizuje wymaganą semantykę. Nie ma podstawy do ustawienia `$processingEvent` na payload każdego bieżącego kroku ani do odtwarzania historycznego eventu z dowodów admission/revision.

## Warunki, których naprawa musi dochować

1. **Jedno źródło przyczyny.** Event występuje dokładnie raz jako `ExternalCause.event`; API nie może przyjmować drugiego niezależnego eventu/cause o niejasnej relacji z zamkniętym wejściem. Stanowy `AffectedClosureSnapshot` nie obejmuje przyczyny ani routingu. Wniosek implementacyjny: dodatkowa informacja przekazywana wewnętrznie do step runtime musi być pochodną zweryfikowanego invocation, a nie niezależnym caller override ani częścią trwałego stanu dokumentu. Contracts §0.2 (Contracts, wiersz 122), §2.2 (Contracts, wiersz 484).

2. **Izolacja dokumentu nadal obowiązuje.** `$document` pozostaje wyłącznie dokumentem docelowym, `$scope` jest `/` dla closure. Runtime nie otrzymuje rodzica, rodzeństwa, kontenerów, reverse indexes ani całego closure. Norma dotycząca admission wprost dopuszcza jawny causal `$processingEvent` obok tej izolacji. Wniosek: samo ujawnienie dopuszczonego original eventu nie łamie izolacji; wstrzyknięcie sesji closure lub danych kontenerów do kontekstu już by ją łamało. Contracts §0.2 (Contracts, wiersz 157), admission (Contracts, wiersz 173), §2.2.2 (Contracts, wiersz 709), §4.11 (Contracts, wiersz 2371).

3. **Kontekst przyczyny nie nadaje uprawnień ani nowego routingu.** Feeder ustala zewnętrzną eligibility/authority, a External Channel jest oceniany tylko dla zamrożonego direct occurrence. Nowy dokument lub Channel utworzony przez event `E` nie staje się nowym bezpośrednim odbiorcą `E`; może przejść initialization i reakcje wewnętrzne. Checkpoint należy do zaakceptowanego source Channel i zapisuje się po całkowitym uspokojeniu closure. Wniosek: obecności `$processingEvent` nie wolno używać jako warunku ponownego external acceptance, uruchomienia operacji `go` na każdym potomku ani dodania checkpointu. Contracts §0.3 (Contracts, wiersz 205), §3.3.2 (Contracts, wiersz 1941), §6.2 (Contracts, wiersz 2788), §7.5 (Contracts, wiersz 3279).

4. **Attribution nie jest authority.** Coordination zabrania podmiany aktora, źródła, timestampu i Mandate Authority w original Timeline Entry. Sam tekst operacji, nazwa lub claim nie nadaje uprawnień. Wniosek: handler może odczytać oryginalnego aktora, ale nie wolno utożsamiać go automatycznie z authority holder, zwłaszcza przy delegacji. Coordination §17.9 (Coordination, wiersz 1375), §23.2–23.4 (Coordination, wiersz 1745).

5. **Admission i managed revision nadal bez eventu.** `AdmissionCause.triggeringEventBlueId` i `parentTransitionIdentity` są dowodami admission. `ManagedRevisionCause.originalSourceCauseIdentity` jest dowodem historycznej przyczyny źródłowej, a osobny managed-revision invocation ma własny meter, rollback i commit. To nie jest jego external event. Zewnętrznie spowodowany lifecycle/init w obrębie bieżącego `PROCESS_CLOSURE` zachowuje natomiast jego original event. Contracts §2.3 — revision/admission (Contracts, wiersz 812), audit cause i osobne invocation (Contracts, wiersz 841), §4.11 (Contracts, wiersz 2365), A.20 (Contracts, wiersz 5706).

6. **Dokładna, niezmienna tożsamość; bez eager deep copy.** Original event jest admitted immutable exact node z ustalonym BlueId, bez dopisywania target paths, occurrence IDs czy internal envelopes. BEX zabrania rekurencyjnego klonowania/materializacji tylko po to, aby przekazać exact value. Brakujące zasoby nie mogą stać się `ABSENT`. Wniosek: należy przekazać istniejący niemutowalny nośnik exact identity i zachować poprawne lazy reads; samo `Node.clone()` lub rozpakowanie referencji nie jest wystarczającym projektem granicy. Contracts §2.3 (Contracts, wiersz 795), §4.13 (Contracts, wiersz 2409), §12.4–12.5 (Contracts, wiersz 4335), BEX §5.5 (BEX, wiersz 577).

7. **Jeden meter i jedna atomowa publikacja.** Każdy krok korzysta z pozostałego wspólnego budżetu. Przeniesienie istniejącej exact value nie podlega rekurencyjnej opłacie za rozmiar. Rzeczywiste odczyty wymagają liczników BEX i nie mogą być liczone ponownie jako ten sam semantic read w Contracts. Gas exhaustion wycofuje wszystkie tentative efekty całego invocation. Contracts §2.2.2 (Contracts, wiersz 719), §12.6 (Contracts, wiersz 4370), BEX §12.1–12.2 (BEX, wiersz 1303), BEX §12.5 (BEX, wiersz 1368).

## Determinizm i kontekst wdrożenia

**Norma:** wykonanie jest związane z wybranym release/registry i stałym środowiskiem; release implementacji jest dowodem audytowym. Retry po `NeedsResources` zaczyna od tego samego exact closure i cause. Reprezentacja, cache czy inlining nie mogą zmieniać stanu, zdarzeń i gas. Contracts compatibility (Contracts, wiersz 7), release evidence (Contracts, wiersz 291), wybór release (Contracts, wiersz 410), stałe środowisko (Contracts, wiersz 939), representation invariance (Contracts, wiersz 1708), retry (Contracts, wiersz 4343).

**Kontekst potwierdzony przez użytkownika:** system nie jest jeszcze wdrożony produkcyjnie; zachowanie wyników błędnej wersji nie jest wymaganiem tej poprawki. Zmiana wyników względem błędnego `ABSENT` nie stanowi więc przeszkody wdrożeniowej. Zalecany test dotyczy powtarzalności poprawionego wykonania dla tych samych dokładnych danych i środowiska, także po restarcie i przy retry. Nie ma potrzeby dodawania zgodności z dotychczasowym błędem. `implementation-artifact identity` pozostaje osobną metadaną zgodności, a nie wejściem portable invocation/commit identity. Contracts §2.6 — implementation metadata (Contracts, wiersz 1512).

## Dwa miejsca specyfikacji do doprecyzowania

- **Szkic `DocumentStepInput` pomija causal event.** Wymienia `exactPayload`, ale nie wskazuje mechanizmu dostarczenia `$processingEvent`. Jednocześnie wire representation jest implementation-defined, a widoczne wartości mają odpowiadać normom execution context. To luka w opisie przepływu danych, nie nakaz zerowania przyczyny ani zakaz dodatkowego processor-private pola pochodnego. Contracts §2.2.2 (Contracts, wiersz 682), implementation-defined wire (Contracts, wiersz 706), wymagana równoważność kontekstu (Contracts, wiersz 102).
- **Literalna równość external `$event == $processingEvent`.** BEX §5.3 stwierdza ją bez zastrzeżenia, natomiast Contracts dopuszcza funkcję `PAYLOAD` zwracającą channelized node, a `$event` definiuje jako ten payload. W Contracts §3.3 nie znaleziono normy wymagającej, aby każda funkcja `PAYLOAD` zwracała niezmieniony event. Równość wymaga doprecyzowania dla channel runtimes przekształcających payload. Nie osłabia to jednoznacznego wymagania zachowania original eventu przy delivery wewnętrznym. BEX §5.3 (BEX, wiersz 562), Contracts §3.3 (Contracts, wiersz 1867), Contracts §4.11 (Contracts, wiersz 2364).

## Zalecane sprawdzenia poprawki

Poniższe przypadki wynikają z norm powyżej; nie są nowymi wymaganiami protokołu:

- External `E` → Triggered → Document Update → Embedded: lokalne `$event` różnią się, ale `$processingEvent` i jego BlueId w każdym kroku pozostają `E`.
- Lifecycle i admission: init/lifecycle spowodowany `E` widzi `E`; osobne `ADMIT_CLOSURE` oraz invocation z `ManagedRevisionCause` nie widzą eventu, nawet z historycznym cause ID. Historyczny Timeline Entry przetwarzany podczas catch-up jest natomiast osobnym zewnętrznym cause i powinien widzieć własny event, nie event dołączenia dokumentu. Coordination §19.3 (Coordination, wiersz 1469).
- Dwa niezależne invocation `E1` i `E2`: brak przeniesienia przyczyny ze starej sesji; to samo przy retry i współbieżnym wykonaniu.
- Wiele kontenerów/cykl oraz dokument utworzony przez `E`: nie pojawiają się dodatkowe direct deliveries, parent context, checkpoints ani ponowne odtworzenie wykonanej operacji.
- Exact event inline/reference, warm/cold i brakujący zasób: identyczna semantyka i gas dla równoważnych danych; missing evidence prowadzi do właściwej granicy zasobów, nie `ABSENT`.
- Handler odczytujący nowo dostępny causal event oraz limit gas: poprawne koszty rzeczywistych odczytów i pełny rollback przy failure; brak dodatkowej materializacji/metry za samo przeniesienie eventu.
- Jeśli runtime External Channel przekształca payload: sprawdzić osobno, że `$processingEvent` pozostaje original eventem, i rozstrzygnąć przytoczone doprecyzowanie BEX przed deklaracją pełnej zgodności tego wariantu.

Istniejący fixture BEX-H-06 (BEX-H-06, wiersz 13) dostarcza oba eventy bezpośrednio w kontekście. Sprawdza odczyt wartości i warianty delivery, ale sam nie sprawdza, czy Contracts przenosi przyczynę między rzeczywistymi krokami closure. Dlatego regresja powinna przejść przez produkcyjną granicę managed closure/document-step.

## Dodatkowa ocena ryzyk w kodzie `next`

Sprawdzony head: `806536457fd2ff284159fe65439973aa0f02ca4f`. Poniżej są punkty do uwzględnienia w implementacji; gotowa poprawka nie została jeszcze napisana ani przetestowana.

- `carriedPayload` w `ManagedDocumentStepRuntime` zasila zarówno snapshot `processEvent`, jak i bezpośredni payload handlera. Nie można rozszerzyć tego samego mechanizmu na przyczynę wszystkich kroków: `ScopeExecutor` dopuszcza niepusty `admittedExternalPayload` tylko dla `EXTERNAL_DELIVERY`, a dla innych work kinds zgłasza `Carried external payload has another work role`. Oryginalna przyczyna potrzebuje osobnego powiązania od bieżącego payloadu i wybranej trasy. [Runtime](https://github.com/bluecontract/blue-language-java/blob/806536457fd2ff284159fe65439973aa0f02ca4f/blue-contracts-core/src/main/java/blue/language/processor/ManagedDocumentStepRuntime.java#L156-L194), [dispatch](https://github.com/bluecontract/blue-language-java/blob/806536457fd2ff284159fe65439973aa0f02ca4f/blue-contracts-core/src/main/java/blue/language/processor/ScopeExecutor.java#L243-L267).
- Obecność process eventu jest przekazywana także do `ProcessingResultCoordinator`, gdzie wpływa na wybór `SUCCESS`, `TERMINATED`, `STALE` lub `NO_MATCH`. To sprzężenie wymaga sprawdzenia przy zmianie wspólnych konstruktorów. Nie wykazano regresji statusu: udany managed step obecnie buduje własny `ManagedDocumentStepOutcome`, a `partialResult()` wywołuje wyłącznie przy failure. [Wybór statusu](https://github.com/bluecontract/blue-language-java/blob/806536457fd2ff284159fe65439973aa0f02ca4f/blue-contracts-core/src/main/java/blue/language/processor/ProcessingResultCoordinator.java#L302-L318), [wynik managed step](https://github.com/bluecontract/blue-language-java/blob/806536457fd2ff284159fe65439973aa0f02ca4f/blue-contracts-core/src/main/java/blue/language/processor/ManagedDocumentStepRuntime.java#L199-L228).
- `ExactEventIdentityEvidence` już przechowuje niezmienny event i zweryfikowany BlueId; jego kontrakt ostrzega przed odłączeniem pary `Node`/BlueId i samodzielnym hashowaniem. To naturalny nośnik oryginalnej przyczyny w obrębie konkretnego invocation. Przechowywanie jej we współdzielonym processorze lub globalnym cache stworzyłoby ryzyko użycia eventu innego invocation. [Kontrakt istniejącego nośnika](https://github.com/bluecontract/blue-language-java/blob/806536457fd2ff284159fe65439973aa0f02ca4f/blue-contracts-core/src/main/java/blue/language/processor/ExactEventIdentityEvidence.java#L18-L32).
