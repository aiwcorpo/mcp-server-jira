# mcp-jira: serwer MCP platformy

Serwer MCP dla systemu `jira`, zbudowany na `mcp-platform-starter` 3.0.0-beta-13 (Java 25, Spring Boot
4.1.0, Spring AI 2.0.0, kontrakty `mcp-contracts` 6.2.1 przez BOM). Stoi wyłącznie za gateway'em platformy: przyjmuje
ruch tylko po mTLS z wewnętrznym JWT gateway'a (C2), a do systemu docelowego woła z poświadczeniem
użytkownika, które gateway dołącza do każdego żądania (C3). Bez poprawnego JWT odpowiada 401, nie ma trybu
anonimowego.

Logowania użytkowników, decyzji o dostępie do narzędzi i potwierdzeń akcji destrukcyjnych serwer nie robi:
to zadania gateway'a. Serwer odpowiada za narzędzia, adapter do systemu, poprawne błędy (C10) i audyt (C8),
który dokłada starter.

## Szybki start

```bash
export JAVA_HOME=/ścieżka/do/jdk-25
./mvnw verify                 # build i testy (167 w `mcp-app` i modułach) (TestGateway + StubBackend Jiry); jar: mcp-app/target/mcp-jira.jar
```

Testy nie potrzebują Dockera ani środowiska `mcp-platform-dev`: `TestGateway` z `mcp-platform-test` wystawia
JWKS z kluczy testowych kontraktów i podpisuje wewnętrzne JWT, a `StubBackend` (test) udaje REST API v2 Jiry
ze ścieżkami i odpowiedziami jak mock Jiry z `mcp-platform-dev` (`mocks/jira`).

## Moduły i miejsce każdej rzeczy

| Moduł | Co w nim jest | W tym serwerze |
| --- | --- | --- |
| `mcp-core` | domena i porty, czysta Java | `JiraIssue`, `JiraProject` (C7 `Jira::Project{key, category}`), `JiraResourceModel` (typy zasobów i wzorce kluczy), `IssueSearch`, wyjątki domeny, porty `JiraUseCase` i `JiraResourcePort` |
| `mcp-application` | przypadki użycia (bez Springa) | `JiraService` (wybór przejścia, filtr projektu wyników), `CommentText` (komentarz jako literalny tekst) |
| `mcp-integration` | adaptery wyjściowe do systemu | `JiraResourceAdapter` (REST API v2, PAT z C3, timeouty, bez przekierowań, `https` poza `dev`), `BoundedBodyInterceptor` (limit rozmiaru odpowiedzi), `BackendResponseException` (odpowiedź bez treści w komunikacie), `JiraIssueResolver` i `JiraProjectResolver` (zasoby dla polityki: id, projekt i kategoria z odpowiedzi Jiry), `JiraResourceDescription`, `JiraCapabilityProbe` (sonda wersji) |
| `mcp-tools` | narzędzia MCP, powierzchnia publiczna | `JiraTool` (19 narzędzi, `@ResourceRef`, zapytanie przez `StructuredQuery`, limity argumentów przez `@ArgumentLimits`), `JiraToolViews` (kształt wyników), `JiraToolErrors` (wyjątki domeny -> kody C10), `JiraWriteGate` (zapisy tylko przy przetestowanej wersji Jiry) |
| `mcp-app` | bootstrap, wiring, jedyny jar, testy end-to-end | `McpServerApplication`, `McpToolsConfiguration` (lista narzędzi, `SchemaLimitedToolCallbacks`), `ApplicationWiringConfiguration` |

Zależności idą do środka: `mcp-tools` -> `mcp-application` -> `mcp-core` <- `mcp-integration`; `mcp-app` składa
całość. Resolvery zasobów (`@ResourceRef`) leżą w `mcp-integration` obok adaptera: atrybuty polityki pochodzą
z Jiry (`Jira::Project.category`, `""` bez kategorii).

## Wspierane wersje Jiry

Jira Data Center od **9.12** (LTS) do **11.x** (testowane na kształtach odpowiedzi 9.12 i 11.3; fixture w
`mcp-app/src/test/resources/fixtures/`, napisane z dokumentowanych odpowiedzi, nie nagrane z instancji). Jira Server
i Data Center starsze niż 9.12 są poza wsparciem Atlassiana i poza tym serwerem; Jira Cloud nie jest wspierana (decyzja W-7;
propozycja CC-213 w `mcp-contracts`).

Serwer używa wyłącznie REST API v2 (`/rest/api/2/...`), PAT jako `Authorization: Bearer` i tekstu w wiki markup
(opis, komentarze, worklogi). Wyszukiwanie to `POST /rest/api/2/search` ze stronicowaniem `startAt`. "Nowe Search API"
z Jiry 10.4 zastępuje Javowe API Lucene dla aplikacji (Atlassian, community.developer.atlassian.com, "New search API for
Jira Data Centre 10.4"); w tym, co sprawdziłem, nie ma informacji o zmianie ani usunięciu REST `/rest/api/2/search` w DC 10.x i 11.x.
Nie potwierdziłem tego na instancji (README, "Pierwsze uruchomienie u klienta"). Dlatego dziś jest jeden adapter
wyszukiwania; wymiana kosztuje jedną klasę (`JiraResourceAdapter.search`).

**Sonda wersji** (`JiraCapabilityProbe`, starter `CapabilityProbe`): przy starcie i co 5 minut (30 s, gdy Jira nie odpowiada)
serwer czyta `GET /rest/api/2/serverInfo` bez poświadczenia (`version` albo `versionNumbers`, `deploymentType`) i ustala stan:

| Stan | Kiedy | Co robi serwer |
| --- | --- | --- |
| `supported` | DC od 9.12.0 do 11.x | wszystkie narzędzia (cechy `issue_read`, `issue_write`, `search_offset`) |
| `degraded` | wersja starsza niż 9.12, 12.0 i nowsza, `deploymentType` Cloud | odczyty działają, każde narzędzie zmieniające dane kończy się `upstream_unavailable` ("This Jira version is not supported for changes", nic nie zostało zmienione) |
| `unavailable` | brak odpowiedzi, `serverInfo` zablokowane, brak wersji | serwer działa jak przed sondą (zapisy nie są blokowane: Jira, która ukrywa wersję, nie jest dowodem na nieprzetestowaną) |

Stan widać w logu (`backend capability: probe=jira state=...`), w metryce `mcp_backend_capability{probe="jira",state}` i
w szczegółach `/actuator/health` (`backendCapability`). Serwer startuje w każdym ze stanów. Aby zmienić granice,
zmień stałe w `JiraCapabilityProbe` i dopisz fixture nowej wersji.

## Narzędzia

Kolumna "Zasób" to `@ResourceRef` (C7): polityka Cedar ocenia akcję na zasobie, tak jak go opisuje Jira. "Uprawnienie" to grupa C9
(`mcp-jira-read`, `mcp-jira-write`, `mcp-jira-destructive`). Poświadczenie wszędzie: `user-secret`, PAT jako `Bearer`, jedyny przyjmowany typ.

| Narzędzie (klient: `jira__<nazwa>`) | Tytuł | Ryzyko | Akcja | Uprawnienie | Zasób (argument) | Co robi |
| --- | --- | --- | --- | --- | --- | --- |
| `search_issues` | Szukaj zgłoszeń | `READ_ONLY` | `read` | `mcp-jira-read` | `Jira::Project` (`project`) | strona ok. 25 zgłoszeń projektu; filtry `status`, `assignee`, `type`, `summary`, `text`; `order_by` (`updated` domyślnie, `created`, `priority`, `key`), `order`, `cursor` |
| `get_issue` | Pobierz zgłoszenie | `READ_ONLY` | `read` | `mcp-jira-read` | `Jira::Issue` (`issueKey`) | pola zgłoszenia, tytuł i opis jako `UntrustedText` |
| `list_comments` | Lista komentarzy | `READ_ONLY` | `read` | `mcp-jira-read` | `Jira::Issue` | 25 komentarzy na stronę, najstarsze pierwsze, tekst jako `UntrustedText` do 5000 znaków, `cursor` |
| `list_transitions` | Dostępne przejścia | `READ_ONLY` | `read` | `mcp-jira-read` | `Jira::Issue` | id, nazwa i status docelowy przejść dostępnych teraz |
| `get_project` | Pobierz projekt | `READ_ONLY` | `read` | `mcp-jira-read` | `Jira::Project` (`project`) | nazwa, kategoria, typy zgłoszeń, komponenty, wersje (po 50) |
| `list_projects` | Lista projektów | `READ_ONLY` | `read` | `mcp-jira-read` | `Jira::Project` (elementy wyniku, `ResultFilter`) | projekty widoczne dla konta i dozwolone przez politykę, ok. 25 na stronę, `cursor` |
| `find_assignable_users` | Znajdź użytkowników do przypisania | `READ_ONLY` | `read` | `mcp-jira-read` | `Jira::Project` (`project`) | do 20 użytkowników po fragmencie loginu lub nazwy; bez e-maili; nazwa wyświetlana jako `UntrustedText` |
| `list_worklogs` | Lista wpisów czasu pracy | `READ_ONLY` | `read` | `mcp-jira-read` | `Jira::Issue` | 25 wpisów na stronę, komentarz jako `UntrustedText`, `cursor` |
| `list_attachments` | Lista załączników | `READ_ONLY` | `read` | `mcp-jira-read` | `Jira::Issue` | metadane do 50 załączników (nazwa jako `UntrustedText`); treści nie pobiera |
| `add_comment` | Dodaj komentarz | `UPDATE` | `update` | `mcp-jira-write` | `Jira::Issue` | komentarz w `{noformat}`; powtórzenie dubluje komentarz |
| `edit_comment` | Edytuj komentarz | `UPDATE` | `update` | `mcp-jira-write` | `Jira::Issue` | zamienia tekst komentarza (przynależność komentarza do zgłoszenia sprawdzana przed zapisem); powtórzenie zostawia ten sam stan |
| `transition_issue` | Zmień status zgłoszenia | `UPDATE` | `update` | `mcp-jira-write` | `Jira::Issue` | przejście po id lub nazwie; powtórzenie po sukcesie kończy się błędem (przejście niedostępne) |
| `create_issue` | Utwórz zgłoszenie | `UPDATE` | `update` | `mcp-jira-write` | `Jira::Project` (`project`) | projekt, typ, tytuł; opis, do 10 etykiet, priorytet, przypisany opcjonalnie; opis w `{noformat}`; powtórzenie tworzy drugie zgłoszenie |
| `update_issue` | Edytuj zgłoszenie | `UPDATE` | `update` | `mcp-jira-write` | `Jira::Issue` | tytuł, opis (w `{noformat}`), etykiety (zastępują zbiór), priorytet; co najmniej jedno pole; powtórzenie zostawia ten sam stan |
| `assign_issue` | Przypisz zgłoszenie | `UPDATE` | `update` | `mcp-jira-write` | `Jira::Issue` | stan docelowy: przypisany użytkownik |
| `unassign_issue` | Usuń przypisanie zgłoszenia | `UPDATE` | `update` | `mcp-jira-write` | `Jira::Issue` | stan docelowy: bez przypisanego |
| `link_issues` | Powiąż zgłoszenia | `UPDATE` | `update` | `mcp-jira-write` | `Jira::Issue` x2 (`outwardIssueKey`, `inwardIssueKey`) | link o nazwie typu (np. `Blocks`); oba zgłoszenia ocenia polityka; powtórzenie może zostawić drugi link |
| `add_worklog` | Zaloguj czas pracy | `UPDATE` | `update` | `mcp-jira-write` | `Jira::Issue` | czas w formacie Jiry (`1h 30m`), komentarz w `{noformat}`; powtórzenie loguje czas drugi raz |
| `delete_issue` | Usuń zgłoszenie | `DESTRUCTIVE` | `delete` | `mcp-jira-destructive` | `Jira::Issue` | `DELETE /rest/api/2/issue/{key}`; kod TOTP weryfikuje gateway |

Nie ma: tablic i sprintów (wymagają typów zasobów, CC-211), pobierania i wysyłania załączników (CC-212), filtrów po etykiecie,
komponencie, wersji i dacie (CC-210), obserwujących, usuwania komentarzy i worklogów, pól własnych, JSM (osobny serwer `servicedesk`).

Błędy (C10) wszystkich narzędzi: `not_found_or_denied` (404 i 403 z Jiry, ten sam stały tekst; brak zakresu projektu w grantach daje
`access_denied` przed zapytaniem do Jiry), `credential_required` (brak poświadczenia albo 401 z Jiry), `invalid_argument` (zły
identyfikator lub argument naruszający schemat, odrzucona wartość, niedostępne lub niejednoznaczne przejście, zły kursor;
teksty stałe, bez treści z Jiry), `upstream_unavailable` (też zmiana przy stanie `degraded`), `internal_error` (też przekierowanie,
pusta, nieczytelna albo za duża odpowiedź Jiry).

Zasady wyników i zapytań:

- **JQL pisze tylko serwer**, ze `StructuredQuery` startera (`policy.queryScope("Jira::Project").jql(...)`), według układu z kontraktów
  6.2.1 (`query-rendering`, CC-160): `(warunki) AND project in (...) ORDER BY <pole> <kierunek>`. Warunki to najwyżej `status = "..."`,
  `assignee = "..."`, `summary ~ "..."` i `text ~ "..."` (pola i operatory z list normatywnych; wartość w cudzysłowie, z escapowaniem,
  znak sterujący odrzucony), łączone `AND` w jednym nawiasie. Zakres `project in ("<klucz>")` to projekt z argumentu
  (`QueryScope.narrowedTo`), dopisany po warunkach. `ORDER BY` tylko z listy `updated`, `created`, `priority`, `key`
  (wzorzec w schemacie argumentu). `issuetype` nie jest polem warunków w kontrakcie, więc `jql_filter.type` serwer stosuje do wierszy
  strony po odpowiedzi Jiry. Wartości są wcześniej sprawdzane wzorcem (nazwa: litery, cyfry, spacja, `_./()&'-`; użytkownik:
  `[A-Za-z0-9][A-Za-z0-9._@+-]{0,99}`), a w `text` i `summary` znaki składni wyszukiwania pełnotekstowego Jiry i operatory
  `AND`, `OR`, `NOT` są zamieniane na spacje. Surowego JQL nie ma w żadnym argumencie.
- **Listy** (`search_issues`, `list_comments`, `list_worklogs`, `list_projects`) zwracają `PagedResult`: `items`, `nextCursor`,
  `truncated`, `omitted`; bez `total`. Kursor to `CursorCodec` startera (szyfrowany i podpisany, związany z użytkownikiem, narzędziem,
  zapytaniem i serwerem, ważny 15 minut); niesie wyłącznie pozycję w stronicowaniu Jiry (`startAt`), nie uprawnienia. Kursor z
  innego zapytania, innego użytkownika lub narzędzia, sfałszowany i przeterminowany daje ten sam `invalid_argument`.
  Wszystkie instancje serwera muszą mieć ten sam `MCP_PAGING_CURSOR_KEY`.
- **Strony po filtrze polityki** (`search_issues`, `list_projects`): serwer dopełnia stronę kolejnymi stronami Jiry (`PageFiller`, do 4 odczytów),
  żeby krótka strona z `nextCursor` nie zdradzała ukrytych zgłoszeń. Strona ma więc zwykle 25 elementów, a gdy część wypadła, do 49. Przy
  filtrze `type` stosowanym po odpowiedzi Jiry strona po 4 odczytach może nadal być krótka; wtedy tylko to jest sygnałem.
- **Zasoby:** `project` i `issueKey` to `@ResourceRef` (`Jira::Project`, `Jira::Issue`). Przed metodą starter woła resolver z poświadczeniem
  użytkownika i ocenia politykę Cedar na zasobie takim, jakim opisuje go Jira: zgłoszenie przeniesione do projektu z kategorią `Restricted`,
  nieistniejące i niewidoczne dają to samo `not_found_or_denied`. Resolver zgłoszenia pobiera zgłoszenie i kategorię jego projektu.
  `ResultFilter` usuwa z list elementy, których polityka nie dopuszcza (`search_issues`: klucz trafienia; `list_projects`: klucz projektu).
- **Podrzędne identyfikatory:** `edit_comment` przed zapisem czyta zgłoszenie (id) i komentarz jako użytkownik i porównuje id zgłoszenia z
  linkiem `self` komentarza; komentarz spoza zgłoszenia, które ocenia polityka, to `not_found_or_denied`. Pozostałe narzędzia nie biorą
  identyfikatora podrzędnego (`list_*` i `add_*` biorą tylko zgłoszenie; `link_issues` bierze dwa klucze zgłoszeń, oba oceniane).
- **Treść z Jiry to dane:** tytuł, opis, komentarze, komentarze worklogów, nazwy wyświetlane i nazwy plików to `UntrustedText` (CC-072:
  `{kind: "untrusted-data", format: "text/plain", text, truncated}`); wiki markup zamieniany na czysty tekst (`JiraWikiText`). Nazwy
  typu, statusu, przejścia, projektu, komponentu i wersji (ustawiane przez administratorów) są ucinane i bez znaków sterujących.
  Serwer nie pobiera `renderedFields` (HTML), e-maili, awatarów, linków do treści załączników ani pól własnych. Wynik jednego
  wywołania nie przekracza 1 MB (`tools.max-result-size` startera).
- **Tekst od modelu to dane, nie markup:** `add_comment`, `edit_comment`, opis w `create_issue` i `update_issue` oraz komentarz w `add_worklog`
  trafiają do Jiry w bloku `{noformat}` (`!url!` ładuje obraz z obcego hosta w przeglądarce każdego, kto otworzy zgłoszenie, `[tekst|url]` to link,
  `[~user]` powiadamia użytkownika), a `{noformat` w treści jest zamieniane na `{-noformat`. Opis i komentarze są przez to preformatowane. Tytuł
  to pole jednowierszowe, nie markup.
- **Powtarzalność `UPDATE` (CC-204):** `UPDATE` jest dostarczane co najmniej raz. `update_issue`, `assign_issue`, `unassign_issue` i `edit_comment` ustawiają stan docelowy,
  więc powtórzenie jest nieszkodliwe. `add_comment`, `create_issue`, `add_worklog` i `link_issues` nie mają klucza, który Jira pozwalałaby sprawdzić, więc powtórzenie dubluje wpis;
  opisy narzędzi mówią to modelowi. `transition_issue` po sukcesie kończy się błędem przy powtórzeniu (przejście nie jest już dostępne).
- **`transition_issue`:** liczbowe `transition` pasujące do id przejścia wygrywa; nazwa musi pasować w całości (bez rozróżniania wielkości liter)
  i do jednego przejścia; kilka pasujących to `invalid_argument` z listą par id -> status docelowy. Jira potwierdza przejście kodem 204; inna odpowiedź 2xx i 3xx to `internal_error`.
- **`delete_issue`:** `DESTRUCTIVE`, akcja Cedar `delete`. Serwer nie pyta o potwierdzenie: kod TOTP weryfikuje gateway (C11). Zgłoszenie przeniesione (klucz z Jiry inny niż z argumentu)
  nie jest usuwane: `invalid_argument` z nowym kluczem. `internal_error` po `delete_issue` (np. 200 zamiast 204 z proxy) nie znaczy "nie usunięto".
- Pozostałe zapisy działają na kluczu kanonicznym zgłoszenia (przeniesione `ABC-3` to `DEF-5`) i go zwracają. Jira potwierdza `update_issue`, `assign_issue` (204) i `link_issues` (201);
  inna odpowiedź 2xx to `internal_error`.
- **Schemat wejścia** powstaje z `@ArgumentLimits` na argumentach `JiraTool` (`SchemaLimitedToolCallbacks`): zamknięte obiekty, `maxLength`, `maxItems`, wzorce
  kluczy `JiraIssueKey` i `JiraProjectKey` z `mcp-contracts`. Starter egzekwuje schemat przy każdym wywołaniu: naruszenie to C10 `invalid_argument`.
- **Adapter:** poza `dev` tylko `https` (`MCP_JIRA_ALLOW_PLAIN_HTTP=true` dla mocków `mcp-platform-dev`), bez przekierowań, odpowiedź najwyżej 1 MB
  (`max-response-size`), a pusta, nieczytelna albo bez klucza odpowiedź to `BackendResponseException` bez treści w komunikacie.

```
gateway --mTLS (CN mcp-gateway) + JWT C2 (aud mcp-jira, tool get_issue) + X-MCP-Credential: Bearer <PAT>-->
  starter: InternalJwtFilter (inaczej 401) -> CredentialHeaderFilter -> AuditingToolCallback (C8)
  -> JiraTool.getIssue: walidacja issueKey
  -> JiraService -> JiraResourceAdapter: CredentialContext.require("jira") + credential::applyTo
  -> GET {MCP_JIRA_URL}/rest/api/2/issue/{key}?fields=summary,description,status,issuetype,assignee,project,updated
```

Ścieżki API Jiry (wszystkie z poświadczeniem użytkownika, poza `serverInfo`): `GET serverInfo`, `GET project`, `GET project/{key}`, `GET issue/{key}`,
`POST search`, `GET issue/{key}/comment`, `POST issue/{key}/comment`, `GET` i `PUT issue/{key}/comment/{id}`, `GET` i `POST issue/{key}/transitions`, `POST issue`,
`PUT issue/{key}`, `PUT issue/{key}/assignee`, `POST issueLink`, `GET` i `POST issue/{key}/worklog`, `GET issue/{key}?fields=attachment`,
`GET user/assignable/search`, `DELETE issue/{key}` (wszystkie pod `/rest/api/2/`). Adapter nie podąża za przekierowaniami
(nagłówek z poświadczeniem nie może pójść pod inny adres), czyta tylko pola domeny i nie wkłada treści odpowiedzi Jiry do żadnego komunikatu.

## Wymagane uprawnienia konta i zakres tokenu

Serwer działa jako użytkownik: PAT Data Center nie ma zakresów (scopes), dziedziczy uprawnienia konta, a Jira decyduje o wszystkim po swojej stronie.
Konto, które użytkownik podłącza w portalu, powinno mieć w projektach, na których ma działać serwer:

| Narzędzia | Uprawnienie projektu w Jirze |
| --- | --- |
| odczyty, `search_issues`, `get_project`, `list_*` | Browse Projects (dla `list_attachments` i `list_comments` także View Comments i View Attachments w schemacie uprawnień) |
| `find_assignable_users` | Browse Projects (Jira zwraca użytkowników, którym można przypisywać) |
| `add_comment`, `edit_comment` | Add Comments; Edit Own/All Comments |
| `transition_issue` | Transition Issues (i ekrany/warunki workflow) |
| `create_issue` | Create Issues |
| `update_issue` | Edit Issues; ewentualnie Schedule/Set Issue Security dla pól |
| `assign_issue`, `unassign_issue` | Assign Issues |
| `link_issues` | Link Issues |
| `add_worklog` | Work On Issues |
| `delete_issue` | Delete Issues |

PAT musi być włączony na instancji (`atlassian.pats.enabled`, domyślnie tak; administrator może go wyłączyć) i ma maksymalny czas życia (domyślnie 365 dni).
Polityka platformy (grupy `mcp-jira-*` i grant na projekty) zwęża to, co serwer pozwoli zrobić; nigdy nie poszerza uprawnień konta w Jirze.

## Typ poświadczenia, manifest i metryki (M4)

- Typ poświadczenia: `manifest.accepted-credential-types` to `[user-secret]` (zmienna `MCP_ACCEPTED_CREDENTIAL_TYPES`).
  Pierwszy typ zbioru jest typem domyślnym i trafia do Cedar jako `context.credentialType`; właściwości
  `credential-type` nie ma od startera beta-7. Żądanie z `X-MCP-Credential` musi mieć `X-MCP-Credential-Type`
  równe `user-secret`; brak nagłówka, inny typ (`user-oauth`, `token-exchange`, `service-account`) albo zapis
  w innej wielkości liter kończy się `internal_error` przed polityką, resolverami i Jirą, a w audycie
  `policy_decision` z powodem `credential_type_mismatch`. Zbioru nie wolno mieszać z `service-account`.
- `not_found_or_denied` ma w całej platformie jeden tekst (`ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE`).
  Grant na zasób jest oceniany przed resolverem: wywołujący bez zakresu projektu dostaje tę odmowę, zanim serwer
  zapyta Jirę.
- Manifest do rejestracji w gateway'u to artefakt budowy `mcp-app/target/mcp-manifest.json`, stała ścieżka. Powstaje
  w fazie `package` każdego buildu, także z `-DskipTests` (`exec-maven-plugin` uruchamia `ManifestExport` z `src/main`,
  które nie startuje serwera: czyta `application.yml` bez profilu, wersja z pom, `internalUrl` i `acceptedTypes` z
  konfiguracji; pusty `internalUrl` dostaje wartość informacyjną `https://mcp-jira.internal:8443/mcp`). Te same kontrole
  co przy starcie serwera zatrzymują build, gdy gateway odrzuciłby manifest. Po `package` test `ManifestArtifactIT`
  (failsafe) porównuje plik z manifestem serwera w kontekście testowym (bez `version` i `internalUrl`, które test
  nadpisuje), z hashami definicji i wersją z pom. CI: `ci/run.sh verify`, potem krok
  rejestracji bierze `mcp-app/target/mcp-manifest.json` i wysyła go certyfikatem `mcp-registry-ci-mcp-jira`; odpowiedź
  działającego serwera nie jest źródłem rejestracji. Narzędzia `READ_ONLY` gateway
  zatwierdza sam; nowe i zmienione narzędzia `UPDATE` i `DESTRUCTIVE` czekają na `approve` administratora.
- `search_issues` i `list_projects` nie mają `hasMore` ani liczników; o dalszych stronach mówi tylko `nextCursor` (patrz "Zasady wyników i zapytań").
- Profil `devstack` (stos deweloperski w kontenerach: `SPRING_PROFILES_ACTIVE=devstack` jako jedyny profil, zmienna
  środowiska `MCP_DEVSTACK_ACKNOWLEDGED=true` i `AIWCORPO_MCP_PLATFORM_DEVSTACK_ACKNOWLEDGED=true`, do tego
  `AIWCORPO_MCP_PLATFORM_POLICY_ALLOW_TEST_KEYS=true` i `AIWCORPO_MCP_PLATFORM_INTERNAL_JWT_ALLOW_TEST_KEYS=true`)
  dodaje do manifestu `"environment": "dev"`; gateway w trybie produkcyjnym odrzuca taki manifest
  (`environment_mismatch`). To konfiguracja stosu (ws-01), nie obrazu: obraz serwera nie ma `ENV` z profilem ani
  zgodami.
- Alerty: licznik `mcp_audit_authentication_suppressed_total{component="server",reason}` rośnie, gdy starter
  pomija zdarzenia `authentication` ponad budżet (zalew żądań bez ważnego JWT). Alert: wzrost licznika w oknie
  5 minut na dowolnym `reason` powyżej progu ustalonego z ruchem bazowym (stały odczyt, brak wartości domyślnej
  w kodzie). Poprzednia nazwa `mcp_server_audit_authentication_suppressed_total` już nie istnieje: panele i reguły
  muszą używać nowej. Endpoint `prometheus` nie jest wystawiony (`management.endpoints.web.exposure.include:
  health`), więc licznik czyta się z metryk, które wdrożenie samo udostępnia (ws-01).

## Instalacja, obsługa, pierwsze uruchomienie

### Instalacja

- **VM z systemd** (zalecane, bez kontenera): `deploy/scripts/install.sh mcp-app/target/mcp-jira.jar`, plik środowiska z
  `deploy/systemd/mcp-jira.env.template` do `/etc/mcp-jira/mcp-jira.env`, certyfikaty PKCS12, `systemctl enable --now mcp-jira`.
  Pełny opis kroków, układ plików i typowe problemy: `docs/operations/deployment-vm-systemd.md`. Wymagane: JRE 25, Redis z
  użytkownikiem ACL `mcp-jira`, od gateway'a dostęp do portu 8443, z serwera do JWKS gateway'a, Redisa, repozytorium paczek polityk
  i API Jiry po `https`. `MCP_JIRA_URL` to adres bazowy bez `/rest/api/2`; gdy Jira ma certyfikat z wewnętrznego CA, ustaw `MCP_JIRA_SSL_BUNDLE`.
- **Kontener:** `Dockerfile` w katalogu głównym pakuje jar z `./mvnw verify` (`eclipse-temurin:25-jre`, glibc, użytkownik `mcp`, port 8443).
  Obraz buduje `ci/run.sh image` jako `mcp-server-jira:<prefiks>-<sha7>`. Zmienne środowiska jak w szablonie powyżej; certyfikaty i klucz
  polityk jako zamontowane pliki. Profilu `dev` w kontenerze nie ma i nie wolno go ustawiać.
- Po instalacji zarejestruj manifest z `mcp-app/target/mcp-manifest.json` w gateway'u (krok pipeline'u serwera) i zatwierdź nowe narzędzia
  `UPDATE` i `DESTRUCTIVE` (`approve`). Paczka polityk dla nowych narzędzi: `mcp-policies` (ws-09).

### Codzienna obsługa

- Zdrowie: `deploy/scripts/healthcheck.sh` (mTLS). Szczegół `backendCapability` w `/actuator/health` (z wewnętrznym JWT) pokazuje stan sondy wersji.
- Logi: stdout do journald; audyt C8 to linie JSON z loggera `pl.aiwcorpo.mcp.audit`. Sonda wersji loguje zmianę stanu
  (`backend capability: probe=jira state=...`); stan `degraded` po aktualizacji Jiry znaczy, że wersja wyszła poza 9.12 do 11.x i zapisy są wyłączone
  do czasu wydania serwera, które ją obejmie.
- Aktualizacja serwera: `deploy/scripts/update.sh <nowy.jar>`; po zmianie narzędzi zarejestruj nowy manifest i zatwierdź zmiany.
- Rotacja `MCP_PAGING_CURSOR_KEY`: nowa wartość na wszystkich instancjach naraz; kursory sprzed zmiany wygasają (są ważne 15 minut).
- Użytkownik widzi `credential_required`, gdy jego PAT wygasł (domyślnie po 365 dniach) albo został wycofany; naprawia to w portalu.

### Pierwsze uruchomienie u klienta (tylko GET)

Zanim serwer dostanie ruch, sprawdź na instancji klienta samymi żądaniami `GET` (nic nie zmieniają):

1. `GET <MCP_JIRA_URL>/rest/api/2/serverInfo` bez poświadczenia: `version` w zakresie 9.12 do 11.x, `deploymentType` `Server`. Gdy 401 albo 403, sonda
   zgłosi `unavailable` (zapisy nie są blokowane), ale wersji nie da się potwierdzić.
2. PAT włączone: z PAT-em użytkownika testowego `GET /rest/api/2/myself` (nagłówek `Authorization: Bearer <PAT>`) daje 200. 401 przy poprawnym tokenie
   znaczy zwykle, że administrator wyłączył PAT (`atlassian.pats.enabled`).
3. `GET /rest/api/2/project` i `GET /rest/api/2/project/<KLUCZ>`: projekty widoczne dla konta; w odpowiedzi projektu `projectCategory` (kategoria `Restricted` zamyka dostęp w polityce).
4. `GET /rest/api/2/issue/<KLUCZ-1>?fields=summary,project` i `GET /rest/api/2/issue/<KLUCZ-1>/comment?maxResults=1`.
5. Jedno zapytanie przez serwer: `search_issues` na projekcie testowym i `list_comments` na zgłoszeniu; sprawdź, że `nextCursor` działa (druga strona), a w
   wynikach nie ma adresów e-mail.
6. Nie potwierdzone bez instancji: czy `POST /rest/api/2/search` w twojej wersji DC 10.x i 11.x odpowiada tak jak w 9.12 (dokumentacja "nowego Search API" 10.4
   dotyczy Javowego API aplikacji). Jeśli odpowiada 404 albo 410, zgłoś to: potrzebny jest drugi adapter wyszukiwania.

## Konfiguracja

Konfiguracja bazowa (`mcp-app/src/main/resources/application.yml`) jest produkcyjna: mTLS, JWKS gateway'a po
TLS z CA wewnętrznym, `jti` w Redisie. Profil `dev` (`application-dev.yml`, testy i uruchomienie lokalne)
wyłącza TLS i mTLS, trzyma `jti` w pamięci i dopuszcza klucze testowe; starter odrzuca te ustawienia poza `dev`.

| Zmienna | Znaczenie | Domyślnie |
| --- | --- | --- |
| `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD` | PKCS12: certyfikat serwera (SAN `mcp-jira.internal`) i CA wewnętrzne; truststore służy też do JWKS | wymagane |
| `MCP_GATEWAY_JWKS_URI` | JWKS wewnętrznego JWT, np. `https://mcp-gateway.internal:8443/.well-known/jwks.json` | wymagane |
| `MCP_GATEWAY_ISSUER` | `iss` C2 | `https://mcp-gateway.internal` |
| `MCP_CONNECT_URL` | strona portalu do połączenia konta, z `{system}`; trafia do `credential_required` | wymagane |
| `MCP_JIRA_URL` | adres bazowy Jiry (bez `/rest/api/2`); poza `dev` tylko `https` | wymagane |
| `MCP_JIRA_ALLOW_PLAIN_HTTP` | `true` dopuszcza `http` poza `dev`, wyłącznie dla mocków `mcp-platform-dev` (PAT idzie wtedy otwartym tekstem; ostrzeżenie w logu) | `false` |
| `MCP_JIRA_SSL_BUNDLE` | bundle SSL dla HTTPS systemu, np. `mcp-internal-ca`; puste = zaufanie JDK | puste |
| `MCP_PAGING_CURSOR_KEY` | klucz kursorów stronicowania (Base64, co najmniej 32 bajty); ten sam na wszystkich instancjach; wygeneruj: `openssl rand -base64 32` | wylosowany przy starcie (kursor działa tylko na tej instancji) |
| `MCP_REDIS_HOST`, `MCP_REDIS_PORT`, `MCP_REDIS_USER`, `MCP_REDIS_PASSWORD` | Redis dla jednorazowego `jti` (użytkownik ACL `mcp-jira`, klucze `jti:mcp-jira:*`) | `localhost`, `6379`, `mcp-jira`, puste |
| `MCP_PORT`, `MCP_BIND` | port i adres nasłuchu | `8443`, `0.0.0.0` |

Endpoint MCP to `/mcp` (domyślne ustawienie startera, tak jak oczekuje gateway: `internal-url:
https://mcp-jira.internal:8443/mcp`). Bez JWT odpowiada tylko `/actuator/health` (i `liveness`,
`readiness`), ale TLS i tak wymaga certyfikatu klienta z CA wewnętrznego.

Poza profilem `dev` serwer nie wstaje bez `MCP_GATEWAY_JWKS_URI` (tylko `https`), `MCP_CONNECT_URL`,
`MCP_JIRA_URL` (`https`) i plików TLS, a od startera 3.0.0-alpha-4 także wtedy, gdy system nie jest na liście
`SystemId` kontraktów (`jira`, `confluence`, `gitlab`, `servicedesk`, `opensearch`). Prawdziwy serwer musi więc
mieć nazwę z tej listy (`new-server.sh` innej nie przyjmie bez `--trial`). Serwer próbny i sam template działają
tylko w profilu `dev`, z ostrzeżeniem w logu.

`java` w `PATH` może być starsza niż 25 (wtedy `UnsupportedClassVersionError`), dlatego poniżej zawsze
`$JAVA_HOME/bin/java`.

## Uruchomienie lokalne bez środowiska

Profil `dev` (musi być jedynym aktywnym profilem): zwykłe HTTP na 127.0.0.1, `jti` w pamięci, bez Redisa,
certyfikatów i sekretów. Gateway'a nie ma, więc każde żądanie z JWT kończy się 503 (JWKS niedostępny); ten tryb
służy do sprawdzenia, że serwer wstaje i odrzuca ruch bez JWT. Narzędzia sprawdzają testy (`./mvnw verify`).

```bash
export JAVA_HOME=/ścieżka/do/jdk-25
./mvnw -B verify
SPRING_PROFILES_ACTIVE=dev MCP_PORT=18500 "$JAVA_HOME/bin/java" -jar mcp-app/target/mcp-jira.jar
# w drugim terminalu:
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:18500/actuator/health          # 200
curl -s -o /dev/null -w '%{http_code}\n' -X POST -H 'Content-Type: application/json' \
     -H 'Accept: application/json, text/event-stream' \
     -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' http://127.0.0.1:18500/mcp            # 401
```

Wartości domyślne profilu są w `mcp-app/src/main/resources/application-dev.yml` (backend
`http://localhost:18081`, portal `http://localhost:18090`); każdą można nadpisać zmienną z tabeli wyżej.

Ryzyko: `application-dev.yml` jest w jarze produkcyjnym, a profil `dev` przyjmuje JWT podpisane opublikowanymi
kluczami testowymi kontraktów i nie wymaga certyfikatu klienta. Dlatego `dev` słucha wyłącznie na adresie pętli
zwrotnej: starter (od 3.0.0-alpha-5) odmawia startu z `dev` przy innym `server.address`, a test
`DevProfileLoopbackTest` pilnuje tego w serwerze. Nie ustawiaj `SERVER_ADDRESS` ani `MCP_BIND` przy `dev` i
nigdy nie włączaj `dev` na hoście ani w kontenerze, do którego łączy się gateway: tam działa wyłącznie
konfiguracja bazowa z mTLS.

## Uruchomienie z mcp-platform-dev (mTLS)

Konfiguracja produkcyjna na hoście, z certyfikatami i Redisem środowiska `mcp-platform-dev` (`make up` już
uruchomione). Działa tylko dla systemu z listy `SystemId`: środowisko ma certyfikaty (`certs/mcp-<system>`, SAN
`mcp-<system>.internal`, z `scripts/gen-certs.sh`) i użytkowników Redisa (`mcp-<system>`, `redis/start.sh`)
dokładnie dla tych pięciu systemów. Nowy system wymaga najpierw zmiany kontraktu (`SystemId`) i dopisania go w
ws-01 (lista `identities` w `gen-certs.sh`, pętla w `redis/start.sh`); bez tego zostaje tryb `dev`.

Magazyny PKCS12 i Redis są chronione hasłami z `$MCP_PLATFORM_DEV/.secrets/dev.env`, więc ten wariant wymaga
ich odczytu. Przykład dla serwera `mcp-jira`:

```bash
export JAVA_HOME=/ścieżka/do/jdk-25
export MCP_PLATFORM_DEV="$PWD/../mcp-platform-dev"           # ścieżka bezwzględna
export MCP_TLS_STORE_PASSWORD="$(grep '^TLS_STORE_PASSWORD=' "$MCP_PLATFORM_DEV/.secrets/dev.env" | cut -d= -f2-)"
export MCP_REDIS_PASSWORD="$(grep '^REDIS_MCP_JIRA_PASSWORD=' "$MCP_PLATFORM_DEV/.secrets/dev.env" | cut -d= -f2-)"
export MCP_TLS_KEYSTORE="$MCP_PLATFORM_DEV/certs/mcp-jira/keystore.p12"
export MCP_TLS_TRUSTSTORE="$MCP_PLATFORM_DEV/certs/ca/truststore.p12"
export MCP_PORT=18501 MCP_BIND=127.0.0.1 MCP_REDIS_PORT=16379
export MCP_JIRA_URL=http://localhost:18081 MCP_JIRA_ALLOW_PLAIN_HTTP=true   # mock Jiry środowiska jest po http
export MCP_GATEWAY_JWKS_URI=https://localhost:18445/.well-known/jwks.json
export MCP_CONNECT_URL='http://localhost:18090/connections?system={system}'
"$JAVA_HOME/bin/java" -jar mcp-app/target/mcp-jira.jar
# w drugim terminalu (te same zmienne MCP_PLATFORM_DEV), certyfikat klienta gateway'a:
TLS=(--cacert "$MCP_PLATFORM_DEV/certs/ca/ca.crt" --resolve mcp-jira.internal:18501:127.0.0.1
     --cert "$MCP_PLATFORM_DEV/certs/mcp-gateway/tls.crt" --key "$MCP_PLATFORM_DEV/certs/mcp-gateway/tls.key")
curl -s -o /dev/null -w '%{http_code}\n' "${TLS[@]}" https://mcp-jira.internal:18501/actuator/health   # 200
curl -s -o /dev/null -w '%{http_code}\n' "${TLS[@]}" -X POST -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"ping"}' https://mcp-jira.internal:18501/mcp                     # 401
```

Sprawdzone 2026-10-27. Połączenie bez certyfikatu klienta jest zrywane w
handshake'u. Pełne wywołanie narzędzia wymaga gateway'a z hosta (`mcp-gateway/scripts/run-dev-host.sh`, JWKS na
`localhost:18445`) skonfigurowanego na ten serwer.

Na hoście ze środowiskiem `mcp-platform-dev` (`make up`) gotowy skrypt `scripts/run-dev-host.sh` uruchamia ten sam
wariant pod `https://localhost:18446/mcp` (adres, którego szuka `scripts/run-dev-host.sh` gateway'a), z mockiem
Jiry na `http://localhost:18081` i `MCP_JIRA_ALLOW_PLAIN_HTTP=true`:

```bash
./mvnw verify && scripts/run-dev-host.sh
```

### Przebieg M1 z gateway'em i brokerem (procesy hosta)

```bash
(cd ../mcp-credential-broker && MCP_GATEWAY_JWKS_URI=https://localhost:18445/.well-known/jwks.json \
   MCP_GATEWAY_TLS_NAME=localhost scripts/run-dev-host.sh) &
(cd ../mcp-gateway && scripts/run-dev-host.sh) &
scripts/run-dev-host.sh &
(cd ../mcp-platform-dev && make pat USER=alice BROKER_URL=https://localhost:18100)
TOKEN=$(make -s -C ../mcp-platform-dev token USER=alice | tail -1) ../mcp-gateway/scripts/mcp-demo.sh
python3 ../mcp-platform-dev/scripts/mcpdev.py audit <X-Request-Id tools/call> \
   --logs gateway.log --logs broker.log --logs mcp-jira.log         # missing: none
make -C ../mcp-platform-dev jira-auth                                 # PAT alice-pat
```

Wynik z 2026-10-27: STATUS.md, sekcja "Demo M1".

## CI

Cała logika jest w `ci/run.sh` (`make ci` woła `ci/run.sh verify`); `.gitlab-ci.yml`, `Jenkinsfile` i `.github/workflows/ci.yml`
to cienkie nakładki, więc to samo działa lokalnie, w obrazie `maven:3.9.12-eclipse-temurin-25` i w każdym z trzech systemów.

| Krok | Co robi |
| --- | --- |
| `ci/run.sh verify` (domyślny) | `hygiene`, `mvnw clean verify`, potem kontrola, że każdy moduł z `CI_TEST_MODULES` uruchomił testy (pominięte nie liczą się) i że powstał `mcp-app/target/mcp-manifest.json` |
| `ci/run.sh hygiene` | cudzysłowy, spacje i wielokropek spoza ASCII oraz ślady narzędzi w źródłach (perl czyta przez `<<>>`) |
| `ci/run.sh image` | `docker build` jaru z `verify` jako `mcp-server-jira:<prefiks>-<sha7>` (prefiks z `CI_IMAGE_TAG_PREFIX`, domyślnie `m6`); tylko gałąź `main`, wymaga demona Dockera |

Inny argument kończy się kodem 2. Merge request i pull request nie dostają sekretów ani gniazda Dockera: testy nie
potrzebują kontenerów, a rejestr Maven z artefaktami platformy (`MCP_MAVEN_REPOSITORY_URL`, zwykła zmienna) jest wtedy
czytany anonimowo (`.ci/settings-anonymous.xml`). Dane dostępu (`MCP_MAVEN_REPOSITORY_USER`, `_PASSWORD`; w GitLabie
zmienne Protected i Masked, w Jenkinsie poświadczenie `mcp-maven-registry`) widzi tylko gałąź `main`, na której działa też
krok `image` (runner z gniazdem Dockera; w GitLabie tag `docker-socket` musi należeć do runnera chronionego (protected),
inaczej gniazdo trafiłoby w ręce merge requestu; w GitHubie sekrety są w Environment `ci-main`). Moduły z wymaganymi
testami: `CI_TEST_MODULES` (domyślnie `mcp-application mcp-integration mcp-tools mcp-app`), dolna granica dla `mcp-app`: `CI_APP_MIN_TESTS` (20).
Rejestracja manifestu w gateway'u (`PUT` z certyfikatem `mcp-registry-ci-mcp-jira`) jest osobnym krokiem pipeline'u
serwera, bo wymaga sekretu, którego repo nie zawiera.

## Testy

| Klasa | Co sprawdza |
| --- | --- |
| `GetIssueToolTest` | PAT z C3 dociera do Jiry, tylko pola narzędzia w wyniku (bez e-maili, linków, HTML), tytuł i opis jako `UntrustedText`; przeniesione zgłoszenie pod nowym kluczem; brak poświadczenia i 401 -> `credential_required`; 404 = 403; złe klucze i argumenty spoza schematu to C10 `invalid_argument` i nie docierają do Jiry, nazwa nieznanego argumentu nie trafia do wyniku ani logu; nieczytelna, pusta i za duża odpowiedź, zgłoszenie bez klucza i przekierowanie -> `internal_error` bez treści w logu; timeout -> `upstream_unavailable`; PAT i treść błędu Jiry nie trafiają do wyniku ani logu |
| `SearchIssuesToolTest` | stronicowanie kursorem (3 strony, `startAt` 0/25/50), dopełnianie strony po odfiltrowaniu ukrytego trafienia, kursor związany z zapytaniem, użytkownikiem i narzędziem, `order_by`/`order` z zamkniętej listy; JQL zbudowany przez serwer (dokładny tekst w układzie 5.1.0: warunki, zakres, `ORDER BY`; bez `project` i `issuetype`), wyniki spoza projektu i innego typu odrzucone, 100 wierszy z Jiry; niewidoczny projekt (404 = 403) bez wyszukiwania; `credential_required` (brak i 401); próby wstrzyknięcia JQL w filtrach i `text`, brak argumentu `jql`; 400 Jiry -> `invalid_argument` bez treści z Jiry |
| `AddCommentToolTest` | komentarz z PAT użytkownika w `{noformat}`, `UPDATE` w hintach; obrazy, linki i wzmianki w treści jako literalny tekst, `{noformat}` w treści nie zamyka bloku; przeniesione zgłoszenie zapisane pod nowym kluczem i tak zgłoszone; `credential_required` (brak i 401); 404 = 403; zły klucz, pusty, za długi i ze znakiem sterującym `body`; przekierowanie i odpowiedź bez `id` -> `internal_error` |
| `TransitionIssueToolTest` | przejście po nazwie i po id; niedostępne przejście bez `POST`; nazwa wspólna dwóm przejściom odrzucona z listą id i statusów docelowych, id z listy działa; nazwa dopasowana w całości (`Do` nie łapie `Done`); przeniesione zgłoszenie pod nowym kluczem; przekierowanie i odpowiedź inna niż 204 -> `internal_error`; 400 Jiry -> `invalid_argument`; `credential_required` (brak i 401); 404 = 403; zły klucz i nazwa przejścia |
| `ToolListTest` | dokładnie 19 narzędzi z konfiguracji gateway'a, ryzyko w hintach, kursor w listach bez `startAt`, bez argumentów `jql`/`cql`, każdy obiekt schematu zamknięty (także `jql_filter`), każdy string z `maxLength`, wzorce kluczy z kontraktów |
| `GatewayOnlyAccessTest` | bez JWT 401 (`tools/call`, `ping`, `initialize`), JWT dla innego serwera, innego narzędzia, wariant serwisowy i przeterminowany -> 401; token z claimem `vs` albo `vsc` (usunięte w kontraktach 5.x) -> 401; bez JWT tylko health; system zgodny z `SystemId` |
| `ProductionConfigurationTest` | konfiguracja bazowa: `client-auth: need`; `accepted-credential-types` `user-secret`, bez właściwości `credential-type`; backend `http` poza `dev` odrzucony (chyba że jawnie dopuszczony); `dev` na 127.0.0.1 |
| `DevProfileLoopbackTest` | `dev` z adresem innym niż pętla zwrotna nie wstaje |
| `JiraServiceTest` | przypadki użycia bez Springa: projekt przed wyszukiwaniem, filtr projektu i typu wyników, 100 wierszy z Jiry i 25 w wyniku, wybór przejścia (id przed nazwą, nazwa w całości, kilka pasujących odrzucone; zabija mutację `equals` -> `contains`), zapis pod kluczem kanonicznym, komentarz w `{noformat}` |
| `DeleteIssueToolTest` | `delete_issue`: usunięcie z PAT użytkownika, `DESTRUCTIVE` w hintach, bez grupy destructive `access_denied` bez ruchu do Jiry, zgłoszenie restricted/przeniesione do restricted/403/nieistniejące z identyczną odpowiedzią, 401 przy odczycie i przy samym DELETE -> `credential_required`, podzadania -> `invalid_argument`, 200 zamiast 204 i przekierowanie -> `internal_error`, zgłoszenie przeniesione usuwane pod nowym kluczem |
| `ResourcePolicyTest` | polityka na zasobach: restricted i przeniesione do restricted jak brak (każde narzędzie), wyszukiwanie w projekcie restricted jak w nieistniejącym, trafienia restricted i nieczytelne usunięte z listy, zapis bez zakresu projektu, awaria Jiry to nie odmowa |
| `ReadToolsTest` | nowe odczyty: kształt wyników, `UntrustedText`, brak e-maili, stronicowanie komentarzy kursorem, lista projektów bez `Restricted`, 404 = 403 = `Restricted` dla każdego narzędzia zgłoszenia, `credential_required`, argumenty odrzucone przed Jirą |
| `WriteToolsTest` | nowe zapisy: ciało żądania do Jiry (opisy w `{noformat}`, etykiety, priorytet), zgłoszenie przeniesione pod nowym kluczem, odmowy Jiry jako stałe `invalid_argument`, `access_denied` bez grupy zapisu i bez ruchu do Jiry, komentarz spoza zgłoszenia nie jest edytowany, `credential_required` |
| `VersionFixturesTest` | te same narzędzia na fixture 9.12 i 11.3, sonda: wspierane 9.12 i 11.3, `degraded` dla 8.14, 9.11, 12.0 i Cloud, wersja z `versionNumbers`, odpowiedź bez wersji i strona logowania |
| `DegradedModeTest` | stan `degraded`: każde narzędzie zmieniające dane to `upstream_unavailable` bez zapisu do Jiry, odczyty działają |
| `JiraCapabilityProbeTest` | klasyfikacja wersji (granice 9.12 i 11.x, Cloud, brak wersji) |
| `JiraWriteGateTest` | zapisy blokuje tylko `degraded` |
| `ManifestTest` | `GET /mcp-manifest` przechodzi reguły `mcp-manifest`, definicje i `definitionHash` zgodne z `tools/list`, ryzyko i `resourceRefs` każdego narzędzia, `delete_issue` jako `DESTRUCTIVE`; bez `environment` poza `devstack`, `acceptedTypes` `["user-secret"]` |
| `ManifestArtifactIT` | po `package`: plik `target/mcp-manifest.json` równy manifestowi serwera, wersja z pom (nie `1.0.0` testów), bez `environment` |
| `CredentialTypeTest` | typ spoza `[user-secret]` (`user-oauth`, `token-exchange`, `service-account`), brak nagłówka typu, `User-Secret` i nieznany typ -> `internal_error`, nic nie dociera do Jiry, PAT nie idzie w świat; `user-secret` przechodzi |
| `JiraResolversTest` | resolvery bez Springa: kategoria projektu w opisie, klucz i projekt z odpowiedzi Jiry, kategoria projektu raz na listę, zgłoszenie bez projektu |

Nowe narzędzie dostaje test w tym samym stylu: klasa dziedziczy po `ServerTestSupport`, odpowiedź systemu
dopisuje się w `StubBackend`.

## Dokumentacja

- `docs/jak-napisac-narzedzie.md`: zasady i kolejność kroków przy nowym narzędziu.
- `docs/architecture/overview.md`: architektura i decyzje.
- `docs/security/SECURITY.md`: niezmienniki bezpieczeństwa w tym serwerze.
- `docs/api/tools-catalog.md`: rejestr narzędzi.
- `docs/operations/deployment-vm-systemd.md`, `deploy/`, `Dockerfile`: wdrożenie.
