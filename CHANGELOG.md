# Changelog

## Unreleased

- Starter 3.0.0-beta-14 (contracts 6.3.2).
- Jira Data Center 9.12 to 11.x: version probe on `serverInfo`; `degraded` state (older, newer, Cloud) serves reads only.
- `search_issues` returns `PagedResult` with a cursor (`items`, `nextCursor`) instead of `{project, issues, hasMore}`; new
  arguments `order_by`, `order`, `cursor` and the `summary` filter; pages are filled after the policy filter. Breaking for clients.
- New tools: `list_comments`, `list_transitions`, `get_project`, `list_projects`, `find_assignable_users`, `list_worklogs`,
  `list_attachments`, `edit_comment`, `create_issue`, `update_issue`, `assign_issue`, `unassign_issue`, `link_issues`, `add_worklog`.
- New setting `MCP_PAGING_CURSOR_KEY`.
- Writes need a `supported` probe state with `issue_write`; closed before the first probe and while the version is unreadable unless
  `MCP_JIRA_WRITES_ON_UNKNOWN_VERSION=true`; an untested-version finding is kept across failed probe runs.
- `edit_comment` sends the role or group visibility of the comment back and refuses other restrictions.
- `find_assignable_users` needs at least 3 characters; `list_projects` reads at most 500 projects and sets `truncated`.
- Times, ids and the key of a created issue from Jira are validated before they reach a model.
- Apache-2.0 `LICENSE` and `NOTICE`, `SECURITY.md`, `CONTRIBUTING.md`.
