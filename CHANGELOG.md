# Changelog

## Unreleased

- Starter 3.0.0-beta-13 (contracts 6.2.1).
- Jira Data Center 9.12 to 11.x: version probe on `serverInfo`; `degraded` state (older, newer, Cloud) serves reads only.
- `search_issues` returns `PagedResult` with a cursor (`items`, `nextCursor`) instead of `{project, issues, hasMore}`; new
  arguments `order_by`, `order`, `cursor` and the `summary` filter; pages are filled after the policy filter. Breaking for clients.
- New tools: `list_comments`, `list_transitions`, `get_project`, `list_projects`, `find_assignable_users`, `list_worklogs`,
  `list_attachments`, `edit_comment`, `create_issue`, `update_issue`, `assign_issue`, `unassign_issue`, `link_issues`, `add_worklog`.
- New setting `MCP_PAGING_CURSOR_KEY`.
- Apache-2.0 `LICENSE` and `NOTICE`, `SECURITY.md`, `CONTRIBUTING.md`.
