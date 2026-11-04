# Changelog

## 1.0.0

First public release. MCP server for Jira Data Center 9.12 to 11.x, run behind the platform gateway. Built on
`mcp-platform-starter` 3.0.0 and `mcp-contracts` 6.3.2.

- 19 tools. Reads: `search_issues`, `get_issue`, `list_comments`, `list_transitions`, `get_project`, `list_projects`,
  `find_assignable_users`, `list_worklogs`, `list_attachments`. Changes: `add_comment`, `edit_comment`, `transition_issue`,
  `create_issue`, `update_issue`, `assign_issue`, `unassign_issue`, `link_issues`, `add_worklog`, `delete_issue`.
- Lists return `PagedResult` with a cursor (`items`, `nextCursor`); pages are filled after the policy filter. The cursor is
  signed with `MCP_PAGING_CURSOR_KEY`.
- A version probe on `serverInfo` sets the state; outside the supported range (older, newer, Cloud) the server is `degraded` and
  serves reads only. Writes need the `supported` state with `issue_write`; they stay closed before the first probe and while the
  version is unreadable, unless `MCP_JIRA_WRITES_ON_UNKNOWN_VERSION=true`.
- `edit_comment` keeps the role or group visibility of the comment and refuses other restrictions. `find_assignable_users`
  needs at least 3 characters; `list_projects` reads at most 500 projects and sets `truncated`.
- Times, ids and the key of a created issue returned by Jira are validated before they reach a model.
- Tool titles are in English. Apache-2.0 `LICENSE` and `NOTICE`, `SECURITY.md`, `CONTRIBUTING.md`; the release workflow builds the
  image for ghcr.io and attaches the manifest and SBOM to the GitHub Release.
