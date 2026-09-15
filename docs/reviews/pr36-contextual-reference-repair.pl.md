# PR #36: kontrakt naprawy referencji kontekstowych

## Podział na prerequisite Contracts i Source

Definicja domeny checkpointu, kaskada jej identyfikatorów oraz poprawka
`CheckpointEntry.domainBlueId()` są wydzielone do pierwszego PR.
[Szczegóły prerequisite](../checkpoint-domain-correction.md) opisują regresję:
inline i referencja muszą rozpoznawać ten sam powtórzony event. Drugi PR
zachowuje kanonikalizację Source oraz jawny typ nowych wpisów checkpointu,
którego kanoniczna reprezentacja zależy od tej zmiany Language.

Po poprawce odczytu domeny ponowna regeneracja pełnego pakietu zmieniła tylko
hash implementacji `CheckpointEntry.java` i wynikowy `releaseIdentity`.
Bajty fixture, oracle i gas pozostały identyczne. Poniższy audyt zachowuje
porównanie całej poprawki do historycznej wersji #36; bieżący diff PR jest
porównywany do wydzielonego prerequisite.


Dotyczy [PR #36](https://github.com/bluecontract/blue-language-java/pull/36)
i [C03 / development#24](https://github.com/bluecontract/development/issues/24).
Punkt wyjścia implementacji: `9eb47786cee058af3868ed581bd9fb4b7e15850c`;
aktualna baza integracji i porównania: `952b90ba5748f6b3605482700608bd0429839a83`
(`next`, po scaleniu PR #37).

## Kontrakt

- Direct BlueId pozostaje hashem dokładnego węzła. `Text PLN` i `Currency PLN`
  mają różne exact ID; zastąpienie węzła jego dokładną referencją zachowuje
  bezpośrednią tożsamość rodzica.
- Gdy kontekst interpretuje wkład, inline i zweryfikowana referencja podlegają
  tym samym regułom. W `Holder.currency: Currency` zgodny prymitywny wkład
  zachowuje efektywny typ `Currency` i wartość `PLN`.
- Obecne, nieredundantne dziecko zachowuje efektywny typ własny. Nie tworzymy
  nieobecnych pól opcjonalnych; całe pole dostarczone przez odziedziczoną stałą
  nadal podlega pominięciu. Listy zachowują pełny kanoniczny payload.
- Oryginalna referencja może reprezentować obliczony wkład kanoniczny tylko,
  jeżeli identyfikuje dokładnie ten wkład. Nie jest wyjątkiem od normalizacji.
- Nieprzezroczysta referencja exact pozostaje liściem bez pobierania dowolnego
  surowego dokumentu. Expand/collapse istniejącej tożsamości i ponowna
  interpretacja ujawnionych bajtów jako Source to różne operacje.
- Brak dowodów, błędna treść i niezgodna wartość nie stają się pustym wkładem.
  Cache i kolejność wywołań nie zmieniają wyniku semantycznego.

Normatywny opis znajduje się w Blue Language §13.2.1, §13.4–13.5.1 i R21;
§0, §7.2 oraz §10.1 określają zgodny zakres równoważności i kompletności.

## Zmiany implementacji

`ReferenceResolver` oznacza udaną materializację konkretnego wystąpienia.
`Node` i resolved `FrozenNode` przenoszą ten znacznik przez kopie. Znacznik
nie należy do wire form ani direct hash; nowe wejście Source i canonical/exact
go usuwają. Klucz interningu rozróżnia węzły z różnymi dowodami wystąpienia.

Rekonstruktor wymaga zweryfikowanych treści dla oznaczonej referencji i
przepuszcza je przez ten sam przebieg co inline. Nie wnioskuje o materializacji
z obecności typu, skalara, listy lub zwykłych pól. Dlatego zachowuje także
wkład zawierający wyłącznie `schema`.

`Dictionary.valueType` wymusza rozpoznanie i walidację zawartości entry.
Oryginalny jawny typ referowanej wartości podlega tej samej kontroli podtypu
co inline. Nie promujemy niedozwolonego `Text` do typu własnego, aby uzyskać
równość. Zwykły publiczny `merge` nadal może przyjąć rozwiązany wcześniej
mutable target bez sidecara poprzedniego wywołania.

`List.itemType` sprawdza rzeczywisty wkład po rozpakowaniu `$replace`, zarówno
inline, jak i po zweryfikowaniu referencji. Jawnie niezgodny typ nie może zostać
ukryty przez kontrolki authoring. Referowany skalar bez pola `type` w obiekcie
Java nadal podlega kontroli swojego normatywnego typu exact; pominięcie pola
przez providera nie zmienia akceptacji.

Oba wejścia patchowania traktują wartość patcha jako nowy wkład i usuwają
stare dowody wystąpienia. Zmiana typu lub schema usuwa również dowody dzieci;
zmiana niezależnego pola zachowuje dowody nietkniętych dzieci.

Pominięcie odziedziczonego pola uwzględnia obecność pustego obiektu. Wkład `{}`
nie znika tylko dlatego, że odziedziczona deklaracja `schema` ma ten sam
bezpośredni zapis metadanych. To realizuje istniejące R74–R78.

## Konieczne następstwo w Contracts

Egzekwowanie `Dictionary.valueType` ujawniło, że rejestr `CheckpointEntry`
deklarował `domain: Text`, chociaż Contracts §10.2 i A.10 definiują dokładny
węzeł domeny lub referencję do niego. Usunięto wyłącznie tę deklarację typu;
wymóg obecności i walidacja domeny przez odpowiedni runtime pozostają.

`CheckpointManager` zapisuje teraz każdy nowy wpis od razu z dokładnym typem
`CheckpointEntry`. Poprzedni nieotypowany zapis mógł otrzymać ten typ przy
następnej normalizacji, co zmieniało ID wyniku na replay lub zależnie od
inline/reference korzenia. Istniejące testy parytetu, dużych grafów i replay
sprawdzają poprawkę bez zmiany swoich asercji semantycznych.

- `CheckpointEntry`:
  `2uJq8ZJGyUpMiZckxopH2koa7ZFRavVacpu2eGdK2UwY` →
  `55JVRmcrK9fcbGmpvYv6KLtoKendvDUBrYfv3nasuQne`.
- Zależny `ChannelEventCheckpoint`:
  `Ag2NpsQnNpn8nNRopURxcWVHRnYu5REDZeS8YJcfvQUS` →
  `6H1dvQ7QnuNRhr9uRCewc7w7yn1UhZuHF2kZYhUKecQr`.

Są to różne dokładne treści. Nie wolno traktować nowych ID jako aliasów
starych. Nowe pakiety i profile muszą mieć jawne nowe wiązania; stara historia
zarządzana pozostaje związana z jej dotychczasowymi profilami.

## Niezależne oracle i powtarzalny raport

Generator
[`generate_contextual_reference_vectors.py`](../../blue-conformance/src/main/tools/generate_contextual_reference_vectors.py)
konstruuje oczekiwane węzły ręcznie i hashuje je istniejącą niezależną
implementacją direct BlueId. Nie wywołuje normalizatora Java w celu uzyskania
oczekiwanych wyników. Ten sam corpus zasila 10 nowych normatywnych fixture (łącznie 195) i
[`ContextualReferenceIdentityMatrixTest`](../../blue-language-core/src/test/java/blue/language/runtime/ContextualReferenceIdentityMatrixTest.java).

Macierz obejmuje 33 przypadki w 4 trybach, czyli 132 wiersze na commit.
Każdy wiersz raportu zawiera dokładne wejście, direct ID, canonical input,
Source ID albo kategorię błędu, resolved i odczyty providera. Tryby obejmują
zimny runtime, rozgrzewanie w obu kolejnościach oraz wcześniejszą rezolucję.
Wartości i typy Currency/Terms mają osobne asercje. Historyczne uruchomienie
używa tego samego corpus i zapisuje różnice bez zmieniania oracle.

```sh
python3 blue-conformance/src/main/tools/generate_contextual_reference_vectors.py
BLUE_IDENTITY_MATRIX_REVISION=<pelny-commit-implementacji> ./gradlew \
  :blue-language-core:test --tests '*ContextualReferenceIdentityMatrixTest'
```

Raport powstaje w
`blue-language-core/build/reports/identity/contextual-reference-matrix.json`.
`BLUE_IDENTITY_MATRIX_OUTPUT` pozwala wskazać plik; ustawienie
`BLUE_IDENTITY_MATRIX_REPORT_ONLY=1` służy wyłącznie pomiarowi starszej wersji.
Końcowa bramka uruchamia asercje.

Każde wykonanie zapisuje hashe SHA-256 dokładnego corpus i źródła testu.
Generator raportu wymaga wszystkich 132 par przypadek/tryb, zgodnych danych
providera i identycznego harnessu w trzech wykonaniach. Przypadki negatywne
muszą zostać odrzucone zarówno przy przygotowaniu Source, jak i rezolucji.
Zgodnie obcięte raporty lub stary harness nie mogą otrzymać wyniku pozytywnego.

Końcowy, przypięty do commitu [raport porównawczy](../../reports/identity/pr36/README.md)
zawiera też [pełny wpływ na zależne identyfikatory](../../reports/identity/pr36/dependency-changes.json).
Wyniki wcześniejszych commitów wykonano z tym samym nowym testem, zachowując
ich niezmieniony kod produkcyjny. Liczba rozbieżności oznacza niespełnione
asercje; jeden wiersz może naruszać więcej niż jedną asercję.

Szersze regresje w
[`ContextualReferenceCanonicalizationTest`](../../blue-language-core/src/test/java/blue/language/runtime/ContextualReferenceCanonicalizationTest.java)
obejmują puste i fałszywe wartości, fixed values, definicje, brak i fałszowanie
treści, dwa konteksty jednej referencji oraz zapis/odczyt kanonicznej Listy
w nowym runtime. Istniejące testy `RawReferenceSourceIdentityTest` sprawdzają
referencje do elementów i całych List oraz zagnieżdżonych Amount.

## Audyt skutków zależnych

[Zamknięty zapis przeglądu](../../blue-conformance/src/main/tools/migration/pr36-contextual-reference-transition.json)
obejmuje 108 rzeczywistych zmian plików spośród 383 plików pakietu Contracts.
Każda para jest związana hashami bajtów sprzed i po naprawie. Generator raportu
odrzuca brakującą pozycję, inne bajty, niezapisane zmiany kodu oraz nierozwiązane
lub aktywne stare wiązania.

Niezależny przegląd Spec potwierdził:

- 35 nowych deklaracji typu checkpointu w wynikach 29 fixture i 44 deklaracje
  w 16 oracle. Sześć zmienionych wejść kontynuacji to dokładne wyniki poprzedniego kroku.
- Zmiany gas: 17 fixture po +4, jeden +6, pięć po +8. C-CLO-33 ma −18:
  +4 za typowany wpis oraz −22 za ponowne użycie dwóch niezmienionych postaci B.
  Zmienia się liczba przebudowanych pól i bloków hashowania, a nie taryfa gas.
- Dwie rodziny permutacji członków cyklu: C-CLO-23-05 i C-CLO-33. Po dopasowaniu
  członków ich dane pozostają zgodne. `canonicalBytes` nie zmienia się.
- Rozmiary i hashe manifestów odpowiadają plikom. Inwentarz implementacji
  zawiera brakujący wcześniej `VerifiedReferenceContents.java`.

Nie znaleziono niewyjaśnionej zmiany payloadu biznesowego, accept/reject ani
kosztu w zależnym pakiecie. Definicja `CheckpointEntry` i nowy kanoniczny zapis
są jawną, ograniczoną poprawką towarzyszącą w Contracts. Stare profile i
utrwalone dowody historyczne pozostają niezmienione.

Integracja z aktualnym `next` zachowuje transport oryginalnego
`ExactEventIdentityEvidence` wprowadzony przez PR #37. Ponowna regeneracja
pakietu zmieniła tylko osiem hashy źródeł Contracts w implementation baseline
i wynikającą z nich tożsamość manifestu. Fixture, oracle, payloady i gas
pozostały takie same jak przed tą integracją.

## Weryfikacja Language i Contracts

Przed przypięciem raportu przeszły `moduleCheck`, `moduleApiVerify`,
`verifyModuleStructure`, `candidatePreflight` i `releaseConformanceTest`:

- 3779 wykonań testów Java: 2810 w głównym zestawie, 206 w rdzeniu Language,
  571 w Contracts, 182 w conformance, 7 w fasadzie i 3 w modelu; zero pominięć.
  Jedyny błąd pierwszego przebiegu dotyczył lokalnego `.DS_Store` w zasobach.
  Po zachowaniu metadanych Findera poza zasobami wszystkie sześć testów
  zestawu architektury przeszło ponownie; kod testu i spis zasobów pozostały bez zmian.
- Dodatkowo przeszło 20 testów przykładów, 114 testów build-logic, 49 testów
  generatorów raportu/inwentarza oraz 7 testów narzędzi wydawniczych.
- Raport wydania: 490/490 fixture PASS, w tym 195 Language; zero pominięć.
- Macierz końcowa: 132/132 wiersze, zero rozbieżności z niezależnym oracle.
- Inwentarz zależności: zero nierozwiązanych tożsamości, rozbieżnych kopii
  i aktywnych starych wiązań. Stare wartości zachowane jako dowody historyczne
  mają osobną, sprawdzaną klasyfikację.

Narzędzia gas są testowane w układzie kompletnego pakietu utworzonym przez
`regenerate_package.stage_release_shell`; nie wystarczy uruchomić ich z
katalogu źródłowego bez manifestów. Bramka rooted używa Pythona 3.12 poprzez
`-PbluePythonExecutable=...`, a testy Java wykonano na JDK 8.

## Mini: osobna, niewykonana bramka wydania

Zgodnie z decyzją użytkownika testy Mini/MyOS wymagają osobnego terminu.
Przed kwalifikacją RC należy wykonać na rzeczywistym kandydacie MyOS:

1. Surowo opublikować Text/PLN i Currency/PLN; porównać wszystkie Holders
   przez publiczne Inspect, wraz z canonical input, typem i payloadem.
2. Powtórzyć dla Terms/Coffee, Terms/Tea i niedozwolonej referowanej wartości.
3. Zachować przygotowany canonical przez exact-content API, odczytać po
   exact ID i powtórzyć po restarcie aplikacji.
4. Dla List użyć canonical/exact loading; sprawdzić kolejność i powtórzenia.
5. Zachować surową semantykę Content API i faktyczną tożsamość przygotowanej
   wartości w Workbench.

Przejście lokalnych bramek Language/Contracts nie zastępuje tego testu i nie
stanowi potwierdzenia gotowości RC MyOS.
