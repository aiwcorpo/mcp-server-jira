# Jak napisać narzędzie

Narzędzie to metoda `@Tool` wołana przez model przez gateway. Każdy argument jest niezaufany, a wynik trafia
do modelu, który może go potraktować jak instrukcję. Wzorcem jest `get_issue`, dla wyszukiwania `search_issues`.

## Kroki, od środka

1. `mcp-core`: rekord domeny tylko z polami, które narzędzie ma oddać; wyjątek domeny dla "nie ma albo
   niewidoczne"; metoda portu wejściowego (`JiraUseCase`) i wyjściowego (`JiraResourcePort`).
2. `mcp-application`: implementacja przypadku użycia w `JiraService` (bez Springa, test JUnit obok).
3. `mcp-integration`: wywołanie systemu w adapterze. Poświadczenie zawsze z
   `CredentialContext.require(SYSTEM)` i `credential::applyTo`. 404 i 403 systemu -> pusty wynik (jeden kod
   `not_found_or_denied`), 401 -> `CredentialRequiredException(SYSTEM)`, 3xx i odpowiedź, której nie da się
   użyć (pusta, nie parsuje się, brak klucza, za duża) -> `BackendResponseException` ze stałym tekstem i bez
   przyczyny (komunikat parsera cytuje treść odpowiedzi). Odpowiedź do prywatnego DTO z
   `@JsonIgnoreProperties(ignoreUnknown = true)`, potem do domeny; brak wymaganych pól to błąd systemu, nie
   `IllegalArgumentException` (to byłby `invalid_argument`, czyli wina modelu). Wszystko to robi `asUser`; nowe
   wywołanie idzie przez niego.
4. `mcp-tools`: cienka metoda `@Tool` z `@ToolRisk` i wywołaniem przypadku użycia; mapowanie wyjątku domeny
   na kod C10 w `JiraToolErrors`. Każdy argument tekstowy ma `@ArgumentLimits(maxLength = ...)`; klucze
   Jiry `contractPattern = "JiraIssueKey"` / `"JiraProjectKey"` (wzorzec z `common.schema.json`, bez kopii w
   kodzie). Limity trafiają do `inputSchema`, a starter sprawdza je przy każdym wywołaniu przed metodą
   (naruszenie to C10 `invalid_argument`, zdarzenie C8). W kodzie zostaje to, czego schemat nie wyraża: nazwy
   statusu i przejścia, nazwa użytkownika, tekst wyszukiwania.
5. `mcp-app`: narzędzia rejestruje `McpToolsConfiguration` przez `SchemaLimitedToolCallbacks.of(...)` (jawna
   lista nazw `TOOLS`; sam `@Tool` nie wystarczy). Zagnieżdżony obiekt `jql_filter` to rekord `JqlFilter`
   z `@ArgumentLimits` i `@Nullable` na każdym komponencie; starter sam go domyka i egzekwuje (od alpha-7 start
   zatrzymuje się, gdy jakikolwiek poziom nie ma limitu).
6. Test w `mcp-app/src/test` przez `ServerTestSupport`, odpowiedź systemu w `StubBackend`. Narzędzie
   zapisujące ma testy: przeniesione zgłoszenie (`ABC-3`), 3xx i odpowiedź inna niż 204 jako błąd, tekst
   od modelu jako dane.
7. `docs/api/tools-catalog.md` i tabela narzędzi w README.

## Zasady

- Zasób zawsze jawnym argumentem. Narzędzie dostaje identyfikator projektu, rekordu, przestrzeni,
  indeksu i działa tylko na nim. Nie ma narzędzi "moje ostatnie", "wszystko z ..." ani domyślnego zasobu z
  konfiguracji: gateway i polityka (C7, `refs`) widzą tylko to, co jest w argumentach.
- Bez globalnego wyszukiwania. Wyszukiwanie zawsze w zakresie wskazanym argumentem (projekt, przestrzeń,
  grupa, prefiks indeksu), zawężonym dalej przez `QueryScope` startera (beta).
- Bez dowolnego języka zapytań. Nie przekazuj JQL, CQL, query DSL ani ścieżek API od modelu. Jeśli
  narzędzie potrzebuje filtrów, to są osobne, ograniczone pola (status, typ, przypisany) parsowane i
  składane przez serwer z escapowaniem.
- Walidacja przed systemem. Wzorzec i długość identyfikatora, limity wielkości (`size <= 100`),
  zamknięte listy wartości. Schemat egzekwuje starter; to, czego schemat nie wyraża, narzędzie sprawdza samo i
  rzuca `InvalidToolArgumentException` z własnym tekstem (starter oddaje go jako `invalid_argument`); nigdy z
  treścią odpowiedzi systemu.
- Narzędzie zapisujące pisze pod kluczem, który zwraca system, nie pod argumentem (zasób mógł zostać
  przeniesiony do innego projektu), i podaje ten klucz w wyniku. Wybór między kilkoma pasującymi obiektami
  (przejścia o tej samej nazwie) nie jest zgadywaniem serwera: błąd z listą identyfikatorów.
- Tekst od modelu trafia do systemu jako dane. Jira interpretuje wiki markup, więc `add_comment` zamyka
  treść w `{noformat}`: obrazy z zewnętrznym URL, linki i wzmianki `[~user]` nie robią wtedy żądań ani
  powiadomień. Ten sam problem mają inne narzędzia zapisujące (Markdown, quick actions GitLaba).
- Błędy tylko przez kody C10. Wyjątek domeny -> `ToolErrorMapping` -> `SafeError` z kodem z
  `ToolErrorCode`. Gotowy `SanitizedToolException` wolno rzucić, gdy narzędzie samo zna kod. Inne wyjątki
  starter zamienia na `internal_error`; komunikat nie zawiera odpowiedzi systemu, nagłówków ani sekretów.
- "Nie istnieje" i "brak dostępu" to ten sam błąd `not_found_or_denied`, z tym samym tekstem.
- Ryzyko zgodne z działaniem. `READ_ONLY`, `UPDATE` (odwracalne), `DESTRUCTIVE` (nieodwracalne). Starter
  nie uruchomi serwera z narzędziem bez flagi ani z nazwą sprzeczną z flagą (`delete_*` jako `READ_ONLY`).
  Nie dodawaj parametru "czy na pewno": potwierdzenie robi gateway (C11).
- Opis deterministyczny. Co narzędzie robi i co zwraca, bez instrukcji dla modelu ("zawsze", "najpierw
  wywołaj"). Zmiana nazwy, opisu, schematu albo ryzyka zmienia `definitionHash` i wymaga ponownego
  zatwierdzenia w gateway'u (C6).
- Wynik minimalny. Tylko pola potrzebne do zadania, bez surowego HTML; tekst z systemu to dane, nie
  polecenia.
- Bez sekretów. Serwer nie ma własnego konta w systemie; nie loguj i nie zwracaj poświadczenia (starter
  usuwa je z komunikatów, ale nie licz na to w kodzie).

## Do dopisania po starterze beta

`@ResourceRef` na każdym argumencie z identyfikatorem zasobu i resolver atrybutów (projekt, klasyfikacja),
`QueryScope` w wyszukiwaniu, `ResultFilter` na wynikach listujących, manifest C6. Wzorce przyjdą z M2
template'u (`search_records`, `update_record`, `purge_records`).
