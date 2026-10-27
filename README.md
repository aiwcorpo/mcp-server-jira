# mcp-jira: serwer MCP platformy

Serwer MCP dla systemu `jira`, zbudowany na `mcp-platform-starter` 3.0.0-alpha-4 (Java 25, Spring Boot
4.1.0, Spring AI 2.0.0, kontrakty `mcp-contracts` 2.0.2). Stoi wyłącznie za gateway'em platformy: przyjmuje
ruch tylko po mTLS z wewnętrznym JWT gateway'a (C2), a do systemu docelowego woła z poświadczeniem
użytkownika, które gateway dołącza do każdego żądania (C3). Bez poprawnego JWT odpowiada 401, nie ma trybu
anonimowego.

Logowania użytkowników, decyzji o dostępie do narzędzi i potwierdzeń akcji destrukcyjnych serwer nie robi:
to zadania gateway'a. Serwer odpowiada za narzędzia, adapter do systemu, poprawne błędy (C10) i audyt (C8),
który dokłada starter.

## Szybki start

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./mvnw verify                 # build i 48 testów (TestGateway + StubBackend Jiry); jar: mcp-app/target/mcp-jira.jar
```

Testy nie potrzebują Dockera ani środowiska `mcp-platform-dev`: `TestGateway` z `mcp-platform-test` wystawia
JWKS z kluczy testowych kontraktów i podpisuje wewnętrzne JWT, a `StubBackend` (test) udaje REST API v2 Jiry
ze ścieżkami i odpowiedziami jak mock Jiry z `mcp-platform-dev` (`mocks/jira`).

## Moduły i miejsce każdej rzeczy

| Moduł | Co w nim jest | W tym serwerze |
| --- | --- | --- |
| `mcp-core` | domena i porty, czysta Java | `JiraIssue`, `JiraProject` (C7 `Jira::Project{key, category}`), `IssueSearch`, wyjątki domeny, porty `JiraUseCase` i `JiraResourcePort` |
| `mcp-application` | przypadki użycia (bez Springa) | `JiraService` |
| `mcp-integration` | adaptery wyjściowe do systemu | `JiraResourceAdapter` (REST API v2, PAT z C3, timeouty), `JqlBuilder` (jedyne miejsce, gdzie powstaje JQL), `JiraBackendProperties` |
| `mcp-tools` | narzędzia MCP, powierzchnia publiczna | `JiraTool` (cztery narzędzia i ich schematy wejścia), `JiraToolViews` (kształt wyników), `JiraToolErrors` (wyjątki domeny -> kody C10) |
| `mcp-app` | bootstrap, wiring, jedyny jar, testy end-to-end | `McpServerApplication`, `McpToolsConfiguration` (lista narzędzi), `ApplicationWiringConfiguration` |

Zależności idą do środka: `mcp-tools` -> `mcp-application` -> `mcp-core` <- `mcp-integration`; `mcp-app` składa
całość. Resolver zasobów (`@ResourceRef`, starter beta) dojdzie w `mcp-integration` obok adaptera: atrybuty
`Jira::Project` daje już `JiraResourcePort.findProject` (kategoria projektu z Jiry, `""` bez kategorii).

## Narzędzia

| Narzędzie (klient: `jira__<nazwa>`) | Ryzyko | Uprawnienie (C9) | Poświadczenie | Argumenty | Wynik |
| --- | --- | --- | --- | --- | --- |
| `search_issues` | `READ_ONLY` | `mcp-jira-read` | `user-secret` (PAT, `Bearer`); gateway przyjmuje też `user-oauth` | `project` (`JiraProjectKey`), opcjonalnie `jql_filter{status, assignee, type}` i `text` (do 200 znaków) | `{project, total, returned, issues[{key, type, status, assignee, updated, untrustedText{summary}}]}`, najwyżej 25, najnowsze zmiany najpierw |
| `get_issue` | `READ_ONLY` | `mcp-jira-read` | jak wyżej | `issueKey` (`JiraIssueKey`) | `{key, project, type, status, assignee, updated, untrustedText{summary, description, descriptionTruncated}}`, opis do 10000 znaków |
| `add_comment` | `UPDATE` | `mcp-jira-write` | jak wyżej | `issueKey`, `body` (1-10000 znaków, bez znaków sterujących poza `\n`, `\r`, `\t`) | `{issueKey, commentId, created}` |
| `transition_issue` | `UPDATE` | `mcp-jira-write` | jak wyżej | `issueKey`, `transition` (id albo nazwa przejścia, do 60 znaków) | `{issueKey, transition, status}` |

Błędy (C10) wszystkich narzędzi: `not_found_or_denied` (404 i 403 z Jiry, ten sam tekst), `credential_required`
(brak poświadczenia albo 401 z Jiry), `invalid_argument` (zły identyfikator, wartość filtra nieznana Jirze,
przejście niedostępne albo odrzucone; tekst stały, bez treści z Jiry), `upstream_unavailable`, `internal_error`.

Zasady wyników i zapytań:

- JQL pisze tylko serwer (`JqlBuilder`): `project = "<klucz>"` i dla każdego podanego pola jedna klauzula
  `AND` z wartością w cudzysłowie i z escapowaniem `\` i `"`. Wartości są wcześniej sprawdzane wzorcem (nazwa:
  litery, cyfry, spacja, `_./()&'-`; użytkownik: `[A-Za-z0-9][A-Za-z0-9._@+-]{0,99}`), a w `text` znaki składni
  wyszukiwania pełnotekstowego Jiry są zamieniane na spacje. Surowego JQL nie ma w żadnym argumencie.
- `search_issues` najpierw pobiera projekt (`GET /rest/api/2/project/{key}`): niewidoczny projekt to
  `not_found_or_denied`, zanim powstanie zapytanie. Wyniki spoza projektu są odrzucane.
- Tekst pisany przez użytkowników Jiry (tytuł, opis) jest tylko pod `untrustedText`; reszta pól to wartości
  ze słowników Jiry. Serwer nie pobiera `renderedFields` (HTML), komentarzy, linków ani pól własnych.
- `get_issue` oddaje klucz i projekt z odpowiedzi Jiry, nie z argumentu: zgłoszenie przeniesione (`ABC-3`
  w mocku) wraca jako `DEF-5`.
- Schemat wejścia każdego narzędzia jest pisany ręcznie (`JiraTool.inputSchemas()`): każdy obiekt ma
  `additionalProperties: false`, każdy string `maxLength`. Wzorce identyfikatorów są sprawdzane w kodzie,
  żeby błąd był `invalid_argument` C10; naruszenie samego schematu (długość, nieznane pole) odrzuca MCP SDK
  przed narzędziem własnym tekstem z `isError: true` (znane ograniczenie startera, do bety).

## Jak przechodzi wywołanie

```
gateway --mTLS (CN mcp-gateway) + JWT C2 (aud mcp-jira, tool get_issue) + X-MCP-Credential: Bearer <PAT>-->
  starter: InternalJwtFilter (inaczej 401) -> CredentialHeaderFilter -> AuditingToolCallback (C8)
  -> JiraTool.getIssue: walidacja issueKey
  -> JiraService -> JiraResourceAdapter: CredentialContext.require("jira") + credential::applyTo
  -> GET {MCP_JIRA_URL}/rest/api/2/issue/{key}?fields=summary,description,status,issuetype,assignee,project,updated
```

Ścieżki API Jiry: `GET /rest/api/2/project/{key}`, `GET /rest/api/2/issue/{key}`, `POST /rest/api/2/search`,
`POST /rest/api/2/issue/{key}/comment`, `GET` i `POST /rest/api/2/issue/{key}/transitions`. Adapter nie podąża
za przekierowaniami (nagłówek z poświadczeniem nie może pójść pod inny adres), czyta tylko pola domeny i nie
wkłada treści odpowiedzi Jiry do żadnego komunikatu.

## Konfiguracja

Konfiguracja bazowa (`mcp-app/src/main/resources/application.yml`) jest produkcyjna: mTLS, JWKS gateway'a po
TLS z CA wewnętrznym, `jti` w Redisie. Profil `dev` wyłącza tylko TLS i służy testom; pozostałe ułatwienia
(klucze testowe, `jti` w pamięci, `mtls.required=false`) daje `TestGateway`, a starter odrzuca je poza `dev`.

| Zmienna | Znaczenie | Domyślnie |
| --- | --- | --- |
| `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD` | PKCS12: certyfikat serwera (SAN `mcp-jira.internal`) i CA wewnętrzne; truststore służy też do JWKS | wymagane |
| `MCP_GATEWAY_JWKS_URI` | JWKS wewnętrznego JWT, np. `https://mcp-gateway.internal:8443/.well-known/jwks.json` | wymagane |
| `MCP_GATEWAY_ISSUER` | `iss` C2 | `https://mcp-gateway.internal` |
| `MCP_CONNECT_URL` | strona portalu do połączenia konta, z `{system}`; trafia do `credential_required` | wymagane |
| `MCP_JIRA_URL` | adres bazowy Jiry (bez `/rest/api/2`) | wymagane |
| `MCP_JIRA_SSL_BUNDLE` | bundle SSL dla HTTPS systemu, np. `mcp-internal-ca`; puste = zaufanie JDK | puste |
| `MCP_REDIS_HOST`, `MCP_REDIS_PORT`, `MCP_REDIS_USER`, `MCP_REDIS_PASSWORD` | Redis dla jednorazowego `jti` (użytkownik ACL `mcp-jira`, klucze `jti:mcp-jira:*`) | `localhost`, `6379`, `mcp-jira`, puste |
| `MCP_PORT`, `MCP_BIND` | port i adres nasłuchu | `8443`, `0.0.0.0` |

Endpoint MCP to `/mcp` (domyślne ustawienie startera, tak jak oczekuje gateway: `internal-url:
https://mcp-jira.internal:8443/mcp`). Bez JWT odpowiada tylko `/actuator/health` (i `liveness`,
`readiness`), ale TLS i tak wymaga certyfikatu klienta z CA wewnętrznego.

Serwer nie wstaje bez `MCP_GATEWAY_JWKS_URI`, `MCP_CONNECT_URL`, `MCP_JIRA_URL` i plików TLS.

Na hoście ze środowiskiem `mcp-platform-dev` (`make up`): `scripts/run-dev-host.sh` uruchamia serwer pod
`https://localhost:18446/mcp`, czyli tam, gdzie szuka go `scripts/run-dev-host.sh` gateway'a, z certyfikatem
`certs/mcp-jira`, JWKS gateway'a z `https://localhost:18445`, Redisem (użytkownik ACL `mcp-jira`, hasło
`REDIS_MCP_JIRA_PASSWORD` z `.secrets/dev.env`) i mockiem Jiry na `http://localhost:18081`:

```bash
./mvnw verify && scripts/run-dev-host.sh
dev=../mcp-platform-dev
curl --cacert $dev/certs/ca/ca.crt --cert $dev/certs/mcp-gateway/tls.crt --key $dev/certs/mcp-gateway/tls.key \
     --resolve mcp-jira.internal:18446:127.0.0.1 https://mcp-jira.internal:18446/actuator/health   # 200
```

`POST /mcp` bez JWT daje 401, połączenie bez certyfikatu klienta jest zrywane w handshake'u.

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

## Testy

| Klasa | Co sprawdza |
| --- | --- |
| `GetIssueToolTest` | PAT z C3 dociera do Jiry, tylko pola narzędzia w wyniku (bez e-maili, linków, HTML); przeniesione zgłoszenie pod nowym kluczem; brak poświadczenia i 401 -> `credential_required`; 404 = 403; złe klucze i argumenty spoza schematu nie docierają do Jiry; PAT i treść błędu Jiry nie trafiają do wyniku ani logu |
| `SearchIssuesToolTest` | JQL zbudowany przez serwer (dokładny tekst), wyniki spoza projektu odrzucone; niewidoczny projekt (404 = 403) bez wyszukiwania; `credential_required` (brak i 401); próby wstrzyknięcia JQL w filtrach i `text`, brak argumentu `jql`; 400 Jiry -> `invalid_argument` bez treści z Jiry |
| `AddCommentToolTest` | komentarz z PAT użytkownika, `UPDATE` w hintach; `credential_required` (brak i 401); 404 = 403; zły klucz, pusty i ze znakiem sterującym `body` |
| `TransitionIssueToolTest` | przejście po nazwie i po id; niedostępne przejście bez `POST`; 400 Jiry -> `invalid_argument`; `credential_required` (brak i 401); 404 = 403; zły klucz i nazwa przejścia |
| `ToolListTest` | dokładnie cztery narzędzia z konfiguracji gateway'a, ryzyko w hintach, każdy obiekt schematu zamknięty, każdy string z `maxLength` |
| `GatewayOnlyAccessTest` | bez JWT 401 (`tools/call`, `ping`, `initialize`), JWT dla innego serwera, innego narzędzia, wariant serwisowy i przeterminowany -> 401; bez JWT tylko health |
| `JiraServiceTest` | przypadki użycia bez Springa (projekt przed wyszukiwaniem, filtr projektu, wybór przejścia) |
| `JqlBuilderTest` | klauzule JQL, escapowanie cudzysłowu i backslasha, odrzucenie znaków sterujących |

Nowe narzędzie dostaje test w tym samym stylu: klasa dziedziczy po `ServerTestSupport`, odpowiedź systemu
dopisuje się w `StubBackend`.

## Dokumentacja

- `docs/jak-napisac-narzedzie.md`: zasady i kolejność kroków przy nowym narzędziu.
- `docs/architecture/overview.md`: architektura i decyzje.
- `docs/security/SECURITY.md`: niezmienniki bezpieczeństwa w tym serwerze.
- `docs/api/tools-catalog.md`: rejestr narzędzi.
- `docs/operations/deployment-vm-systemd.md`, `deploy/`, `Dockerfile`: wdrożenie.
