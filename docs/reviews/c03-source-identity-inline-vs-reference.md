# C03: tożsamość Source dla dziecka inline vs. referencja — analiza (2026-09-13)

> Raport historyczny. Aktualny kontrakt, poprawki i pomiary PR #36 opisuje
> [raport naprawy kontekstowych referencji](pr36-contextual-reference-repair.pl.md).

Dotyczy: PR bluecontract/blue-language-java#33 oraz issue bluecontract/development#24.
Wszystkie wyniki poniżej zmierzono lokalnie na tym repozytorium (branch `fix/nested-value-source-identity`
oraz `origin/next`), testem `TmpC03ReproTest` (kopia w scratchpadzie sesji, nie w repo).

## 1. Słownik (spec Blue Language 1.0)

| Pojęcie | Co to jest | Gdzie w kodzie |
|---|---|---|
| **Direct BlueId** (exact Content BlueId) | Hash dokładnego węzła po normalizacji §14. Czysta referencja `{blueId: X}` w polu wnosi do hasha rodzica dokładnie `X` (§7.3). Skalar `PLN` i `{type: Text, value: PLN}` hashują się identycznie (§14.4). | `DirectBlueIdCalculator` |
| **Source-derived BlueId** (`sourceBlueId`) | Hash **Canonical Identity Input**. Pipeline: preprocess → pełna rezolucja → kanonizacja → direct hash (§7.2). | `SourceDocumentBlueIdCalculator`, `BlueLanguageRuntime.canonicalizeWithEvidence` |
| **Resolved Form** | Dokument po nałożeniu typów (dziedziczenie, overlay, schema). Nie jest bezpośrednim wejściem hasha (§7.2.2). | `ResolutionEngine`, `TypeAssigner` |
| **Canonical Identity Input (CII)** | Jedyna, deterministyczna forma tożsamości „wyprowadzona z pełnej Resolved Form" (§13.2). Ma być **unikalna dla danej Resolved Form**. | `CanonicalIdentityInputReconstructor` |
| **Kanonizacja** | „Deterministyczny diff" Resolved Form względem tego, co wynika z łańcucha typów (§13.5): pomiń to, co wyprowadzalne z typu, zachowaj wkład instancji. | `reconstructNode`, `setTypeIfDifferent` |
| **Minimalizacja** | Mniejszy overlay źródłowy, który rezolwuje się do tej samej Resolved Form. Nie jest krokiem liczenia tożsamości (§13.1). | `MinimizedOverlayBuilder` |
| **Specjalizacja** | Nowy węzeł, który wskazuje inny węzeł jako `type` i dodaje zgodny overlay. To **nowy węzeł, nowy BlueId** (§0, §8.7). | — |
| **Ekspansja / kolaps** | Ta sama treść, więcej lub mniej zmaterializowana. **Zachowuje BlueId** (§12.6, §12.7). | `BlueGraph.expand/collapse` |
| **Primitive contribution** | Instancja podaje typ rdzeniowy (`Text`) tam, gdzie pole rodzica deklaruje typ własny wywodzący się z tego rdzenia (`Currency: type Text`). Resolver traktuje `Text` jako „składnię prymitywu", nie jako rozszerzenie typu, i zostawia `Currency` (§2.5 „provisional inference"). | `TypeAssigner` (komentarz „Primitive syntax supplies payload, not a wider declared type"), `isInheritedPrimitiveContribution` w rekonstruktorze |

Dwa zdania specyfikacji, które w tym problemie są w napięciu:

- §0 / §7.3 / §12.6–12.7: forma inline i zweryfikowana referencja to **ta sama krawędź grafu**; operacja semantyczna nie może zależeć od tego, czy węzeł był inline czy zwinięty.
- §13.4 / §13.5.1 pkt 4: jeśli źródło podało czystą referencję, a provider zmaterializował ją tylko do rezolucji, CII **MUSI zachować czystą referencję** („preserve source pure references materialized only for resolution").

Do tego §13.5.1 pkt 1 (pomiń treść wyprowadzalną z typu) i §13.7 (CII preferuje formę zmaterializowaną, nie może zależeć od preferencji kolapsu).

## 2. Jak to działa w kodzie

`BlueLanguageRuntime.canonicalizeWithEvidence`:

1. `rawPreprocess(source)` → aliasy typów zamienione na `{blueId}`.
2. `merger.resolveTypeDeclarationEvidence(preprocessed)` → Resolved Form **w trybie deklaracji** (dokument opakowany jako `type` węzła-wrappera, `Contribution.TYPE_METADATA`). Referencje do **typów** są materializowane i dostają „evidence" tożsamości; referencje do **wartości** pozostają nieprzezroczyste (Javadoc: „Pure root references remain opaque"; eksperyment w §5 to potwierdza).
3. `CanonicalIdentityInputBuilder.build(resolved, preprocessed, evidence)` → rekonstruktor chodzi po drzewie równolegle po trzech „pasach": `resolved`, `inherited` (to, co wynika z typu rodzica na tej ścieżce), `source` (preprocessed).
4. `ResolvedSnapshot.withCanonicalTypeIdentities` uruchamia rekonstruktor **drugi raz** z `source = canonical` i wymaga punktu stałego (`sameResolvedStructure`), inaczej `IllegalStateException`.
5. Direct hash CII = `sourceBlueId`.

W rekonstruktorze (`CanonicalIdentityInputReconstructor.reconstructNode`) są dwie reguły, które decydują o tym problemie:

- **R1 – diff w kontekście** (`setTypeIfDifferent`, linie 315–361 po PR): typ dziecka jest porównywany z typem odziedziczonym od pola rodzica. Przed PR #33: typ równy odziedziczonemu jest pomijany. Po PR #33: typ własny (nie-rdzeniowy) jest **zawsze emitowany**, także gdy jest odziedziczony — również w gałęzi „primitive contribution" (linie 337–341), czyli inline `type: Text` pod polem `Currency` daje w CII `type: Currency`.
- **R2 – zachowaj czystą referencję** (linie 127–130): jeśli w `source` na tej ścieżce była czysta referencja, cały wynik dla tego węzła jest zastępowany `{blueId: X}` — bez zaglądania do środka, bez diffu wobec typu rodzica.

Ponieważ `{blueId: X}` w polu hashuje się jako `X`, **parytet inline ↔ referencja zachodzi wtedy i tylko wtedy, gdy `X == hash(CII-w-kontekście(dziecko inline))`**, czyli gdy przechowywany dokładny węzeł `X` jest bajt w bajt tym, co R1 wyprodukowałaby dla formy inline.

## 3. Pomiar: pięć form tego samego znaczenia

Typy: `Currency = {name: Currency, type: Text}`, `Holder = {name: Holder, currency: {type: Currency}}`.
Przechowane wartości: `6UPP… = {type: Text, value: PLN}` (jak w issue), `GTS7… = {type: Currency, value: PLN}`.
Wszystkie pięć dokumentów ma **identyczną Resolved Form** (`currency: {type: Currency, value: PLN}`) i przez to, wg §13.2, powinny mieć jedno CII.

| Forma `currency` w dokumencie | `next` (przed PR #33) | PR #33 |
|---|---|---|
| inline `{type: Text, value: PLN}` (przypadek C03) | `844q…` | `3vZv…` |
| referencja → `6UPP…` (Text PLN) (przypadek C03) | `844q…` ✓ zgodne | `844q…` ✗ **rozjazd** |
| goły skalar `PLN` | `844q…` | `3vZv…` |
| inline `{type: Currency, value: PLN}` | `844q…` | `3vZv…` |
| referencja → `GTS7…` (Currency PLN) | `3vZv…` ✗ **rozjazd** | `3vZv…` ✓ zgodne |
| test PR: `Holder → Terms` (obiekt z typem własnym) | ✗ rozjazd | ✓ zgodne |

CII inline: `next` → `currency: {type: Text, value: PLN}` (typ Currency pominięty jako odziedziczony, skalar normalizuje się do Text);
PR #33 → `currency: {type: Currency, value: PLN}`. CII z referencją w obu wersjach: `currency: {blueId: …}` verbatim.

Wniosek z tabeli: **PR #33 nie usuwa asymetrii, tylko przesuwa ją**. Przed PR parytet miały referencje do węzłów typowanych rdzeniowo (`Text PLN`), a nie miały go obiekty/skalary typowane typem własnym. Po PR jest dokładnie odwrotnie. Issue #24 zaobserwowało tę drugą połowę na buildzie zawierającym tę zmianę.

Przypadek `raw Amount` z issue to ten sam mechanizm: surowo opublikowany `{type: Amount, amountMinor: 3990, currency: PLN}` (id `57tB…`) nie jest równy CII-w-kontekście (`currency` dostaje `type: Currency`, id `B9rV…`), więc referencja do niego daje inny `sourceBlueId` niż inline. Kontrola „opublikuj CII i do niego się odwołaj" przechodzi, bo wtedy `X` jest z definicji wierne.

## 4. Dlaczego to jest trudne

1. **Wiele dokładnych węzłów, jedno znaczenie.** `Text PLN` (`6UPP…`), `Currency PLN` (`GTS7…`), goły `PLN`, surowy Amount – pod polem `Currency` wszystkie rezolwują się do tej samej Resolved Form, ale mają różne direct BlueId. R2 „zamraża" w CII ten konkretny `X`, który autor akurat wskazał. R1 produkuje dla formy inline jedną, kontekstową postać. Żadna pojedyncza reguła R1 nie dogoni jednocześnie wszystkich możliwych `X`. Można ją tylko dostroić do jednej rodziny przechowywanych reprezentacji — co właśnie robi PR #33.
2. **Trzy wymagania specyfikacji, z których spełnić da się dwa**: (a) inline ≡ referencja (§0, §7.3), (b) CII unikalne dla Resolved Form i wolne od treści wyprowadzalnej (§13.2, §13.5.1 pkt 1), (c) zachowaj czystą referencję źródłową (§13.4, §13.5.1 pkt 4). Obecny kod wybiera (c) + „część (b)"; PR #33 dodatkowo łamie (b), emitując typ wyprowadzalny z rodzica, by przybliżyć się do (a) dla jednej rodziny przypadków.
3. **Architektura pasa tożsamości.** Pas `canonicalizeWithEvidence` celowo nie materializuje referencji do wartości (identity locality, §14.11; tryb deklaracji). Próba „kanonizowania przez referencję" w samym rekonstruktorze (prototyp: zachowaj `{blueId: X}` tylko gdy `X == hash(kanon-w-kontekście)`) kończy się: na `next` pustym dzieckiem (w pasie resolved nie ma `value`), na PR #33 `{type: Currency}` bez wartości i wyjątkiem punktu stałego w `withCanonicalTypeIdentities`. Realna implementacja wymaga zmiany w resolverze (materializacja referencji wartości w pasie tożsamości, cache `VerifiedReferenceEntry`, evidence typów dla poddrzewa), nie kilkulinijkowej poprawki.
4. **Fixtury konformancji pinują R2**: `R_provider_reference_canonicalizes_back`, `R_provider_reference_with_overlay_keeps_overlay` (specs/conformance/language/fixtures/resolver). Zmiana R2 to zmiana kontraktu 1.0, nie tylko kodu.
5. **Każda zmiana zmienia istniejące `sourceBlueId`** (PR #33 to przyznaje). Trzeba wiedzieć, które ID w Coordination/Mini/repo są już utrwalone.

## 5. Ustalenia dodatkowe

- `TypeAssigner` **akceptuje** `type: Text` pod polem `Currency` jako „primitive contribution", stąd `conformanceStatus: VALID` dla obu form w issue. Odrzucenie tego (opcja B poniżej) byłoby zmianą reguły §8.4.1 pkt 4 vs §2.5.
- Minimalizacja formy inline zwraca `currency: {type: Text, value: PLN}` — czyli minimizer zdejmuje `Currency` jako wyprowadzalne, a kanonizacja po PR #33 je dodaje. To pokazuje, że PR #33 rozjeżdża kanonizację z minimalizacją w ocenie, co jest „wyprowadzalne".
- `exactContentBlueId` równy dla obu form (`844q…` w pomiarze, `CKGD…` w issue) to trywialna konsekwencja §7.3 i nie mówi nic o tożsamości Source.
- Uruchamianie testów `:blue-language-core:test` na tej maszynie wymaga obejścia zepsutej Rosetty (toolchain Java 8 x86). Działa init-script Gradle podmieniający `javaLauncher` testów na JDK 25 (`-I …/jdk25-tests.gradle`).

## 6. Opcje decyzji (do rozstrzygnięcia przez właściciela specyfikacji)

| Opcja | Co zmienia | Co spełnia | Koszt |
|---|---|---|---|
| **A. Kanonizacja „przez" referencję** – zachowaj `{blueId: X}` tylko gdy `X` jest wierne kanonowi w kontekście; inaczej zmaterializuj CII dziecka | resolver (pas tożsamości materializuje referencje wartości), rekonstruktor, fixtury R_provider_reference_* | (a) i (b) dla **każdej** przechowywanej reprezentacji; zgodna z §13.7 („preferuj formę zmaterializowaną", „nie zależy od preferencji kolapsu"); PR #33 staje się zbędny (można wrócić do pomijania typu wyprowadzalnego) | zmiana ID dokumentów z „niewiernymi" referencjami; słabsza identity locality dla Source id (ale pełna rezolucja i tak wymaga providera); zmiana kontraktu 1.0 pkt 4 tie-breakera |
| **B. Reguła odrzucenia** – jawne `type: Text` pod polem `Currency` niedozwolone (widening) | `TypeAssigner`, konformancja | spójne odrzucenie C03 dla obu form | nie zamyka problemu ogólnie (obiekty z nadmiarową treścią, surowy Amount nadal „niewierne"); Mini publikuje skalary właśnie jako `{type: Text, value: …}`; trzeba rozstrzygnąć goły skalar (§2.5) |
| **C. Zostawić R2 i doprecyzować spec** – Source id dokumentu z referencją identyfikuje „dokument z tą referencją jako liściem"; parytet inline ≡ ref gwarantowany tylko dla direct BlueId | tylko dokumentacja + test z oczekiwaną nierównością | zero zmian ID | wprost sprzeczne z §0/§13.2 i z kryteriami akceptacji issue #24; PR #33 wtedy też traci sens |
| **D. PR #33 as-is** | — | Terms/Title | wprowadza rozjazd C03 (tabela §3), emituje typ wyprowadzalny (§13.5.1 pkt 1), zmienia ID |

Rekomendacja autora analizy: nie mergować PR #33 w obecnej postaci; potraktować go jako część decyzji A vs C. Jeśli celem jest niezmiennik „inline ≡ referencja" dla `sourceBlueId`, jedyną opcją, która go daje dla dowolnej przechowywanej reprezentacji, jest A, i wymaga ona pracy w resolverze, a nie w rekonstruktorze.

## 7. Pojęcia na zmierzonych przykładach (dodatek 2026-09-13)

Wszystkie ID poniżej pochodzą z lokalnego pomiaru (`TmpConceptsTest`, kopia w scratchpadzie sesji). Skróty:
`Text = GX7C…`, `Integer = E2LM…`, `Currency = A6bE…`, `Holder = 3WZs…`, `Product = ELnv…`, `Price = 96zh…`.

### 7.1 Direct BlueId

Hash dokładnego węzła po normalizacji §14. Nie patrzy na typy rodzica ani na providera.

| Wejście | Direct BlueId |
|---|---|
| `PLN` | `6UPP…` |
| `{type: Text, value: PLN}` | `6UPP…` (goły skalar dostaje wnioskowany typ rdzeniowy, §14.4) |
| `{type: Currency, value: PLN}` | `GTS7…` (inny typ efektywny = inny węzeł) |
| `{a: 1, b: PLN}` | `Bip8…` |
| `{a: {type: Integer, value: 1}, b: {type: Text, value: PLN}}` | `Bip8…` |
| `{a: 1, b: {blueId: 6UPP…}}` | `Bip8…` (czysta referencja wnosi dokładnie `X`) |
| `{a: 1, b: PLN, c: null}` | `Bip8…` (pole `null` czyszczone) |
| `{b: PLN, a: 1}` | `Bip8…` (kolejność bez znaczenia) |
| `{a: 1.0, b: PLN}` | `DcNG…` (Double ≠ Integer) |

Ekspansja i kolaps: `Order = {name: Order, price: {blueId: Price}}` i ten sam Order z ciałem Price wpisanym inline
mają identyczny direct BlueId `8ArB…`; `collapse(expand(Order))` zwraca `{blueId: 8ArB…}`.

### 7.2 Source BlueId i kanonizacja jako diff

Typ `Product = {price: {type: Integer}, currency: {type: Currency, value: PLN}}` (waluta jest wartością stałą typu).

| Dokument źródłowy | Direct BlueId | CII | Source BlueId |
|---|---|---|---|
| S1 `{type: Product, price: 5, currency: PLN}` | `98fh…` | `{type: Product, price: 5}` | `6VqP…` |
| S2 `{type: Product, price: 5}` | `6VqP…` | `{type: Product, price: 5}` | `6VqP…` |
| S3 S2 przez alias `blue.imports` | `6VqP…` | to samo | `6VqP…` |
| S4 `{type: Product, price: {type: Integer, value: 5}}` | `6VqP…` | to samo | `6VqP…` |
| S5 `{type: Product, price: 5, currency: EUR}` | `3Vva…` | — | odrzucone: „Node values conflict" |

Resolved Form S1–S4 jest identyczna (zawiera `currency: {type: Currency, value: PLN}`), CII jest jedno, a `currency`
znika z CII jako wyprowadzalne z typu. Direct BlueId S1 ≠ Source BlueId S1, bo S1 ma jawnie wpisany skalar, który
w CII nie występuje. Direct BlueId S2 = Source BlueId S2, bo S2 jest już własnym CII.

### 7.3 Specjalizacja vs ekspansja

`PLN Price = {name: PLN Price, type: {blueId: Price}, currency: PLN}` → CII
`{name: PLN Price, type: {blueId: 96zh…}, currency: {type: Currency, value: PLN}}`, Source BlueId `FmT9…` ≠ `96zh…`;
`isSubtype(PLN Price, Price) = true`. Nowy węzeł. Ekspansja `{blueId: Price}` zwraca ciało Price i ten sam BlueId.

### 7.4 Primitive contribution

Pole `Holder.currency` deklaruje `Currency` (a `Currency: type Text`).

| `currency` w instancji | Wynik | CII dziecka (PR #33) | Source |
|---|---|---|---|
| `PLN` | OK, resolved `Currency PLN` | `{type: Currency, value: PLN}` | `3vZv…` |
| `{type: Text, value: PLN}` | OK, resolved `Currency PLN` | `{type: Currency, value: PLN}` | `3vZv…` |
| `{type: Currency, value: PLN}` | OK | `{type: Currency, value: PLN}` | `3vZv…` |
| `{type: Integer, value: 5}` | odrzucone: „not a subtype" | — | — |
| `5` | odrzucone (Integer nie jest przodkiem Currency) | — | — |
| `{type: Code, value: PLN}` gdzie `Code: type Text` | odrzucone (rodzeństwo, nie przodek) | — | — |

Reguła z `TypeAssigner`: jawny typ rdzeniowy w instancji jest akceptowany, jeśli zadeklarowany typ pola **wywodzi się**
z tego typu rdzeniowego. Wtedy typ efektywny to typ pola, a instancja wniosła tylko payload. Dlatego `{type: Text,
value: PLN}` i `PLN` znaczą pod `Currency` to samo, a przechowany osobno `{type: Text, value: PLN}` (`6UPP…`) to
inny dokładny węzeł niż `{type: Currency, value: PLN}` (`GTS7…`), choć w tym kontekście oba rezolwują się identycznie.

## 8. Implementacja (gałąź `fix/reference-canonical-parity`, 2026-09-14)

Gałąź od `origin/next` (rc.25). Kod produkcyjny PR #33 nie jest na niej bazą; jego testy regresyjne
(`NestedValueReferenceIdentityTest`) zostały przeniesione jako wymagania.

### 8.1 Co się zmienia

1. **Pas tożsamości materializuje referencje do wartości tam, gdzie kontekst tego wymaga.**
   `CompletedValueValidator.materializePendingDefinitionReferences` wykonuje oczekujące materializacje
   (tylko dla kandydatów kompletnych, których cel ma typ, payload albo schema zależną od payloadu)
   wewnątrz poddrzewa autorskiego typu inline, zanim `DeclaredTypeContributionResolver` zapisze jego
   tożsamość i odłączy kopię ciała. Dotyczy to także dokumentu głównego kanonizacji, bo pas tożsamości
   opakowuje go jako `type` wrappera. Referencje w pozycjach bez ograniczeń nie są pobierane.
2. **Resolver zapisuje dokładną, zweryfikowaną treść każdej zmaterializowanej referencji** w evidence
   (`CanonicalTypeIdentityIndex.recordVerifiedReferenceContent`, `CanonicalTypeIdentityLookup.findVerifiedReferenceContent`).
3. **Rekonstruktor kanonizuje zmaterializowaną referencję tą samą ścieżką co treść inline**, z dokładną
   treścią jako pasem źródłowym, a potem zachowuje formę `{blueId: X}` tylko wtedy, gdy hash kanonu w
   kontekście równa się `X` (`preserveFaithfulReference`). Referencja niezmaterializowana pozostaje liściem.
4. **Reguła typów z PR #33 zostaje** (typ własny dziecka jest emitowany, także gdy jest odziedziczony z pola).
   Powód zmierzony: bez niej żaden kanonicznie opublikowany, typowany węzeł nie jest „wierny” pod polem tego
   samego typu (typ jest pomijany), więc każda taka referencja byłaby materializowana, a `exactContentBlueId`
   i `sourceBlueId` rozjeżdżałyby się dla dokumentów autorowanych kanonicznie. Kryteria akceptacji issue #24
   wymagają, aby kontrole „opublikuj CII i odwołaj się” zachowały równość obu identyfikatorów; spełnia to
   tylko ta kombinacja. Odwrócenie tej reguły to jedna zmiana w `setTypeIfDifferent`.
5. **Dwie wcześniejsze wady rekonstruktora, które stały się widoczne po (3):**
   deklaracje bez payloadu z łańcucha typu dziecka przeciekały do CII jako wkład instancji, bo pas
   „inherited” znał tylko surową deklarację z typu rodzica. Widoczne było to już na `next` dla `contracts`
   (`order`, `event`, opisy i `schema` z typu `Handler`) oraz dla granic typów cyklicznych (`owner`).
   Naprawa: `declaredProperty` (fallback do deklaracji z łańcucha typu efektywnego węzła) oraz reguła
   „nieautorskie i bez payloadu ⇒ wyprowadzalne” (`isPayloadFree`; `blueId` na rozwiniętej treści to metadane,
   nie payload). Reguła działa tylko tam, gdzie pas źródłowy jest kompletną treścią autorską węzła; pod nakładką
   pozycyjną `$pos` elementu listy jest wyłączona, bo §13.6 wymaga pełnego payloadu listy (flaga `sourceComplete`).

### 8.2 Wyniki

| Forma `currency` pod `Holder.currency: Currency` | Source id | CII dziecka |
|---|---|---|
| inline `{type: Text, value: PLN}` | `844q…` | `{type: Currency, value: PLN}` |
| goły `PLN` | `844q…` | j.w. |
| inline `{type: Currency, value: PLN}` | `844q…` | j.w. |
| referencja → `Text PLN` (`6UPP…`) | `844q…` | zmaterializowane `{type: Currency, value: PLN}` |
| referencja → `Currency PLN` (`GTS7…`) | `844q…` | zachowana `{blueId: GTS7…}` (wierna) |

Raw Amount z issue: inline, referencja do surowej publikacji i referencja do publikacji kanonicznej dają jeden
Source id; publikacja kanoniczna zachowuje `exact == source`. Referencje w pozycjach nietypowanych nie są
pobierane (test z providerem bez tej treści).

Testy dostosowane do semantyki parytetu: `EffectiveFragmentationCatalogTest` (inline i fragment `contracts`
mają teraz jeden Source id), `DeferredSnapshotCacheIsolationTest` (fixture `body` przez własny typ, publikacja
kanoniczna; wzorzec „instancja jako typ i wartość” jest w całości wyprowadzalny i znika z CII),
`RecursiveTypeResolutionTest` (referencja wierna zostaje, tożsamość równa formie inline).

Środowisko: testy modułów wymagają init-scriptu Gradle podmieniającego launcher na JDK 25 (zepsuta Rosetta dla
toolchainu Java 8 x86); `verifySourceDevelopment` i `rootedResourceFollowupBindingsTest` padają lokalnie z
powodu Pythona 3.9 (brak modułu `yaml`, `fromisoformat` bez sufiksu `Z`), niezależnie od zmiany.

### 8.3 Poprawki po przeglądzie (2026-09-14)

1. **Evidence z cache traciło treść referencji.** `CanonicalTypeIdentityIndex.importComplete` nie importowało
   `verifiedReferenceContents`; po rozgrzaniu przez `resolve()` kanonizacja nie miała dokładnej treści referencji.
   Naprawione: import w `importComplete`, klucze mapy w `equals`/`hashCode` snapshotu evidence, forwardowanie
   `findVerifiedReferenceContent` w czterech wrapperach lookupu (`EffectiveTypeChecks`, `CanonicalTypeIdentityEvidenceUnion`,
   dwa w `CanonicalIdentityEvidence`).
2. **Zagnieżdżone referencje mogły przerwać kanonizację.** Treść zewnętrznej referencji rezolwuje się osobno; jej
   własne odroczone referencje wskazywały na oryginalne węzły, a do celu trafiał klon. Naprawione: `materializedReference`
   dopełnia oczekujące referencje przed klonowaniem i przed zapisem do cache. Test: `shouldDeriveOneSourceIdentityThroughNestedReferencesColdAndWarm`.
3. **Surowy węzeł w pozycji nietypowanej: inline ≠ referencja.** To świadoma granica wariantu ograniczonego: referencja
   w pozycji, której kontekst nic nie wnosi, pozostaje liściem i nie jest pobierana, więc parytet zachodzi tam tylko dla
   publikacji kanonicznych. Na `next` przypadek raw Amount przechodził „przypadkiem”, bo reguła pomijania typu
   odziedziczonego czyniła surowy węzeł własnym CII. Pełny parytet wymaga materializacji każdej referencji w pasie
   tożsamości (koszt: pobranie także treści dokumentów wskazanych przez listy bez `itemType`).
   **Decyzja właściciela z 2026-09-14: pozostaje wariant ograniczony.** Automatyczne pobieranie treści
   dokumentów wskazanych przez takie listy przy obliczaniu `sourceBlueId` jest niedopuszczalne.

### 8.4 Ustalona granica kontraktu (2026-09-14)

Referencja wartości pozostaje dokładnym liściem tożsamości, jeśli jej kontekst nie wymaga treści.
Dotyczy to elementów list bez efektywnego `itemType`, odziedziczonego payloadu i innych ograniczeń,
które wymagałyby otwarcia tych elementów. Brak autorskiego `itemType` nie usuwa ograniczenia
odziedziczonego z typu. Dostępność treści u providera ani stan cache nie rozszerzają zakresu materializacji.

Nie ma kosztu pobrania treści dokumentów wskazanych przez elementy takiej listy. Sam fold listy
nadal korzysta z ich BlueId. Parytet Source inline/referencja w tych pozycjach gwarantuje publikacja
kanoniczna; surowa publikacja może dać inny Source BlueId po wpisaniu inline. Bezpośrednia tożsamość
dokładnej treści inline/referencja pozostaje równa. Przypadki Currency/Amount pod polami typowanymi
z issue #24 nadal wymagają parytetu także dla surowej publikacji.

Punkt 3 przeglądu jest przy tym zakresie zaakceptowaną granicą kontraktu. Nie należy usuwać jej przez
rozszerzenie `needsContent` na wszystkie referencje. Regresję kosztu chroni test
`shouldCalculateListSourceIdentityWithoutFetchingReferencedDocuments`: provider ma 100 dokumentów,
lecz każde żądanie ich treści podczas liczenia Source identity powoduje błąd, na zimnym i ciepłym cache.

Uzgodnione brzmienie do włączenia do następnej zmiany normatywnej §13 (wraz z odpowiadającymi
jej wektorami i powiązaniami wydania):

> During Source canonicalization, a pure value reference whose effective context requires no
> content MUST remain an exact identity leaf and MUST NOT be materialized solely for identity
> calculation; this includes referenced list elements without effective item-type or other
> payload-dependent constraints, and inline/reference Source identity parity at such paths is
> guaranteed when the referenced exact content is already Canonical Identity Input.

To brzmienie zapisuje decyzję dla rozwijanej implementacji; nie aktualizuje samo z siebie
utrwalonych, identyfikowanych hashem paczek specyfikacji 1.0. Zmiana normatywna musi też uzgodnić
§13.4 i §13.5.1 pkt 4 z kanonizacją referencji, których kontekst wnosi typ lub payload.

### 8.4 Wydajność po przeglądzie dużych dokumentów (2026-09-14)

Punkt odniesienia: `docs/reviews/source-identity-large-documents-2026-09-14.md` (1000 typowanych referencji po 50 pól:
0,65 s → 5,9 s). Przyczyny i poprawki:

1. **Snapshot evidence kopiował mapę treści całego wywołania** (kwadratowo, plus wypieranie wpisów cache).
   Treści referencji żyją teraz w `VerifiedReferenceContents`; snapshot jednej referencji zawiera tylko treści
   referencji zagnieżdżonych w niej (`nestedWithin`), bez własnej (tę wpis cache ma już jako `canonicalContent`), a
   waga snapshotu liczy klucze, nie ciała węzłów, bo są to obiekty współdzielone z wpisami cache. Region
   `verifiedReferences` wraca do wagi HEAD (23,7 MB vs 23,6 MB dla 302 wpisów), bez wypierania i ponownych pobrań.
2. **Dopełnianie referencji skanowało poddrzewo i kandydatów od nowa po każdym elemencie.** Teraz przegląda tylko
   kandydatów zaobserwowanych od znacznika (`candidateMark`) pobranego przed zagnieżdżoną rezolucją, a zbiór węzłów
   poddrzewa buduje raz i rozszerza przyrostowo o zmaterializowaną treść. Koszt liniowy.
3. **Pas tożsamości scalał dokument dwukrotnie.** Dokument opakowany jako `type` wrappera był scalany także do celu
   wrappera, którego nikt nie czyta, a końcowa walidacja materializowała tam wszystkie referencje raz jeszcze (HEAD
   ponosił ten koszt bez treści). `isDiscardedDeclarationWrapper` pomija to scalanie.
4. **Wierność referencji bez hashowania w typowym przypadku.** Jeśli kanoniczne dziecko jest strukturalnie równe
   dokładnej treści referencji (porównanie `NodeWireForm`), jest wierne bez liczenia BlueId; hash tylko w pozostałych
   przypadkach. Konwersja `FrozenNode → Node` treści jest memoizowana per BlueId.

Pomiar tym samym harnessem (`LargeIdentityProbe`, mediana z trzech ciepłych wywołań, provider w pamięci), HEAD → po poprawkach:

| Scenariusz | zimno | ciepło | alokacje ciepło |
|---|---|---|---|
| `typed_unique` 300 × 50 pól | 323 → 349 ms | 96 → 133 ms | 313 → 446 MB |
| `typed_unique` 1000 × 50 pól | 827 → 1056 ms | 660 → 916 ms | 1842 → 2307 MB |
| `typed_shared` 1000 × 1 | 300 → 250 ms | 187 → 144 ms | 787 → 526 MB |
| `opaque_list` 10 000 | 1575 → 1596 ms | 1626 → 1581 ms | bez zmian, 0 pobrań |
| `raw_amount_list` 100 | 118 → 105 ms | 57 → 46 ms | parytet zachowany |
| `nested_raw` 5 | 124 → 111 ms | 26 → 21 ms | parytet: HEAD `false`, teraz `true` |

`snapshots().resolve` w trybie instancji dla 300 × 50: 76–106 ms → 83–124 ms, bez ponownych pobrań (cache 302 wpisy,
0 wypierań). Pozostała różnica na `typed_unique` (~1,4× na ciepło) to koszt nieunikniony w tym projekcie: pas
tożsamości scala treść typowanych referencji tak jak tryb instancji, a rekonstruktor kanonizuje ją w kontekście.

### 8.5 Drugi przegląd (2026-09-14)

1. **Osierocone węzły kandydatów.** Kandydat referencji zaobserwowany zanim jego otaczająca wartość (inline dziecko z
   typem) została scalona do nowego celu wskazywał węzeł spoza końcowego drzewa; dopełnianie pomijało go, CII traciło
   `value: PLN`, a dla `schema: required` kontrola punktu stałego rzucała wyjątek. Naprawa: gdy węzeł kandydata nie leży
   w poddrzewie, dopełnianie odnajduje żywy węzeł pod tą samą ścieżką (`NodePathEditor`) i materializuje do niego, jeśli
   nadal oczekuje tej referencji. Przegląd kandydatów powtarza się od znacznika w każdej iteracji, więc kandydat, który
   nabył oczekującą referencję później, nie jest pomijany. Test: `shouldCompleteReferencesNestedInInlineTypedChildrenAndListItems`
   (pole i lista, na zimno, po `resolve` i na ciepło).
2. **Mutowalne evidence.** `VerifiedReferenceContents.find` zwraca teraz świeży klon; rekonstruktor pobiera treść raz i
   przekazuje ją do sprawdzenia wierności.
3. **Waga cache.** Snapshot evidence liczy ciała zatrzymanych treści referencji; region `verifiedReferences` dla 300 × 50
   waży 30,5 MB (HEAD 23,6 MB, różnica to treści zatrzymane przez snapshot dokumentu głównego), bez wypierań.

Pomiar po poprawkach: `typed_unique` 300 × 50 na ciepło 106 → 146 ms, 1000 × 50 691 → 948 ms; `typed_shared` i `nested_raw`
szybsze niż HEAD; `resolve` w trybie instancji 81–119 → 91–127 ms.

### 8.6 Trzeci przegląd (2026-09-14)

1. **Referencja do całej typowanej listy i węzły spoza poddrzewa.** Fallback po ścieżce działa także dla referencji,
   których materializacja tworzy nowe dzieci; zbiór węzłów poddrzewa rośnie o zmaterializowaną treść.
   Test: `shouldCompleteReferencesInsideAReferencedTypedList` (lista jako jeden węzeł, na zimno, po `resolve`, na ciepło).
2. **Odziedziczona wartość stała blokowała dopełnienie.** Fallback odrzucał żywy węzeł z wartością z typu. Teraz o tym,
   czy węzeł jest już zmaterializowany, decyduje jawny zbiór `materializedReferenceTargets` w stanie rezolucji, nie
   obecność payloadu. Przy okazji: zmaterializowana referencja, która nie wnosi nic ponad odziedziczoną treść stałą,
   jest pomijana w CII dokładnie jak ta sama treść inline (wcześniej zostawał pusty `{type: Currency}` i kontrola
   punktu stałego rzucała wyjątek). Test: `shouldOmitReferencedContentThatOnlyRepeatsAnInheritedFixedValue`.
3. **Waga memoizowanej kopii.** `VerifiedReferenceContents.find` nie zatrzymuje już kopii `Node`; każdy lookup
   materializuje zamrożoną treść na nowo, więc waga evidence odpowiada zatrzymanym obiektom.

### 8.7 Czwarty przegląd (2026-09-14)

`addSubtreeNodes` przerywało przejście, gdy korzeń był już członkiem zbioru, więc dzieci dołączone przez materializację
(np. elementy listy przez referencję, których typ pochodzi dopiero z `itemType` holdera) nie trafiały do zbioru i ich
własne referencje nie były dopełniane. Przejście używa teraz osobnego zbioru odwiedzonych i zawsze dodaje potomków.
Test: `shouldCompleteScalarReferencesTypedOnlyByTheEnclosingListItemType` (reprodukcja z raportu R4). Samodzielna
reprodukcja recenzenta `TypedListCurrencyReferenceRepro` kończy się bez rozbieżności.
