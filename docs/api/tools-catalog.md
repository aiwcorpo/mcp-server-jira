# Katalog narzędzi

Narzędzia to powierzchnia publiczna serwera. Każda zmiana nazwy, opisu, schematu albo ryzyka zmienia
`definitionHash` (C6) i wymaga ponownego zatwierdzenia w gateway'u, więc przechodzi przez code review razem z
tą tabelą.

| Narzędzie (klient widzi `jira__<nazwa>`) | Ryzyko | Akcja (C6) | Argumenty | Uprawnienie (C9) | Poświadczenie |
| --- | --- | --- | --- | --- | --- |
| `search_issues` | `READ_ONLY` | `read` | `project` (`JiraProjectKey`), `jql_filter{status, assignee, type}` (opcjonalne, zamknięty obiekt, wartości do 60/100 znaków), `text` (opcjonalne, do 200 znaków) | `mcp-jira-read` | `user-secret` z C3, `Authorization: Bearer` |
| `get_issue` | `READ_ONLY` | `read` | `issueKey` (`JiraIssueKey`) | `mcp-jira-read` | jak wyżej |
| `add_comment` | `UPDATE` | `write` | `issueKey`, `body` (1-10000 znaków; tekst zwykły, zapisywany w `{noformat}`) | `mcp-jira-write` | jak wyżej |
| `transition_issue` | `UPDATE` | `write` | `issueKey`, `transition` (id `^[1-9][0-9]{0,9}$` albo nazwa do 60 znaków; nazwa wspólna kilku przejść daje `invalid_argument` z listą id) | `mcp-jira-write` | jak wyżej |

Konwencje: nazwy `snake_case`, czasownik i obiekt, bez prefiksu systemu (dokłada go gateway); opis mówi, co
narzędzie zwraca, bez instrukcji dla modelu.

Wzorce kluczy są wzorcami `JiraProjectKey` i `JiraIssueKey` z `mcp-contracts` 2.0.2 (`common.schema.json`).
Zasoby C7 (od bety startera przez `@ResourceRef`): `project` -> `Jira::Project{key, category}`, `issueKey` ->
`Jira::Issue` (projekt z odpowiedzi Jiry). Schematy wejścia powstają z `@ArgumentLimits` na argumentach
`JiraTool` (`SchemaLimitedToolCallbacks`), a zagnieżdżony `jql_filter` domyka `McpToolsConfiguration`.

Wyniki: `get_issue` i `search_issues` oddają tytuł i opis jako `UntrustedText`
(`{kind: "untrusted-data", format: "text/plain", text, truncated}`, CC-072); `add_comment` i `transition_issue`
oddają `issueKey` z odpowiedzi Jiry (klucz kanoniczny zgłoszenia), nie z argumentu.
