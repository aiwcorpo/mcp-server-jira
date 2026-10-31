# mcp-jira: serwer MCP platformy

Serwer MCP dla systemu `jira`, zbudowany na `mcp-platform-starter` 3.0.0-beta-10 (Java 25, Spring Boot
4.1.0, Spring AI 2.0.0, kontrakty `mcp-contracts` 5.1.0 przez BOM). Stoi wyłącznie za gateway'em platformy: przyjmuje
ruch tylko po mTLS z wewnętrznym JWT gateway'a (C2), a do systemu docelowego woła z poświadczeniem
użytkownika, które gateway dołącza do każdego żądania (C3). Bez poprawnego JWT odpowiada 401, nie ma trybu
anonimowego.

Logowania użytkowników, decyzji o dostępie do narzędzi i potwierdzeń akcji destrukcyjnych serwer nie robi:
to zadania gateway'a. Serwer odpowiada za narzędzia, adapter do systemu, poprawne błędy (C10) i audyt (C8),
który dokłada starter.

## Szybki start

```bash
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
./mvnw verify                 # build i 149 testów (TestGateway + StubBackend Jiry); jar: mcp-app/target/mcp-jira.jar
```

Testy nie potrzebują Dockera ani środowiska `mcp-platform-dev`: `TestGateway` z `mcp-platform-test` wystawia
JWKS z kluczy testowych kontraktów i podpisuje wewnętrzne JWT, a `StubBackend` (test) udaje REST API v2 Jiry
ze ścieżkami i odpowiedziami jak mock Jiry z `mcp-platform-dev` (`mocks/jira`).

## Moduły i miejsce każdej rzeczy

| Moduł | Co w nim jest | W tym serwerze |
| --- | --- | --- |
| `mcp-core` | domena i porty, czysta Java | `JiraIssue`, `JiraProject` (C7 `Jira::Project{key, category}`), `JiraResourceModel` (typy zasobów i wzorce kluczy), `IssueSearch`, wyjątki domeny, porty `JiraUseCase` i `JiraResourcePort` |
| `mcp-application` | przypadki użycia (bez Springa) | `JiraService` (wybór przejścia, filtr projektu wyników), `CommentText` (komentarz jako literalny tekst) |
| `mcp-integration` | adaptery wyjściowe do systemu | `JiraResourceAdapter` (REST API v2, PAT z C3, timeouty, bez przekierowań, `https` poza `dev`), `BoundedBodyInterceptor` (limit rozmiaru odpowiedzi), `BackendResponseException` (odpowiedź bez treści w komunikacie), `JiraIssueResolver` i `JiraProjectResolver` (zasoby dla polityki: id, projekt i kategoria z odpowiedzi Jiry), `JiraResourceDescription` |
| `mcp-tools` | narzędzia MCP, powierzchnia publiczna | `JiraTool` (pięć narzędzi, `@ResourceRef`, zapytanie przez `StructuredQuery`, limity argumentów przez `@ArgumentLimits`), `JiraToolViews` (kształt wyników), `JiraToolErrors` (wyjątki domeny -> kody C10) |
| `mcp-app` | bootstrap, wiring, jedyny jar, testy end-to-end | `McpServerApplication`, `McpToolsConfiguration` (lista narzędzi, `SchemaLimitedToolCallbacks`), `ApplicationWiringConfiguration` |

Zależności idą do środka: `mcp-tools` -> `mcp-application` -> `mcp-core` <- `mcp-integration`; `mcp-app` składa
całość. Resolvery zasobów (`@ResourceRef`) leżą w `mcp-integration` obok adaptera: atrybuty polityki pochodzą
z Jiry (`Jira::Project.category`, `""` bez kategorii).

## Narzędzia

| Narzędzie (klient: `jira__<nazwa>`) | Tytuł | Ryzyko | Akcja Cedar | Uprawnienie (C9) | Poświadczenie | Argumenty | Wynik |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `search_issues` | Szukaj zgłoszeń | `READ_ONLY` | `read` | `mcp-jira-read` | `user-secret` (PAT, `Bearer`), jedyny przyjmowany typ | `project` (`JiraProjectKey`), opcjonalnie `jql_filter{status, assignee, type}` i `text` (do 200 znaków) | `{project, issues[{key, type, status, assignee, updated, summary}]}`, najwyżej 25 z pierwszych 100 wierszy Jiry, najnowsze zmiany najpierw, bez `total` (liczba z Jiry liczyłaby zgłoszenia usunięte potem przez filtr wyników); `summary` to `UntrustedText` |
| `get_issue` | Pobierz zgłoszenie | `READ_ONLY` | `read` | `mcp-jira-read` | jak wyżej | `issueKey` (`JiraIssueKey`) | `{key, project, type, status, assignee, updated, summary, description}`; oba teksty to `UntrustedText`; opis to wiki markup z Jiry zamieniony na czysty tekst (bez składni formatowania, obrazów i makr, linki jako tekst), do 10000 znaków; `assignee` i `description` bywają `null` |
| `add_comment` | Dodaj komentarz | `UPDATE` | `update` | `mcp-jira-write` | jak wyżej | `issueKey`, `body` (1-10000 znaków, bez znaków sterujących poza `\n`, `\r`, `\t`) | `{issueKey, commentId, created}`; `issueKey` to klucz kanoniczny z Jiry |
| `transition_issue` | Zmień status zgłoszenia | `UPDATE` | `update` | `mcp-jira-write` | jak wyżej | `issueKey`, `transition` (id albo nazwa przejścia, do 60 znaków) | `{issueKey, transition, status}`; `issueKey` kanoniczny |
| `delete_issue` | Usuń zgłoszenie | `DESTRUCTIVE` | `delete` | `mcp-jira-destructive` | jak wyżej | `issueKey` | `{issueKey, deleted}`; `issueKey` kanoniczny; `DELETE /rest/api/2/issue/{key}`, zgłoszenie z podzadaniami odrzuca Jira (`invalid_argument`), podzadań serwer nie usuwa; kod TOTP weryfikuje gateway |

Błędy (C10) wszystkich narzędzi: `not_found_or_denied` (404 i 403 z Jiry, ten sam tekst), `credential_required`
(brak poświadczenia albo 401 z Jiry), `invalid_argument` (zły identyfikator lub argument naruszający schemat,
wartość filtra nieznana Jirze, przejście niedostępne, niejednoznaczne albo odrzucone; tekst stały, bez treści z
Jiry), `upstream_unavailable`, `internal_error` (też przekierowanie, pusta, nieczytelna albo za duża odpowiedź
Jiry).

Zasady wyników i zapytań:

- JQL pisze tylko serwer, ze `StructuredQuery` startera (`policy.queryScope("Jira::Project").jql(...)`), według
  układu z kontraktów 5.1.0 (`query-rendering`, CC-160): `(warunki) AND project in (...) ORDER BY updated DESC`.
  Warunki to najwyżej `status = "..."`, `assignee = "..."` i `text ~ "..."` (pola i operatory z list normatywnych;
  wartość w cudzysłowie, z escapowaniem, znak sterujący odrzucony), łączone `AND` w jednym nawiasie. Zakres
  `project in ("<klucz>")` to projekt z argumentu (`QueryScope.narrowedTo`), dopisany po warunkach. `issuetype` nie jest polem warunków w kontrakcie, więc `jql_filter.type` serwer stosuje do
  wierszy po odpowiedzi Jiry (typ bez rozróżniania wielkości liter); dlatego prosi Jirę o 100 wierszy, a oddaje
  najwyżej 25. Klucz projektu z odpowiedzi jest dodatkowo porównywany z argumentem. Jedyne `ORDER BY` to
  `updated DESC`. Wartości są wcześniej sprawdzane wzorcem (nazwa: litery, cyfry, spacja, `_./()&'-`;
  użytkownik: `[A-Za-z0-9][A-Za-z0-9._@+-]{0,99}`), a w `text` znaki składni wyszukiwania pełnotekstowego Jiry są
  zamieniane na spacje. Surowego JQL nie ma w żadnym argumencie.
- `project` w `search_issues` i `issueKey` w pozostałych narzędziach to `@ResourceRef` (`Jira::Project`,
  `Jira::Issue`). Przed metodą starter woła resolver z poświadczeniem użytkownika i ocenia politykę Cedar na
  zasobie takim, jakim opisuje go Jira: zgłoszenie przeniesione do projektu z kategorią `Restricted`, nieistniejące
  i niewidoczne dają to samo `not_found_or_denied`; brak grupy narzędzia to `access_denied`. Resolver zgłoszenia
  pobiera zgłoszenie i kategorię jego projektu (`GET project/{key}`); zgłoszenie bez projektu w odpowiedzi to błąd
  odpowiedzi, nie zgłoszenie bez klasyfikacji.
- `ResultFilter`: `key` każdego trafienia `search_issues` jest `@ResourceRef`, więc starter rozwiązuje trafienia
  (jedno `fetchAll`, kategoria każdego projektu raz na listę) i usuwa to, czego polityka nie dopuszcza albo czego
  nie da się odczytać. Dlatego wynik nie ma `total` ani `returned`.
- Manifest C6: `GET /mcp-manifest` (token serwisowy gateway'a) i konfiguracja `policy.*`, `manifest.*`
  (zmienne `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR`,
  `MCP_INTERNAL_URL`, `MCP_ACCEPTED_CREDENTIAL_TYPES`). `dev` czyta paczkę z `http://127.0.0.1:18747` kluczem
  testowym z `mcp-policies/keys/dev`; poza `dev` wymagane są https, `state-dir` i brak kluczy testowych.
- Tytuł i opis zgłoszenia to `UntrustedText` (CC-072: `{kind: "untrusted-data", format: "text/plain", text,
  truncated}`); nazwy typu, statusu i przejścia (ustawiane przez administratorów projektu) są ucinane do 60
  znaków i bez znaków sterujących. Serwer nie pobiera `renderedFields` (HTML), komentarzy, linków ani pól własnych.
- `get_issue` oddaje klucz i projekt z odpowiedzi Jiry, nie z argumentu: zgłoszenie przeniesione (`ABC-3`
  w mocku) wraca jako `DEF-5`. Narzędzia zapisujące (`add_comment`, `transition_issue`, `delete_issue`) działają na
  kluczu z odpowiedzi resolvera i go zwracają; osobnego odczytu klucza kanonicznego nie ma. Zgłoszenie niewidoczne
  to `not_found_or_denied`.
- `delete_issue`: `DESTRUCTIVE`, akcja Cedar `delete` na zgłoszeniu, uprawnienie `mcp-jira-destructive` (plus zakres
  projektu z paczki). Serwer nie pyta o potwierdzenie ani nie ma parametru "czy na pewno": kod TOTP weryfikuje gateway (C11).
  Jira potwierdza usunięcie kodem 204 (inna odpowiedź 2xx i 3xx to `internal_error`); 404 i 403 to
  `not_found_or_denied` (ten sam tekst co odmowa polityki), 401 to `credential_required`. Zgłoszenie przeniesione
  (klucz z Jiry inny niż z argumentu) nie jest usuwane: `invalid_argument` z nowym kluczem, bo potwierdzenie dotyczyło
  starego. `internal_error` po `delete_issue` (np. 200 zamiast 204 z proxy, log `delete_unconfirmed`) nie znaczy "nie usunięto".
- `transition_issue`: liczbowe `transition` pasujące do id przejścia wygrywa; nazwa musi pasować w całości
  (bez rozróżniania wielkości liter) i do jednego przejścia. Jira nie wymusza unikalnych nazw przejść
  wychodzących z jednego statusu, więc kilka pasujących to `invalid_argument` z listą par id -> status docelowy do wyboru. Jira
  potwierdza przejście kodem 204; inna odpowiedź 2xx i 3xx to `internal_error`, nie sukces.
- `add_comment`: tekst od modelu jest danymi, nie markupem. Jira interpretuje wiki markup (`!url!` ładuje obraz
  z obcego hosta w przeglądarce każdego, kto otworzy zgłoszenie, `[tekst|url]` to link, `[~user]` powiadamia
  użytkownika), więc serwer zapisuje całą treść w bloku `{noformat}` (literalny tekst), a `{noformat` w
  treści zamienia na `{-noformat`, żeby nie zamknął bloku. Komentarz w Jirze jest przez to preformatowany.
- Schemat wejścia powstaje z `@ArgumentLimits` na argumentach `JiraTool` (`SchemaLimitedToolCallbacks` ze
  startera), a klucze mają wzorce `JiraIssueKey` i `JiraProjectKey` prosto z `mcp-contracts`. Starter egzekwuje
  schemat przy każdym wywołaniu: naruszenie to C10 `invalid_argument` i zdarzenie C8, bez wartości argumentu i
  bez nazw argumentów spoza schematu.
- Adapter: poza `dev` tylko `https` (`MCP_JIRA_ALLOW_PLAIN_HTTP=true` dla mocków `mcp-platform-dev`), bez
  przekierowań, odpowiedź najwyżej 1 MB (`max-response-size`), a pusta, nieczytelna albo bez klucza odpowiedź to
  `BackendResponseException` bez treści w komunikacie.

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
  w fazie `package` każdego buildu, także z `-DskipTests` (`exec-maven-plugin` uruchamia `ManifestExport`, które startuje
  aplikację z konfiguracją `application.yml`: wersja z pom, `internalUrl` i `acceptedTypes` z konfiguracji albo ze
  zmiennych `MCP_INTERNAL_URL` i `MCP_ACCEPTED_CREDENTIAL_TYPES`, gdy są ustawione; podstawione są tylko klucze
  gateway'a, paczka polityk i backend Jiry). Build kończy się błędem, gdy plik różni się od `GET /mcp-manifest` tej
  aplikacji albo ma pole `environment`. Po `package` test `ManifestArtifactIT` (failsafe) porównuje plik z manifestem
  serwera w kontekście testowym (bez `version` i `internalUrl`, które test nadpisuje). CI: `ci/run.sh verify`, potem krok
  rejestracji bierze `mcp-app/target/mcp-manifest.json` i wysyła go certyfikatem `mcp-registry-ci-mcp-jira`; odpowiedź
  działającego serwera nie jest źródłem rejestracji. Tylko `search_issues` i `get_issue` (`READ_ONLY`) gateway
  zatwierdza sam; nowe i zmienione `add_comment`, `transition_issue` i `delete_issue` czekają na `approve` administratora.
- `search_issues` zwraca `hasMore`, liczone wyłącznie z wyników widocznych dla wywołującego: `true`, gdy widocznych
  trafień jest więcej niż 25, albo gdy filtr `jql_filter.type` (stosowany po odpowiedzi Jiry, bo `issuetype` nie jest polem
  kontraktu) objął pełne 100 wierszy i starsze zgłoszenia tego typu mogły zostać pominięte. Trafienia ukryte przez
  politykę (np. zgłoszenie przeniesione do projektu `Restricted`) nie wpływają na flagę ani na liczbę wyników: 26 trafień
  z jednym ukrytym jest nieodróżnialne od 25 widocznych. Serwer ocenia trafienia partiami po 26 (wynik resolvera jest
  buforowany na czas wywołania i używany ponownie przez filtr platformy).
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
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
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
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@25/25.0.4.1/libexec/openjdk.jdk/Contents/Home
export MCP_PLATFORM_DEV=<workspace>/mcp-platform-dev           # ścieżka bezwzględna
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
| `ci/run.sh verify` (domyślny) | `hygiene`, `mvnw clean verify`, potem kontrola, że uruchomiono co najmniej jeden test (pominięte nie liczą się) i że powstał `mcp-app/target/mcp-manifest.json` |
| `ci/run.sh hygiene` | cudzysłowy, spacje i wielokropek spoza ASCII oraz ślady narzędzi w źródłach (perl czyta przez `<<>>`) |
| `ci/run.sh image` | `docker build` jaru z `verify`; tylko gałąź `main`, wymaga demona Dockera |

Inny argument kończy się kodem 2. Merge request i pull request nie dostają sekretów ani gniazda Dockera: testy nie
potrzebują kontenerów, a rejestr Maven z artefaktami platformy (`MCP_MAVEN_REPOSITORY_URL`, zwykła zmienna) jest wtedy
czytany anonimowo (`.ci/settings-anonymous.xml`). Dane dostępu (`MCP_MAVEN_REPOSITORY_USER`, `_PASSWORD`; w GitLabie
zmienne Protected i Masked, w Jenkinsie poświadczenie `mcp-maven-registry`) widzi tylko gałąź `main`, na której działa też
krok `image` (runner z gniazdem Dockera). Rejestracja manifestu w gateway'u (`PUT` z certyfikatem
`mcp-registry-ci-mcp-jira`) jest osobnym krokiem pipeline'u, bo wymaga sekretu, którego repo nie zna.

## Testy

| Klasa | Co sprawdza |
| --- | --- |
| `GetIssueToolTest` | PAT z C3 dociera do Jiry, tylko pola narzędzia w wyniku (bez e-maili, linków, HTML), tytuł i opis jako `UntrustedText`; przeniesione zgłoszenie pod nowym kluczem; brak poświadczenia i 401 -> `credential_required`; 404 = 403; złe klucze i argumenty spoza schematu to C10 `invalid_argument` i nie docierają do Jiry, nazwa nieznanego argumentu nie trafia do wyniku ani logu; nieczytelna, pusta i za duża odpowiedź, zgłoszenie bez klucza i przekierowanie -> `internal_error` bez treści w logu; timeout -> `upstream_unavailable`; PAT i treść błędu Jiry nie trafiają do wyniku ani logu |
| `SearchIssuesToolTest` | JQL zbudowany przez serwer (dokładny tekst w układzie 5.1.0: warunki, zakres, `ORDER BY`; bez `project` i `issuetype`), wyniki spoza projektu i innego typu odrzucone, 100 wierszy z Jiry; niewidoczny projekt (404 = 403) bez wyszukiwania; `credential_required` (brak i 401); próby wstrzyknięcia JQL w filtrach i `text`, brak argumentu `jql`; 400 Jiry -> `invalid_argument` bez treści z Jiry |
| `AddCommentToolTest` | komentarz z PAT użytkownika w `{noformat}`, `UPDATE` w hintach; obrazy, linki i wzmianki w treści jako literalny tekst, `{noformat}` w treści nie zamyka bloku; przeniesione zgłoszenie zapisane pod nowym kluczem i tak zgłoszone; `credential_required` (brak i 401); 404 = 403; zły klucz, pusty, za długi i ze znakiem sterującym `body`; przekierowanie i odpowiedź bez `id` -> `internal_error` |
| `TransitionIssueToolTest` | przejście po nazwie i po id; niedostępne przejście bez `POST`; nazwa wspólna dwóm przejściom odrzucona z listą id i statusów docelowych, id z listy działa; nazwa dopasowana w całości (`Do` nie łapie `Done`); przeniesione zgłoszenie pod nowym kluczem; przekierowanie i odpowiedź inna niż 204 -> `internal_error`; 400 Jiry -> `invalid_argument`; `credential_required` (brak i 401); 404 = 403; zły klucz i nazwa przejścia |
| `ToolListTest` | dokładnie pięć narzędzi z konfiguracji gateway'a, ryzyko w hintach, każdy obiekt schematu zamknięty (także `jql_filter`), każdy string z `maxLength`, wzorce kluczy z kontraktów |
| `GatewayOnlyAccessTest` | bez JWT 401 (`tools/call`, `ping`, `initialize`), JWT dla innego serwera, innego narzędzia, wariant serwisowy i przeterminowany -> 401; token z claimem `vs` albo `vsc` (usunięte w kontraktach 5.x) -> 401; bez JWT tylko health; system zgodny z `SystemId` |
| `ProductionConfigurationTest` | konfiguracja bazowa: `client-auth: need`; `accepted-credential-types` `user-secret`, bez właściwości `credential-type`; backend `http` poza `dev` odrzucony (chyba że jawnie dopuszczony); `dev` na 127.0.0.1 |
| `DevProfileLoopbackTest` | `dev` z adresem innym niż pętla zwrotna nie wstaje |
| `JiraServiceTest` | przypadki użycia bez Springa: projekt przed wyszukiwaniem, filtr projektu i typu wyników, 100 wierszy z Jiry i 25 w wyniku, wybór przejścia (id przed nazwą, nazwa w całości, kilka pasujących odrzucone; zabija mutację `equals` -> `contains`), zapis pod kluczem kanonicznym, komentarz w `{noformat}` |
| `DeleteIssueToolTest` | `delete_issue`: usunięcie z PAT użytkownika, `DESTRUCTIVE` w hintach, bez grupy destructive `access_denied` bez ruchu do Jiry, zgłoszenie restricted/przeniesione do restricted/403/nieistniejące z identyczną odpowiedzią, 401 przy odczycie i przy samym DELETE -> `credential_required`, podzadania -> `invalid_argument`, 200 zamiast 204 i przekierowanie -> `internal_error`, zgłoszenie przeniesione usuwane pod nowym kluczem |
| `ResourcePolicyTest` | polityka na zasobach: restricted i przeniesione do restricted jak brak (każde narzędzie), wyszukiwanie w projekcie restricted jak w nieistniejącym, trafienia restricted i nieczytelne usunięte z listy, zapis bez zakresu projektu, awaria Jiry to nie odmowa |
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
