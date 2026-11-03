# Bezpieczeństwo serwera

Niezmienniki platformy (`mcp-platform-plan/00-overview.md`) i to, gdzie serwer je spełnia.

| Niezmiennik | W serwerze |
| --- | --- |
| Ruch tylko od gateway'a: mTLS + wewnętrzny JWT, inaczej 401 | `server.ssl.client-auth: need` z CA wewnętrznym; `InternalJwtFilter` startera (CN `mcp-gateway`, ES256 z JWKS gateway'a, `iss`, `aud`, czas życia <= 60 s, `jti` jednorazowy w Redisie, `tool` == `params.name`, `rid` == `X-Request-Id`). Bez JWT tylko `/actuator/health*`. Test: `GatewayOnlyAccessTest` |
| Token IdP nigdy do systemu | serwer go nie dostaje; do systemu idzie wyłącznie poświadczenie z C3 (`CredentialContext.require`) |
| Fail-closed | brak JWKS, Redisa, konfiguracji albo poświadczenia -> 401/503/`credential_required`/brak startu, nigdy przepuszczenie; starter odrzuca relaksacje `dev` w innych profilach |
| Sekrety nigdy w logach, audycie, wynikach i błędach | nagłówki poświadczeń ukryte po odczycie, `BackendCredential.toString()` bez wartości, redakcja komunikatów w starterze; adapter zamienia błędy i nieczytelne odpowiedzi systemu na wyjątki bez treści; backend tylko po `https`. Testy: `thePatNeverAppearsInTheResultOrTheLog`, `anUnreadableBodyStaysOutOfTheResultAndTheLog`, `outsideDevTheBackendMustBeHttps` |
| "Nie istnieje" i "brak dostępu" -> `not_found_or_denied` | 404 i 403 systemu dają ten sam błąd i tekst. Testy: `missingAndForbiddenIssuesLookTheSame` (trzy narzędzia), `aMissingAndAForbiddenProjectLookTheSameAndAreNotSearched` |
| Bez dowolnego języka zapytań | JQL tylko ze `StructuredQuery` startera: pola warunków `status`, `assignee`, `summary`, `text` (podzbiór list normatywnych kontraktów 6.3.2), `ORDER BY` z zamkniętej listy, wartości w cudzysłowie z escapowaniem, sprawdzone wcześniej wzorcem; projekt nie jest warunkiem, tylko zakresem (`@ResourceRef` sprawdzony przez politykę przed zapytaniem, zakres `project in (...)` jest dopisany po warunkach), projekt z argumentu zawęża zakres (`QueryScope.narrowedTo`) i jest dodatkowo sprawdzany na wierszach odpowiedzi, a typ zgłoszenia serwer stosuje do wierszy strony. Testy: `filterValuesCannotCarryJql`, `searchesOneProjectWithJqlBuiltByTheServer`, `ResourcePolicyTest` |
| Treść systemu to dane | tytuł i opis zgłoszenia jako `UntrustedText` (`kind: "untrusted-data"`, `format`, `text`, `truncated`; CC-072), bez `renderedFields` (HTML); nazwy typu, statusu i przejścia (zmieniane przez administratorów projektu) są ucinane do 60 znaków i bez znaków sterujących |
| Tekst od modelu to dane, nie markup | `add_comment`, `edit_comment`, opis w `create_issue` i `update_issue` i komentarz worklogu zamykają treść w `{noformat}` i rozbija każdy `{noformat` w tekście: `!url!` (żądanie do obcego hosta w przeglądarce), `[tekst\|url]` i `[~user]` (powiadomienie) są w Jirze literalnym tekstem. Testy: `markupThatMakesRequestsOrNotificationsReachesJiraAsLiteralText`, `aNoformatTagInTheTextCannotEndTheBlock` |
| Zapis na tym zasobie, który widzi użytkownik | `add_comment` i `transition_issue` najpierw czytają klucz kanoniczny zgłoszenia (przeniesione `ABC-3` to `DEF-5`), piszą pod nim i zwracają go; brak widoczności to `not_found_or_denied`. Testy: `aMovedIssueIsWrittenUnderItsNewKeyAndReportedWithIt`, `aMovedIssueIsTransitionedUnderItsNewKeyAndReportedWithIt` |
| Wybór przejścia bez zgadywania | id wygrywa z nazwą, nazwa musi pasować w całości i do jednego przejścia; kilka pasujących to `invalid_argument` z listą id. Testy: `aNameSharedByTwoTransitionsIsRefusedWithTheIdsToChooseFrom`, `JiraServiceTest.aNameMatchesWholeOrNotAtAll` |
| Identyfikator podrzędny należy do ocenionego zasobu | `edit_comment` czyta zgłoszenie i komentarz jako użytkownik i porównuje id zgłoszenia z `self` komentarza przed zapisem; niezgodność to `not_found_or_denied`; ograniczenie widoczności komentarza jest odsyłane w PUT (inaczej Jira zdejmuje je przy edycji), nieodtwarzalne zatrzymuje edycję. Test: `aCommentOfAnotherIssueIsNotEditedThroughThisIssue` |
| Nieprzetestowana wersja systemu nie dostaje zapisów | `JiraCapabilityProbe` + `JiraWriteGate`: zapis tylko w `supported` z cechą `issue_write`; `degraded` (Jira < 9.12, >= 12, Cloud) i `unavailable` (także przed pierwszą sondą) blokują narzędzia zmieniające dane (`upstream_unavailable`), `MCP_JIRA_WRITES_ON_UNKNOWN_VERSION=true` otwiera tylko `unavailable`, a wcześniejsze `degraded` zostaje zapamiętane (`ObservedVersions`). Testy: `DegradedModeTest`, `JiraWriteGateTest`, `JiraCapabilityProbeTest`, `VersionFixturesTest` |
| Kursor nie jest uprawnieniem | `CursorCodec` startera: związany z użytkownikiem, narzędziem, zapytaniem i serwerem, niesie tylko `startAt`; zakres liczony od nowa na każdej stronie. Testy: `aCursorOnlyWorksForTheQueryAndTheUserItWasIssuedFor` |
| Audyt z `requestId` | jedno zdarzenie C8 `tool_call` na wywołanie (logger `pl.aiwcorpo.mcp.audit`, stdout, czysta linia JSON), `requestId` z JWT |

## Zasady dla kodu

- Każdy argument narzędzia to dane od modelu: wzorzec, długość, zamknięte listy, przed wywołaniem systemu.
  Szczegóły: `docs/jak-napisac-narzedzie.md`.
- Adresy: tylko `MCP_<SYSTEM>_URL` z konfiguracji, ścieżki API z szablonu URI z argumentami jako zmiennymi
  (kodowane przez `RestClient`), bez przekierowań; żadnych adresów z argumentów (SSRF). Poza `dev` wyłącznie
  `https`; `http` tylko jawnym `MCP_JIRA_ALLOW_PLAIN_HTTP` dla mocków środowiska dev.
- Odpowiedź Jiry: najwyżej `max-response-size` (1 MB), tylko pola domeny, nigdy w komunikacie ani logu (także
  odpowiedź 2xx, której nie da się sparsować). Przekierowanie (3xx), pusta odpowiedź 2xx i odpowiedź inna niż
  204 na przejście to `internal_error`, nie "brak zgłoszenia" ani sukces.
- Profil `dev` (klucze testowe, bez mTLS) jest w jarze, ale wstaje tylko na adresie pętli zwrotnej (starter,
  test `DevProfileLoopbackTest`). Serwer, do którego łączy się gateway, działa wyłącznie w konfiguracji
  bazowej.
- Serwer nie ma konta w systemie. Typ `service-account` (np. service desk, D10) przychodzi też przez C3, a
  wtedy serwer jest główną granicą autoryzacji i musi sprawdzać zakres zasobu sam (starter beta).
- Hinty MCP (`readOnlyHint`, `destructiveHint`) są informacją dla klienta, nie zabezpieczeniem.

## Wdrożenie

Konto bez uprawnień, sandbox systemd (`deploy/systemd/mcp-jira.service`), sekrety tylko w pliku
środowiska (0640, root:mcp) albo z magazynu sekretów, certyfikat serwera z SAN `mcp-jira.internal` z CA
wewnętrznego. Redis: użytkownik ACL `mcp-jira` z kluczami `jti:mcp-jira:*` i komendą `SET`.
