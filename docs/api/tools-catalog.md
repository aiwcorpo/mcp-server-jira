# Katalog narzędzi

Narzędzia to powierzchnia publiczna serwera. Każda zmiana nazwy, opisu, schematu albo ryzyka zmienia
`definitionHash` (C6) i wymaga ponownego zatwierdzenia w gateway'u, więc przechodzi przez code review razem z
tą tabelą. Pełna tabela (tytuł, ryzyko, akcja, uprawnienie, zasób) jest w README, sekcja "Narzędzia"; tu są argumenty.

| Narzędzie (klient widzi `jira__<nazwa>`) | Argumenty |
| --- | --- |
| `search_issues` | `project` (`JiraProjectKey`); opcjonalnie `jql_filter{status, assignee, type, summary}` (zamknięty obiekt, wartości do 60/100/60/200 znaków), `text` (do 200), `order_by` (`updated`, `created`, `priority`, `key`), `order` (`asc`, `desc`), `cursor` |
| `get_issue`, `list_transitions`, `list_attachments`, `unassign_issue`, `delete_issue` | `issueKey` (`JiraIssueKey`) |
| `list_comments`, `list_worklogs` | `issueKey`, opcjonalnie `cursor` |
| `get_project` | `project` |
| `list_projects` | opcjonalnie `cursor` |
| `find_assignable_users` | `project`, `query` (1-50 znaków: litery, cyfry, spacja, `. _ @ + -`) |
| `add_comment` | `issueKey`, `body` (1-10000 znaków) |
| `edit_comment` | `issueKey`, `commentId` (`^[0-9]{1,18}$`), `body` |
| `transition_issue` | `issueKey`, `transition` (id `^[1-9][0-9]{0,9}$` albo nazwa do 60 znaków) |
| `create_issue` | `project`, `issueType`, `summary` (1-255, jedna linia); opcjonalnie `description` (do 10000), `labels` (do 10, `^[A-Za-z0-9_.:-]{1,50}$`), `priority`, `assignee` |
| `update_issue` | `issueKey`; opcjonalnie `summary`, `description`, `labels`, `priority` (co najmniej jedno) |
| `assign_issue` | `issueKey`, `assignee` (login) |
| `link_issues` | `outwardIssueKey`, `inwardIssueKey`, `linkType` (nazwa do 60 znaków); w terminach Jiry zgłoszenie "outward" działa na "inward" (dla `Blocks`: outward blokuje inward) |
| `add_worklog` | `issueKey`, `timeSpent` (`^[0-9]{1,3}[wdhm]( [0-9]{1,3}[wdhm]){0,3}$`); opcjonalnie `comment` |

Konwencje: nazwy `snake_case`, czasownik i obiekt, bez prefiksu systemu (dokłada go gateway); opis mówi, co
narzędzie zwraca, bez instrukcji dla modelu. Żadne narzędzie nie przyjmuje JQL, CQL ani innego tekstu zapytania.

Wzorce kluczy są wzorcami `JiraProjectKey` i `JiraIssueKey` z `mcp-contracts` (`common.schema.json`).
Zasoby C7 (przez `@ResourceRef`, resolvery `JiraProjectResolver` i `JiraIssueResolver`): `project` -> `Jira::Project{key, category}`,
`issueKey` -> `Jira::Issue` (projekt z odpowiedzi Jiry). Typów `Jira::Board`, `Jira::Sprint` i `Jira::Attachment` kontrakt nie ma (CC-211, CC-212, proposed).

Wyniki: listy zwracają `PagedResult` (`items`, `nextCursor`, `truncated`, `omitted`), bez `total`. Teksty pisane przez użytkowników Jiry
(tytuł, opis, komentarze, nazwy wyświetlane, nazwy plików) to `UntrustedText` (CC-072). Narzędzia zapisujące oddają `issueKey` z odpowiedzi
Jiry (klucz kanoniczny), nie z argumentu. Opis zgłoszenia i komentarze Jira trzyma jako wiki markup; serwer zamienia je na czysty tekst
(`JiraWikiText`), a tekst od modelu zapisuje w `{noformat}`. Pola, które Jira może zostawić puste, są `@Nullable` w wyniku.
