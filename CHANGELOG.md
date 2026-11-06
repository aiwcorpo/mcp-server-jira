# Changelog

## 2.0.0

Major release: the policies of the server live in this repository. Built on `mcp-platform-starter` 4.0.0 and `mcp-contracts` 7.0.0.

- Cedar policies in `mcp-app/src/main/resources/mcp/policies/` (schema in the namespace `Jira`, `resource-types.json`, `read.cedar`,
  `update.cedar`), carried in the manifest with `policiesHash`. They replace `policies/jira/` of the `mcp-policies` repository; the
  rules are the same, except what the platform now generates for every server: the call rules by group, the forbid for
  `restricted` projects (from `Jira::Project.category`) and the rule for the service account.
- A deployed server whose policies or declarations changed waits for approval in the gateway: it refuses calls (`policies_not_approved`,
  `policies_differ`) until an administrator approves the new `policiesHash`. Register the new manifest and approve before the first call.
- Both resolvers describe the scope `Jira::Project/<key>` of the project Jira reports and provide the grant probe (`probe(id)`), so a
  caller without a grant is refused before Jira is asked. Key arguments take their pattern from the declared types
  (`@ArgumentLimits(resourceType = ...)`).
- Removed: `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR`, the signed policy bundle and the
  `StateDirectory` of the unit. Added: `MCP_GATEWAY_POLICY_DATA_URL` (and `MCP_GATEWAY_TLS_NAME` when the host in it is not a name of the
  gateway certificate). The server connects to the gateway for the policy data with its own certificate, which now needs the usage
  `clientAuth` next to `serverAuth` (CN `mcp-jira`, SAN `mcp-jira.internal`).
- `deploy/` has `compose.yaml`, `mcp-jira.env` and `certs/` next to the systemd files, and the procedure that adds the system
  `jira` in the broker and the server `mcp-jira` in the gateway without restarting anything.
- `.run/` has IntelliJ configurations for the server and for the `LocalGateway` of `mcp-platform-test`, with the policies directory and a scope mapping.

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
