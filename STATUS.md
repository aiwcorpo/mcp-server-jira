# STATUS - mcp-server-jira (ws-04)

M1: osiągnięty (2026-10-27) - cztery narzędzia Jiry (`search_issues`, `get_issue` READ_ONLY; `add_comment`, `transition_issue` UPDATE) na starterze 3.0.0-alpha-6, 84 testy na stubie Jiry, pełny zestaw e2e M1 (`mcp-e2e/run-m1.sh`: F1, A1, A2, A3, A9, A15) PASS na procesach hosta; recenzja M1 (J-M1..J-M6) poprawiona, obejścia alpha-5 usunięte, poprawki template'u c1258c4 przeniesione.

Start: `mcp-server-template` 7f65f8f (`./new-server.sh jira`), BOM `pl.aiwcorpo.mcp:mcp-platform-bom:3.0.0-alpha-6`
(kontrakty 2.0.2). M2 (manifest C6, `@ResourceRef`, `QueryScope`, `ResultFilter`) nie jest zaczęty.

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

- M2/M3: manifest C6, `@ResourceRef` (resolver `Jira::Issue` ma gotowy `canonicalIssueKey` i `findProject`),
  `QueryScope`, `ResultFilter` (wtedy `JiraService.searchIssues` traci ręczny filtr projektu).
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

## Miejsca na betę startera (M2/M3)

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
