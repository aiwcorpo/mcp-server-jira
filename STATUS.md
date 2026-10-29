# STATUS - mcp-server-jira (ws-04)

M1: osiągnięty (2026-10-27) - cztery narzędzia Jiry (`search_issues`, `get_issue` READ_ONLY; `add_comment`, `transition_issue` UPDATE) na starterze 3.0.0-alpha-10 (wcześniej alpha-6), 103 testy na stubie Jiry, pełny zestaw e2e M1 (`mcp-e2e/run-m1.sh`: F1, A1, A2, A3, A9, A15) PASS na procesach hosta; recenzja M1 (J-M1..J-M6) poprawiona, obejścia alpha-5 usunięte, poprawki template'u c1258c4 przeniesione.

M3: osiągnięty (2026-10-28) - starter 3.0.0-beta-3 (kontrakty 3.1.0): `@ToolTitle`, `@ResourceRef` + resolvery `Jira::Issue` i `Jira::Project` (kategoria projektu z Jiry), wyszukiwanie przez `StructuredQuery`/`QueryScope`, `ResultFilter`, manifest C6 (`GET /mcp-manifest`, 5 narzędzi), nowe `delete_issue` (`DESTRUCTIVE`, akcja `delete`, `mcp-jira-destructive`), 135 testów na stubie Jiry (w tym `delete_issue`: bez grupy destructive `access_denied`, restricted jak nieistniejące, 401 `credential_required`).

M4: osiągnięty (2026-10-29) - starter 3.0.0-beta-8 (kontrakty 4.1.0): typ poświadczenia `[user-secret]` z odmową nagłówka spoza zbioru (`CredentialTypeTest`), JQL ze `StructuredQuery` z pól normatywnych (`status`, `assignee`, `text`; projekt z argumentu jako zakres przez `QueryScope.narrowedTo`, `ORDER BY updated`; typ zgłoszenia filtrem po odpowiedzi), stały tekst `not_found_or_denied` i grant przed resolverem, manifest jako artefakt budowy `mcp-app/target/mcp-manifest.json`, licznik `mcp_audit_authentication_suppressed_total` w README, 147 testów na stubie Jiry; obraz `mcp-server-jira:m4-cedc20c` (beta-8, zbudowany z cedc20c; następny commit zmienia tylko STATUS.md). Poza zakresem tego repo: rejestracja manifestu (ws-01/ws-05), wdrożenie obrazu (ws-01).

## M4: plan kamieni (2026-10-29)

Starter 3.0.0-beta-7, kontrakty 4.1.0 (BOM). Lista kontrolna: MIGRATION-3.0.md startera, "Z beta-5 na beta-6" i "Z beta-6 na beta-7".

| Kamień | Zakres | Stan |
| --- | --- | --- |
| K1 | beta-7; zapytania ze `StructuredQuery` z list normatywnych (projekt jako zakres, nie warunek; `ORDER BY` z dozwolonych pól); typ poświadczenia `[user-secret]` i odmowa nagłówka spoza zbioru; stały tekst `not_found_or_denied`, grant przed resolverem; manifest jako artefakt budowy | zrobiony (2026-10-29), 147 testów |
| K2 | README (tabela narzędzi, typ poświadczenia, licznik `mcp_audit_authentication_suppressed_total`, `devstack` i `environment: dev`), SECURITY.md (w commicie K1), propozycje CC, sprawdzenie obrazu | zrobiony (2026-10-29) |
| K3 | obraz `mcp-server-jira:m4-<sha>`, linia `M4: osiągnięty` | zrobiony (2026-10-29) |

K1, decyzje i zachowanie:

- `search_issues`: JQL ma postać `(status = "..." AND assignee = "..." AND text ~ "...") AND project in (...) ORDER BY updated DESC`.
  Pola warunków: `status`, `assignee`, `text` (podzbiór `JQL_CONDITION_FIELDS`), `ORDER BY`: `updated`. `project` i `issuetype`
  nie są polami warunków w kontrakcie 4.x, więc wypadły z JQL; projekt z argumentu i `jql_filter.type` serwer stosuje do
  wierszy (`JiraService.searchIssues`, `IssueSearch.issueType`). Ponieważ zapytanie nie nazywa już projektu, serwer prosi Jirę
  o 100 wierszy (`SEARCH_FETCH_SIZE`) i oddaje najwyżej 25; bez tego strona 25 wierszy z wielu projektów mogłaby nie zawierać
  nic z żądanego. Wyniki mogą być uboższe niż przy zapytaniu z `project = "X"`, gdy użytkownik widzi dużo projektów:
  patrz "Propozycje CC".
- Typ poświadczenia: w repo nie było właściwości `credential-type`; `accepted-credential-types` to `user-secret` (typ domyślny).
  `CredentialTypeTest`: `user-oauth`, `token-exchange`, `service-account` -> `internal_error`, nic nie dociera do Jiry.
  `ServerTestSupport` ustawia w testach `accepted-credential-types=user-secret` (domyślnie `TestGateway` przyjmuje też `user-oauth`),
  żeby artefakt budowy miał to, co zarejestruje potok.
- `JiraToolErrors` używa `ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE`; `ResourcePolicyTest` sprawdza stały tekst i to, że zapis
  bez zakresu projektu nie powoduje żadnego żądania do Jiry (resolver nie jest wołany, CC-146). Zachowanie `delete_issue`
  (DESTRUCTIVE, potwierdzenie kodem robi gateway, odmowa pod innym kluczem) bez zmian; `DeleteIssueToolTest` zielony.
- Manifest: `ServerTestSupport` ustawia `manifest.output-file=target/mcp-manifest.json`; po `./mvnw verify` plik jest w
  `mcp-app/target/` (`acceptedTypes: ["user-secret"]`, bez `environment`), `ManifestTest` porównuje go z odpowiedzią serwera.
  `environment: dev` dodaje starter w profilu `devstack`; serwer nie ma własnego kodu na to, a test kontekstu pod `devstack`
  wymaga zmiennej procesu (MIGRATION, "Z beta-3 na beta-4"), więc w repo go nie ma.
- `JiraWikiTextTest.aCraftedDescriptionIsFilteredQuickly`: próg 200 ms zawodził na obciążonym hoście (load ~17), próg 1000 ms nadal
  wyłapuje wykładniczy nawrót.

## Propozycje CC

- Typ zgłoszenia (`issuetype`) jako pole warunku JQL: dziś poza listą `JQL_CONDITION_FIELDS`, więc `jql_filter.type`
  działa po fakcie na wierszach (100 wierszy z Jiry, 25 w wyniku). Dodanie `issuetype` do listy normatywnej to usunęłoby.
  (Propozycja zawężenia zakresu do argumentu z beta-7 jest zrealizowana przez `narrowedTo` w beta-8.)

## Beta-8 (2026-10-29)

Podbicie na 3.0.0-beta-8. `search_issues` zawęża zakres przez `scope.narrowedTo(List.of(project))`: JQL ma `project in ("<projekt>")`
po warunkach (przecięcie z linkiem; puste przecięcie daje pusty wynik bez wywołania Jiry). Porównanie projektu w
`JiraService` zostaje jako druga warstwa; `SEARCH_FETCH_SIZE` 100 -> 25 zostaje, bo typ zgłoszenia nadal jest filtrem po odpowiedzi.
Żaden test nie oczekiwał `access_denied` dla zasobu spoza linku (spoza linku już dawał `not_found_or_denied` w testach);
pozostałe `access_denied` to brak grupy narzędzia.

Start: `mcp-server-template` 7f65f8f (`./new-server.sh jira`); od rundy 6 BOM `pl.aiwcorpo.mcp:mcp-platform-bom:3.0.0-beta-3`.

## Runda 3: starter alpha-10 (2026-10-28)

Z alpha-6 na 3.0.0-alpha-10 (BOM, kontrakty 2.1.1). Testy: `./mvnw clean verify` 103, 0 błędów (JiraServiceTest 14,
JqlBuilderTest 4, JiraWikiTextTest 14, JiraToolErrorsTest 2, mcp-app 69). Uruchomienie lokalne wg README (port 18500, `dev`): health 200,
`POST /mcp` bez JWT 401, proces zatrzymany.

- Limity na każdym poziomie: `JqlFilter` to rekord z `@ArgumentLimits` (`minLength 1`, `maxLength` 60/100/60) i
  `@Nullable` na `status`, `assignee`, `type`; `McpToolsConfiguration` ma tylko `SchemaLimitedToolCallbacks.of(...)` i
  kontrolę listy nazw narzędzi (usunięte `boundedFilter` i `withBoundedFilter`). Test za długiego `status` w
  `SearchIssuesToolTest.filterValuesCannotCarryJql` (C10 `invalid_argument`; komunikat startera wymienia
  `jql_filter`, nie pole).
- Wyniki: `UntrustedText` ze startera zamiast własnego rekordu; `@Nullable` na `assignee`, `description` oraz
  `project`, `type`, `status`, `updated` (adapter dopuszcza brak); `TransitionView.status` też. Bez adnotacji Jacksona.
  Testy: `anUnassignedIssueIsAnOkResultWithNullAssignee` (zgłoszenie bez osoby i opisu: wynik ok, `null` w
  `structuredContent`). Nazwy typu, statusu i przejścia: `TextHygiene.normalize` i `UntrustedText.cut` (60) zamiast
  własnego `\p{Cntrl}|\p{Cf}`.
- J2-L2: `JiraWikiText` zamienia opis z wiki markup na tekst. Kolejność: dekodowanie encji (`&#8203;`), `normalize`,
  `cut`, filtr (obrazy, makra `{code}`/`{noformat}`/`{panel}`..., `{{mono}}`, linki: `[tekst|url]` -> `tekst (url)`,
  `[url]` -> `url`, `[~user]` -> `user`; nagłówki, listy, tabele, cytaty, pogrubienia i podobne), `plainCut`.
  Filtr powtarza się do braku zmian (najwyżej 5 rund), bo usunięcie jednej konstrukcji może złożyć inną; gdy po 5 rundach tekst dalej się zmienia, filtr jest fail-closed: z tekstu znikają wszystkie znaki `! { } [ ] |`. Znak niewidoczny wewnątrz
  `!url!` normalizacja zamienia na spację, więc taki tekst nie jest już składnią obrazu i wychodzi jako zwykły
  tekst z wykrzyknikami. Sekwencje `\*` nie są interpretowane (zostają jak napisane). Streszczenie (`summary`) w Jirze
  to pole jednowierszowe, nie markup: tylko higiena i cięcie. Komentarze nie są zwracane przez żadne narzędzie.
- `application-dev.yml`: usunięte `replay-store: memory` i `mtls.required: false` (starter robi to sam; WARN
  `dev_profile_overrides` ma `overridden=[]`). `SPRING_PROFILES_ACTIVE=dev` tylko w komendzie z README, nie w `.env`,
  compose, jednostce ani obrazie.
- Logi: błędy domenowe (`not_found_or_denied`, `invalid_argument`, `credential_required`) są INFO; ERROR zostają
  tylko przypadki `internal_error` (3xx, nieczytelna odpowiedź). Testy serwera niczego nie oczekiwały na ERROR
  dla błędu domenowego, więc nie było czego zmieniać. Brak linii `untrusted_text_not_normalized`.
- Gateway M2 (claimy `vs` i `vsc:{}` przy wywołaniu przez link): `GetIssueToolTest.acceptsTheVirtualServerClaimsOfACallThroughALink`
  (JWT z `vs` i pustym `vsc`, wynik ok) przechodzi bez zmian w kodzie serwera.
- Zmiana `definitionHash` narzędzi (nowe `outputSchema`, opis `get_issue`): gateway oznaczy je jako `pending_approval`.

## Runda 2: alpha-6, template c1258c4, recenzja M1 (2026-10-27)

| Znalezisko | Status | Gdzie / test |
| --- | --- | --- |
| J-M1 przejście po nazwie wybiera pierwsze | poprawione: id wygrywa z nazwą, nazwa musi pasować w całości i do jednego przejścia, kilka to `invalid_argument` z listą id | `JiraService.choose`; `JiraServiceTest.aNameSharedByTwoTransitionsIsNotGuessed`, `aNameMatchesWholeOrNotAtAll` (zabija mutację `equals` -> `contains`, sprawdzone), `TransitionIssueToolTest.aNameSharedByTwoTransitionsIsRefusedWithTheIdsToChooseFrom` |
| J-M2 3xx i pusta odpowiedź jako sukces | poprawione: `defaultStatusHandler` także dla 3xx, pusta/nieczytelna 2xx, zgłoszenie bez klucza, komentarz bez `id`, przejście inne niż 204 -> `BackendResponseException` (`internal_error`) | `GetIssueToolTest` (redirect, pusta, bez klucza), `AddCommentToolTest.aRedirectIsNotFollowedAndNotReadAsMissing`, `TransitionIssueToolTest.aRedirectIsNotAnExecutedTransition`, `anAnswerOtherThanNoContentIsNotAnExecutedTransition` |
| J-M3 `http` poza `dev` | poprawione: `https` poza `dev`, `MCP_JIRA_ALLOW_PLAIN_HTTP` jawna zgoda (ostrzeżenie w logu); `scripts/run-dev-host.sh` ustawia ją dla mocka | `ProductionConfigurationTest` |
| J-M4 brak limitu odpowiedzi | poprawione: `BoundedBodyInterceptor`, `max-response-size: 1MB` | `GetIssueToolTest.anOversizedBodyIsCutOff` |
| J-M5 zapis na przeniesionym zgłoszeniu | poprawione: oba narzędzia UPDATE czytają klucz kanoniczny (`GET issue/{key}?fields=project`), piszą pod nim i zwracają go; niewidoczne -> `not_found_or_denied` | `AddCommentToolTest.aMovedIssueIsWrittenUnderItsNewKeyAndReportedWithIt`, `TransitionIssueToolTest.aMovedIssueIsTransitionedUnderItsNewKeyAndReportedWithIt`, `JiraServiceTest.writesGoToTheKeyJiraReportsAndAreReportedUnderIt` |
| J-M6 markup wiki w `add_comment` | poprawione: treść w `{noformat}`, `{noformat` w treści zamieniane na `{-noformat`; opis narzędzia: tekst zwykły | `CommentText`; `AddCommentToolTest.markupThatMakesRequestsOrNotificationsReachesJiraAsLiteralText`, `JiraServiceTest.aNoformatTagInTheTextCannotEndTheBlock` |
| J-L1 `prometheus` wystawiony | poprawione: `include: health` | `application.yml` |
| J-L2 400 dla `text` z samych operatorów | poprawione: samodzielne `AND`/`OR`/`NOT` usuwane z tekstu, bez liter/cyfr -> `invalid_argument` | `SearchIssuesToolTest.filterValuesCannotCarryJql`, `booleanOperatorsInTheTextAreNotPassedToJira` |
| J-L3 `total` z odfiltrowanymi trafieniami | poprawione: po odrzuceniu czegokolwiek `total` = liczba zwróconych | `JiraServiceTest.searchKeepsOnlyIssuesOfTheRequestedProject`, `theTotalOfJiraStandsWhenNothingWasDropped` |
| J-L4 teksty administracyjne Jiry | częściowo: nazwy typu, statusu i przejścia ucinane do 60 znaków, bez znaków sterujących (`JiraToolViews.name`); zostają zwykłymi polami, nie `UntrustedText` (model używa ich jako wartości filtrów) | `README.md`, `SECURITY.md` |
| J-L5 / W-2 walidacja SDK bez C10 i C8 | zamknięte przez starter alpha-6: naruszenie schematu to C10 `invalid_argument` i zdarzenie C8; wzorce kluczy z kontraktów w schemacie (`contractPattern`) | `GetIssueToolTest.argumentsOutsideTheSchemaNeverReachJira`, `ToolListTest` |
| J-L6 luki testów | poprawione: testy z J-M1, J-M2, J-M5, `ProductionConfigurationTest`, `DevProfileLoopbackTest` | j.w. |
| W-1 poprawki template'u c1258c4 | przeniesione (patrz niżej) | |
| W-3 kształt oznaczenia treści (kontrakt) | CC-072 zastosowane bez zmiany zależności: `summary` i `description` to `UntrustedText{kind, format, text, truncated}` (zamiast `untrustedText{summary, description, descriptionTruncated}`) | `GetIssueToolTest`, `SearchIssuesToolTest` |
| W-4, W-5 (starter) | W-4 zamknięte (jedna implementacja w starterze); W-5 (normalizacja `\p{Cf}` w tekście z systemu) tylko dla nazw, tekst zgłoszenia bez zmian: wymaga decyzji startera | |
| W-6 neutralizacja markupu w UPDATE | patrz J-M6 | |
| W-7 zasób przeniesiony przy UPDATE | patrz J-M5 | |
| J2-M1 odmowa niejednoznacznego przejścia bez celu | poprawione: komunikat zawiera pary `id -> status docelowy` (nazwa statusu z Jiry przez `JiraToolViews.name`: do 60 znaków, bez znaków sterujących; id tylko liczbowe); wyjątek niesie kandydatów, nie same id | `JiraToolErrors`, `AmbiguousTransitionException.candidates()`; `TransitionIssueToolTest.aNameSharedByTwoTransitionsIsRefusedWithTheIdsToChooseFrom` (oczekuje `41 -> 'Done'` i `42 -> 'Rejected'`), `JiraServiceTest.aNameSharedByTwoTransitionsIsNotGuessed` |
| J2-L1 wzmianki `[~user]` w `{noformat}` | otwarte, do sprawdzenia na instancji DC: komentarz `{noformat}[~user]{noformat}` i skrzynka powiadomień. Test w repo widzi tylko tekst wysłany do stuba. Niezależnie od wyniku tani środek to rozbicie `[~` w `CommentText` (np. na `[ ~`); nie wprowadzone, bo zmienia treść komentarza i wymaga decyzji, czy wzmianki mają kiedykolwiek działać | `CommentText`, `AddCommentToolTest.markupThatMakesRequestsOrNotificationsReachesJiraAsLiteralText` |
| J2-L2 opis zgłoszenia jako surowy wiki markup z `format: text/plain` | zamknięte w alpha-10: konwersja po stronie serwera (`JiraWikiText`), opisana niżej | `JiraWikiTextTest`, `GetIssueToolTest.theDescriptionIsPlainTextWithoutWikiMarkupImagesAndMacros` |

Starter 3.0.0-alpha-6: usunięte ręcznie pisane schematy (`inputSchemas()`, `TOOLS` z kontrolą schematu) na rzecz
`@ArgumentLimits` i `SchemaLimitedToolCallbacks.of(...)`; wzorce `JiraProjectKey` i `JiraIssueKey` przez
`contractPattern` (bez kopii; w kodzie zostały tylko reguły, których schemat nie wyraża: nazwy, użytkownik,
tekst wyszukiwania, id przejścia). Jira nie miała własnych obejść SDK. Testy oczekujące tekstu SDK zmienione na
C10 `invalid_argument`. Zagnieżdżony `jql_filter` alpha-6 zostawia otwarty (znane ograniczenie startera), więc
`McpToolsConfiguration.boundedFilter` domyka go i ogranicza, a Spring AI oznaczał wszystkie pola rekordu jako
wymagane (zdejmowane tam samo; test `ToolListTest`).

Z template'u c1258c4: https poza `dev`, brak przekierowań i 3xx jako błąd, limit odpowiedzi,
`BackendResponseException`, `ProductionConfigurationTest`, `DevProfileLoopbackTest`, `application-dev.yml`,
sandbox systemd i `install.sh`, sekcje README "Uruchomienie lokalne bez środowiska" (sprawdzone na porcie 18500:
health 200, `POST /mcp` bez JWT 401) i "Uruchomienie z mcp-platform-dev (mTLS)", `prometheus` niewystawiony,
`TRIAL_SYSTEM` i `contractSystemIsKnown`. Nie przeniesiono `JiraToolTest` z mcp-tools (testował stałe wzorca
rekordu z template'u, których tu nie ma; zachowanie pokrywają testy `mcp-app`).

## Co zostaje

- M2/M3: zrobione w rundzie 6 (niżej). `JiraService.searchIssues` zachowuje filtr projektu jako drugą warstwę.
- Kategoria (b), bez zmiany w tym repo: W-3 pozostaje decyzją kontraktów (CC-072 przyjęte, w 2.0.2 nie ma
  definicji `UntrustedText`; kształt jest zgodny z opisem, a `outputSchema` narzędzi będzie możliwy po
  przejściu na kontrakty 2.1.x z następnym starterem).
- Zakładam, że Jira nie rozpoznaje `{ noformat}` z odstępem jako makra (i dlatego zamieniam `{noformat` na
  `{-noformat`, co jest inną nazwą makra niezależnie od tego); komentarz w Jirze jest preformatowany.
- Pojedyncze `AND`/`OR`/`NOT` wielkimi literami są usuwane z `text`; wyszukiwanie takich słów wprost nie jest możliwe.

## Czego potrzebują inni

- ws-01: nic nowego dla uruchomienia z hosta (`scripts/run-dev-host.sh` ustawia `MCP_JIRA_ALLOW_PLAIN_HTTP=true`).
  Usługa `mcp-jira` w `docker-compose.yml` musi dostać `MCP_JIRA_ALLOW_PLAIN_HTTP: "true"`, dopóki mock Jiry jest po
  `http`.
- ws-02: zagnieżdżone obiekty w `inputSchema` (`additionalProperties`, `maxLength`) i `required` wygenerowane przez
  Spring AI dla rekordów; teraz obsługuje to serwer.
- ws-03, ws-05: bez zmian (nazwy narzędzi, `/mcp`, SAN `mcp-jira.internal`, wyniki `get_issue` mają nowy kształt
  tekstów: `summary.text`, `description.text`).

## Co działa

- Narzędzia z tabeli ws-04, dokładnie te nazwy, które gateway ma w konfiguracji statycznej; każde z
  `@ToolRisk`, opisem bez instrukcji dla modelu i schematem wejścia z `@ArgumentLimits`
  (`additionalProperties: false` na każdym obiekcie, `maxLength` na każdym stringu). Rejestracja tylko przez
  `ToolCallbackProvider` w `McpToolsConfiguration` (jawna lista `TOOLS`; narzędzie spoza listy zatrzymuje start).
- Walidacja: wzorce kontraktu (`JiraProjectKey`, `JiraIssueKey`) w schemacie, egzekwowane przez starter; własne
  reguły (nazwy statusu, typu, przejścia, użytkownik, `text`, `body`) przez `InvalidToolArgumentException` przed
  wywołaniem Jiry.
- `search_issues`: projekt obowiązkowy i sprawdzany przed zapytaniem (`GET /rest/api/2/project/{key}`); JQL
  buduje tylko `JqlBuilder` (stałe nazwy pól, wartości w cudzysłowie z escapowaniem, znaki składni Lucene w
  `text` zamieniane na spacje); wyniki spoza projektu odrzucane; najwyżej 25 wyników.
- Wyniki: tylko potrzebne pola; tytuł i opis jako `UntrustedText`; bez `renderedFields`, bez
  komentarzy, linków, e-maili. 404 i 403 -> `not_found_or_denied` (ten sam tekst), brak poświadczenia i 401 ->
  `credential_required` z `connectUrl`, 400 Jiry -> `invalid_argument` ze stałym tekstem. Obejście z template'u
  (`defaultStatusHandler` rzuca wyjątek z samym statusem) zostaje.
- `JiraProject{key, category}` (C7): kategoria z `projectCategory.name`, `""` gdy brak; dziś używane do
  sprawdzenia projektu przed wyszukiwaniem, w becie źródło atrybutów resolvera.
- Testy (`./mvnw clean verify`, JDK 25, bez Dockera): 84, 0 błędów (w tym `contractSystemIsKnown` dziedziczony
  przez każdą klasę z `ServerTestSupport`).
  - `GetIssueToolTest` (17), `SearchIssuesToolTest` (10), `AddCommentToolTest` (11), `TransitionIssueToolTest` (16),
    `ToolListTest` (4), `GatewayOnlyAccessTest` (4), `ProductionConfigurationTest` (3), `DevProfileLoopbackTest` (1),
    `JiraServiceTest` (14), `JqlBuilderTest` (4).
  - Każde narzędzie: pozytywny, `credential_required` (brak i 401), `not_found_or_denied` (404 = 403),
    `invalid_argument`, negatywne dla każdego identyfikatora.
- `StubBackend` odpowiada ścieżkami i kształtem REST API v2 jak mock Jiry z `mcp-platform-dev/mocks/jira`
  (ABC-1 "Login page returns 500", ABC-3 przeniesione do DEF-5, HR w kategorii "Restricted"); komentarze i
  przejścia dopisane wg API Jiry, bo mock ich nie ma.
- `scripts/run-dev-host.sh`: serwer na `https://localhost:18446/mcp` (adres z `run-dev-host.sh` gateway'a),
  certyfikat `mcp-jira`, Redis z ACL `mcp-jira`, mock Jiry `http://localhost:18081`.

## E2E M1 po rundzie 2 (2026-10-27)

`cd ../mcp-e2e && ./run-m1.sh` (porty 18000, 18100, 18445, 18446 wolne, `mcp-platform-dev` działał, nic w nim nie
restartowane): exit 0, F1, A1, A2, A3, A9, A15 PASS. Serwer: mcp-jira 5d1b625 na 3.0.0-alpha-6, bez profilu `dev`,
z mock Jiry po `http` przez `MCP_JIRA_ALLOW_PLAIN_HTTP=true` ze `scripts/run-dev-host.sh`. Skrypt zapisał własny
raport w `mcp-e2e/reports/2026-10-27-m1.md` i logach obok (pliki śledzone tamtego repo są przez to zmodyfikowane;
nie commitowane stąd).

## Demo M1 (2026-10-27, procesy hosta)

Infrastruktura `mcp-platform-dev` działała (7 kontenerów, bez restartu), porty 18000, 18100, 18445, 18446 wolne.
Gateway d4fbf98, broker 5a5dbb4, mcp-jira f0da7e1 na 3.0.0-alpha-4 (jar z `./mvnw verify`). Logi:
`<scratchpad>/jira-demo/`.

```bash
(cd ../mcp-credential-broker && MCP_GATEWAY_JWKS_URI=https://localhost:18445/.well-known/jwks.json \
   MCP_GATEWAY_TLS_NAME=localhost scripts/run-dev-host.sh > broker.log 2>&1) &
(cd ../mcp-gateway && scripts/run-dev-host.sh > gateway.log 2>&1) &
scripts/run-dev-host.sh > mcp-jira.log 2>&1 &
(cd ../mcp-platform-dev && make pat USER=alice BROKER_URL=https://localhost:18100)
#   {"httpStatus": 200, "status": "connected", "backendUser": "alice", "expiresAt": null}
TOKEN=$(make -s -C ../mcp-platform-dev token USER=alice | tail -1) ../mcp-gateway/scripts/mcp-demo.sh
```

- bez tokenu: 401 z `resource_metadata`;
- `tools/list`: `jira__search_issues`, `jira__get_issue`, `jira__add_comment`, `jira__transition_issue`;
- `tools/call jira__get_issue ABC-1` (sesja 2025-11-25, z podrzuconymi `X-PAT-Jira` i `X-MCP-Credential`),
  `requestId` `01a17e23-7309-7b4a-8efa-c87637dffca7`: `{"key":"ABC-1","project":"ABC","type":"Task",
  "status":"To Do",...,"untrustedText":{"summary":"Login page returns 500",...}}`; to samo w erze 2026-07-28
  (`01a17e23-73d6-7dfa-86b0-4c810e7449b8`);
- dodatkowo `jira__search_issues {project: ABC, text: login}` (`01a17e23-98a0-7b39-b938-33ef50382796`):
  ABC-1 i ABC-2 z mocka.

```
$ python3 ../mcp-platform-dev/scripts/mcpdev.py audit 01a17e23-7309-7b4a-8efa-c87637dffca7 \
    --logs gateway.log --logs broker.log --logs mcp-jira.log
2026-10-27T16:13:34.859Z   gateway  mcp-gateway            policy_decision      allow
2026-10-27T16:13:34.936Z   broker   mcp-credential-broker  credential_resolve   ok
2026-10-27T16:13:35.028Z   server   mcp-jira               tool_call            ok
2026-10-27T16:13:35.038Z   gateway  mcp-gateway            tool_call            allow
components with events: ['broker', 'gateway', 'server']; missing: none
```

`make jira-auth`: żądania mcp-jira (`GET /rest/api/2/issue/ABC-1?fields=summary,description,...`,
`GET /rest/api/2/project/ABC`, `POST /rest/api/2/search`) z `PAT alice-pat`. `grep -c alice-pat` w
gateway.log, broker.log, mcp-jira.log: 0, 0, 0. Procesy zatrzymane po demo.

Powtórka na starterze 3.0.0-alpha-5 (ten sam dzień, te same komendy; mcp-jira bez profilu `dev`, log "No
active profile set", mTLS z certyfikatem `mcp-jira` i JWKS gateway'a): `tools/list` z czterema narzędziami,
`get_issue ABC-1` z mocka, audyt `01a17e27-692c-7dce-9dc7-1b8894d235a4` -> policy_decision allow,
credential_resolve ok, tool_call ok (server), tool_call allow (gateway), missing: none; `alice-pat` w logach 0, 0,
0. Logi: `<scratchpad>/jira-demo-a5/`.

## Odstępstwa

- Wzorce identyfikatorów nie są w `inputSchema`, tylko w kodzie: MCP SDK 2.0.0 sprawdza argumenty schematem
  przed narzędziem i odpowiada własnym tekstem (`Tool (...) input validation failed`), nie C10. Z wzorcem w
  schemacie zły klucz nie dawałby `invalid_argument`. W schemacie zostają `additionalProperties: false`,
  `maxLength`/`minLength` i `required`; ich naruszenie daje tekst SDK z `isError: true` (testy sprawdzają, że
  nic nie dociera do Jiry).
- `jql_filter` to obiekt `{status, assignee, type}` (nazwa z briefu w `snake_case`, reszta argumentów w
  `camelCase` jak w template). `assignee` przyjmuje tylko nazwę użytkownika (bez `currentUser()`, `EMPTY`).
- `transition_issue` przyjmuje id albo nazwę przejścia; serwer najpierw pobiera dostępne przejścia i wysyła
  tylko jedno z nich. Przejście wymagające pól na ekranie (400 Jiry) kończy się `invalid_argument`.
- Bez stronicowania w `search_issues` (jedna strona, 25 wyników, `total` w wyniku).

## Miejsca na betę startera (M2/M3) - stan sprzed rundy 6, zrealizowane

- `QueryScope`: `JqlBuilder.build` (mcp-integration) - jedyne miejsce, gdzie powstaje JQL; zawężenia dojdą jako
  kolejne klauzule `AND` wokół całego zapytania.
- `ResultFilter`: `JiraService.searchIssues` - dziś odrzuca wyniki spoza projektu z argumentu.
- `@ResourceRef`: `project` -> `Jira::Project` (atrybuty z `JiraResourcePort.findProject`), `issueKey` ->
  `Jira::Issue` (projekt z odpowiedzi Jiry, nie z klucza: przeniesione zgłoszenie ABC-3 należy do DEF).

## Czego potrzebują inni

- ws-02 (starter): błąd walidacji schematu przez MCP SDK (`ToolInputValidator`) jako C10 `invalid_argument`
  (znane z STATUS startera, "do bety"); wtedy wzorce mogą wejść do `inputSchema`.
- ws-01: mock Jiry nie ma `POST /rest/api/2/issue/{key}/comment` ani `GET/POST .../transitions`; demo i testy
  e2e narzędzi UPDATE przez gateway wymagają tych mapowań (format w `StubBackend` tego repo). Usługa `mcp-jira` w
  compose nadal bez `MCP_CONNECT_URL` (zgłoszone przez ws-03).
- ws-05: nic; serwer działa z gateway'em d4fbf98 bez zmian.
- ws-10: serwer gotowy do e2e (`scripts/run-dev-host.sh`).

## Propozycje zmian kontraktu

Brak w M1.

## Runda 4: poprawki przeglądu alpha-10 (2026-10-28)

| Znalezisko | Status | Dowód |
| --- | --- | --- |
| J3-M1 filtr kwadratowy x 50 rund (DoS) | poprawione: `INLINE` patrzy najwyżej 200 znaków w przód, 5 rund zamiast 50, `TRAILING_SPACE` bez kwadratowego skanu, tekst surowy ucinany przed dekodowaniem (10 x limit) | `JiraWikiTextTest.aCraftedDescriptionIsFilteredQuickly` (spreparowany opis z raportu poniżej 200 ms; zimna JVM ok. 100 ms), `hugeRawTextIsCutBeforeAnyFilterSeesIt` |
| J3-L1 po wyczerpaniu rund zostaje `!url!` | poprawione: fail-closed (`dropSyntax`) | `nestingDeeperThanTheRoundsLeavesNoImageOrMacroSyntax` |
| J3-L2 luki filtra | poprawione: obraz z adresem (`://`) usuwany także po literze (`see!https://...!`); drugie `cut` po filtrze (tabele rosną); test kolejności (`cut` przed filtrem). Do wiadomości, nie zmieniane: `&lt;img ...&gt;` po dekodowaniu zostaje tekstem `<img ...>`, a zawartość `{html}...{html}` zostaje jako tekst (jak C-L4 w Confluence: `text/plain` jest deklaracją, ryzyko po stronie klienta renderującego HTML); obraz Markdown `![a](u)` rozbija reguła linków; obraz bez adresu (`see!a.png!`) zostaje, bo `Wow!great!` jest zwykłym tekstem | `anImageGluedToAWordIsStillRemovedWhenItHasAnAddress`, `theResultIsCutAgainAfterTheFilterBecauseTableRowsGrow`, `theCutRemovesAClosingDelimiterBeforeTheFilterRuns` |
| J3-L3 nazwy statusów w komunikacie C10 | poprawione: nazwa w apostrofach bez `'`, `"`, `,` i `->`, brak celu jako `?` | `JiraToolErrorsTest` |
| A10-M1 `DevProfileLoopbackTest` | bez zmian, rozwiąże starter alpha-11 | |

## Runda 5: starter alpha-11 (2026-10-28)

BOM alpha-10 -> alpha-11 (MIGRATION-3.0.md, sekcja "Zmiany w 3.0.0-alpha-11"). `DevProfileLoopbackTest` sprawdza regułę guarda przez `DevProfileGuardAssertions.assertDevProfileLoopbackRule()` (A10-M1), więc przechodzi także w kontenerze CI. `./mvnw clean verify`: 103 testów, 0 błędów; `INVOCATION_ID=ci ./mvnw -B verify`: bez różnic. Start lokalny w profilu `dev`: health 200, POST /mcp bez JWT 401.

## Runda 6: M3, starter beta-2 (2026-10-28)

Przejście krok po kroku wg MIGRATION-3.0.md, każdy etap z zielonym `./mvnw clean verify` i osobnym commitem:

| Etap | Co | Commit |
| --- | --- | --- |
| 1 | alpha-11 -> alpha-14 (sekcja "Jak przejść z alpha-11"): `DevProfileLoopbackTest` sprawdza kontekst serwera, kontrakty 2.2.5; brak wykluczeń platformy do usunięcia | `1e1a2fb` |
| 2 | alpha-14 -> beta-1: `@ToolTitle` (polskie tytuły), `policy.*` i `manifest.*` w `application.yml` (zmienne środowiska, produkcja) i `application-dev.yml` (paczka z `http://127.0.0.1:18747`, klucz `test-policies-main-dev`, `allow-test-keys` tylko w `dev`), testy z grupami przykładowej paczki (`mcp-jira-read/write/destructive`, `mcp-jira-proj-ABC/DEF`) | `c6c72a1` |
| 3 | beta-1 -> beta-2 i zasoby: `@ResourceRef`, resolvery, `StructuredQuery`, `ResultFilter`, `delete_issue`, obraz bez Alpine (cedar-java) | patrz `git log` |

Kamienie pośrednie: (1) migracja bez nowej logiki, (2) zasoby i wyszukiwanie, (3) `delete_issue`, testy polityki i manifestu, dokumentacja.

Co się zmieniło w kodzie:

- Resolvery w `mcp-integration`: `JiraIssueResolver` (zgłoszenie z odpowiedzi Jiry plus `GET project/{key}` dla kategorii;
  `fetchAll` szuka każdego projektu raz na listę i pomija element, którego odpowiedź jest nieużywalna) i
  `JiraProjectResolver`. Id i projekt w opisie pochodzą z Jiry. Zgłoszenie bez projektu w odpowiedzi to błąd
  odpowiedzi (`internal_error`), nie zasób bez klasyfikacji.
- Odczyt klucza kanonicznego (`canonicalIssueKey`) zniknął: narzędzia zapisujące biorą klucz z zasobu pobranego przez
  resolver (jeden `GET` mniej na zapis, ale `GET project` więcej). `getIssue` w use case też zniknął (narzędzie czyta
  zasób z `ResourceContext`).
- `JqlBuilder` usunięty; zapytanie powstaje w `JiraTool` z `policy.queryScope("Jira::Project").jql(...)`. Wygenerowany
  JQL jest bajt w bajt taki jak poprzedni (test z dokładnym tekstem przeszedł bez zmian), plus `project in (...)` przy linku.
- Domena: `IssueSearch(projectKey, jql)`, `IssueSearchResult` bez `total`, `JiraIssue.projectCategory`.
- `delete_issue`: `DELETE /rest/api/2/issue/{key}`, 204 = usunięte; 404/403 -> `not_found_or_denied`, 401 ->
  `credential_required`, 400 (podzadania) -> `invalid_argument` ze stałym tekstem, inne 2xx i 3xx -> `internal_error`.
- Dockerfile: `eclipse-temurin:25-jre` (glibc) zamiast Alpine, jak w template.

Odstępstwa i zmiany zachowania:

- `search_issues` nie zwraca już `total` ani `returned` (starter nie poprawia liczników po filtrze, a liczba z Jiry
  zdradzałaby zgłoszenia ukryte przez politykę). Model nie wie, czy jest więcej niż 25 wyników.
- Walidacja argumentów, której nie wyraża schemat (pusty `body` po przycięciu, nazwa przejścia, wartości filtra
  wyszukiwania), dzieje się teraz po resolverze, więc przy takim błędzie Jira dostaje odczyt zgłoszenia i projektu
  (nigdy zapis ani zapytanie). Testy sprawdzają brak zapisów (`BACKEND.writes()`) i brak zapytań.
- Komunikat `not_found_or_denied` z domeny serwera ma teraz ten sam tekst co odmowa startera ("The resource does not
  exist or you do not have access to it."), żeby odmowa po resolverze (np. 403 na samym DELETE) nie różniła się od odmowy polityki.
- `project` w `search_issues` jest `@ResourceRef` (inaczej niż w template, gdzie jest zwykłym argumentem): projekt
  z kategorią `Restricted` daje `not_found_or_denied` jak nieistniejący, zamiast pustej listy. Poza linkiem wirtualnego
  serwera starter odpowiada `access_denied` na projekt spoza zakresu (tak samo dla istniejącego i nie, test
  `aLinkNarrowedToOneProjectHidesTheIssuesAndTheSearchOfAnother`), więc pusty wynik zamiast błędu nie jest możliwy.
- Atrybuty polityki: Jira ma tylko `Jira::Project.category` (obowiązkowy, `""` bez kategorii); `labels` i
  `classification` z entity-builder.md dotyczą innych systemów.
- `jql_filter` zachowuje nazwę z briefu, choć model nie podaje JQL.

Nie sprawdzone: wywołanie `delete_issue` przez prawdziwy gateway i kod TOTP (gateway, broker i portal są poza tym repo);
serwer sprawdziłem na procesie hosta (port 18775, `dev`): paczka polityk pobrana z `18747` (`policy_bundle_loaded`),
`manifest_ready tools=5`, health 200, `POST /mcp` i `GET /mcp-manifest` bez JWT 401. Czy aktualna paczka ws-09 przepuszcza
`delete` dla `mcp-jira-destructive` przy `acr` z MFA, sprawdzi e2e z gateway'em.

## Propozycje CC

- Tekst `not_found_or_denied` w C10: starter ma stały tekst odmowy zasobu, a serwery mają własne komunikaty dla
  wyjątków domeny. Proponuję zapisać w C10 jeden tekst dla tego kodu (albo zakaz własnych), żeby odmowy z różnych
  warstw były nieodróżnialne z definicji, nie z uzgodnienia.
- Odpowiedź na projekt spoza zakresu linku (`access_denied` ze startera dla płaskich typów w `@ResourceRef`): kontrakt
  nie mówi, czy wyszukiwanie poza linkiem ma być błędem czy pustym wynikiem (README startera mówi: pusty wynik). Do rozstrzygnięcia.
- Licznik wyników: wyszukiwanie bez `total` zostawia model bez informacji o kolejnych stronach. Propozycja: pole
  `hasMore` liczone przez starter po filtrze (np. gdy surowa strona była pełna), zamiast liczników z systemu.

## Czego potrzebują inni (runda 6)

- ws-01: usługa `mcp-jira` w compose potrzebuje zmiennych `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`,
  `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR`, `MCP_INTERNAL_URL`, `MCP_ACCEPTED_CREDENTIAL_TYPES` (README,
  `application.yml`) i obrazu bez Alpine (`mcp-server-jira:m3-<sha>`).
- ws-05/ws-09: rejestracja manifestu z `GET /mcp-manifest` (5 narzędzi, nowy `definitionHash` wszystkich: tytuły,
  `outputSchema`); paczka musi mieć schemat 3.1.0.

## Runda 7: starter beta-3 (2026-10-28)

Podbicie BOM beta-2 -> beta-3 (MIGRATION "Z beta-2 na beta-3"). Wszystkie narzędzia zwracają rekordy, więc `@ResultWithoutResources` nie jest potrzebne; brak ręcznych `ToolCallback`; `@ResourceRef` nie stoi na komponentach argumentów. Bez zmian w kodzie.

## Runda 8: poprawki przeglądu M3 (2026-10-28)

| Znalezisko | Status |
| --- | --- |
| J-1 `delete_issue` na przeniesionym zgłoszeniu | poprawione: gdy klucz z Jiry różni się od klucza z argumentu, `invalid_argument` z nowym kluczem ("call again with that key"), bez DELETE; polityka dopuściła już nowy klucz, a zgłoszenie przeniesione poza uprawnienia daje `not_found_or_denied` bez klucza. `add_comment` i `transition_issue` zostają przy zapisie pod kluczem kanonicznym (J-M5): to UPDATE, odwracalne. Testy: `aMovedIssueIsNotDeletedUnderAKeyTheHumanDidNotConfirm`, `theNewKeyOfAMovedIssueOutsideThePolicyIsNotDisclosed` |
| J-2 systemd | poprawione: `MCP_POLICY_*`, `MCP_INTERNAL_URL`, `MCP_ACCEPTED_CREDENTIAL_TYPES` w szablonie env, `StateDirectory=mcp-jira` |
| J-3 TOCTOU resolver -> zapis | ryzyko rezydualne: REST v2 nie pozwala zamknąć atomowo; Jira sprawdza jeszcze prawa użytkownika. Nie zmieniane |
| J-4 liczba wywołań restricted vs nieistniejące | zaakceptowane: odpowiedź klienta identyczna, różnica tylko w czasie i logach Jiry |
| J-5 `fetchAll` | bez zmian: mock Jiry z mcp-platform-dev odpowiada na `key in (...)` wspólną pustą odpowiedzią (mapowania szukają `project = X`), więc zbiorcze zapytanie wycięłoby wszystkie trafienia w dev. Zostaje pętla (jedno `GET issue` na trafienie, projekt raz na listę, najwyżej 25). Do zrobienia, gdy mock zacznie obsługiwać `key in` |
| J-6 `assignee` | poprawione: `TextHygiene.normalize` i cięcie do 100 (`JiraToolViewsTest`) |
| J-7 DELETE z 200 | odmowa zostaje (`internal_error`), dodane zdarzenie logu `event=delete_unconfirmed status=200`; `internal_error` po `delete_issue` nie znaczy "nie usunięto" |
