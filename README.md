# mcp-jira

Part of [MCP Platform](https://github.com/aiwcorpo/mcp-platform), a self-hosted MCP gateway for companies. Documentation: [mcp.aiwcorpo.pl](https://mcp.aiwcorpo.pl/).

MCP server for Jira Data Center. AI clients such as Claude Code reach it through the gateway of the aiwcorpo MCP platform;
the server calls Jira with each user's own personal access token.

## Quick start

```bash
cd deploy
# fill in MCP_GATEWAY_JWKS_URI, MCP_CONNECT_URL and MCP_JIRA_URL in mcp-jira.env
docker compose up -d
docker compose logs mcp-jira | grep event=tls_certificate
```

Then the platform operator adds `https://<this host>:8443/mcp` in the portal. Full steps: [deploy/README.md](deploy/README.md).

## Tools

32 tools, shown to a client as `jira__<name>`. Reading needs the group `mcp-jira-read`, writing `mcp-jira-write`. Two tools
delete data and need the group `mcp-jira-destructive` and a confirmation code from the Authenticator, marked in the Delete column.

| Area | Read | Write | Delete |
| --- | --- | --- | --- |
| Issues | `get_issue`, `search_issues`, `get_issue_changelog` | `create_issue`, `update_issue`, `assign_issue`, `unassign_issue` | `delete_issue` |
| Comments and transitions | `list_comments`, `list_transitions` | `add_comment`, `edit_comment`, `transition_issue` | |
| Links | `list_link_types` | `link_issues` | `remove_issue_link` |
| Worklogs and watchers | `list_worklogs` | `add_worklog`, `add_watcher`, `unwatch_issue` | |
| Attachments | `list_attachments`, `read_attachment` | | |
| Projects and fields | `list_projects`, `get_project`, `list_statuses`, `list_issue_types`, `get_create_fields`, `find_assignable_users` | | |
| Boards and sprints | `list_boards`, `list_sprints`, `list_sprint_issues` | | |
| User | `get_current_user` | | |

No tool takes raw JQL: `search_issues` builds the query from structured arguments for one project. `read_attachment` returns
text files only, up to 200000 bytes. Boards and sprints use the Agile API of Jira Software.

Users connect with a personal access token (credential type `user-secret`). The Jira account behind the token needs the
matching project permissions (Browse Projects for reading; Create Issues, Edit Issues, Transition Issues and so on for
writing). Writes are allowed only inside Jira projects that the platform operator maps to the user's group (scope mapping).
Projects with the category `restricted` are closed to everyone.

## Supported Jira versions

Jira Data Center 9.12 to 11.x, REST API v2. Jira Cloud, Jira Server and Data Center older than 9.12 are not supported.

The server reads the version at start and every 5 minutes:

| State | When | Effect |
| --- | --- | --- |
| `supported` | Data Center 9.12 to 11.x | all tools |
| `degraded` | older than 9.12, 12.0 or newer, Cloud | reads work, writes fail with `upstream_unavailable` |
| `unavailable` | Jira does not answer or hides its version | reads work, writes are closed unless `MCP_JIRA_WRITES_ON_UNKNOWN_VERSION=true` |

The server has not been tested against a live Jira. The tests use responses written from the vendor documentation for 9.12 and
11.3. Run read-only calls on your instance first.

## Configuration

Required, in `deploy/mcp-jira.env`:

| Variable | Description |
| --- | --- |
| `MCP_GATEWAY_JWKS_URI` | key set of the gateway: `https://<platform>/.well-known/mcp-jwks.json` |
| `MCP_CONNECT_URL` | portal page where a user connects Jira: `https://<platform>/connections?system={system}` |
| `MCP_JIRA_URL` | address of Jira without `/rest/api/2`, `https` only |

`MCP_ACCEPTED_CREDENTIAL_TYPES` is preset to `user-secret`. Optional settings (own certificate, Redis for several instances,
trust store for a Jira with a company CA) are commented in the same file.

## Build

```bash
export JAVA_HOME=<path to JDK 25>
./mvnw verify
```

The jar is `mcp-app/target/mcp-jira.jar`; `docker build -t mcp-jira:dev .` builds the image. Tests need neither Docker nor
Jira. Working on the code: [CONTRIBUTING.md](CONTRIBUTING.md).

## More

- [deploy/README.md](deploy/README.md): installation, troubleshooting, upgrade from 1.x
- [CHANGELOG.md](CHANGELOG.md), [SECURITY.md](SECURITY.md)

Apache License 2.0.
