# mcp-jira

MCP server for Jira Data Center. It is one of the native servers of the MCP platform and runs only behind the platform
gateway: it accepts mutual TLS traffic carrying the gateway's internal JWT and calls Jira with the user's personal access
token (PAT), which the gateway attaches to every request. Without a valid JWT it answers 401; there is no anonymous mode.

The server does not log users in, decide who may call which tool, or ask for confirmation of destructive actions. The
gateway does that. The server provides the tools, the Jira adapter, error mapping and the audit trail the starter writes.

Built on `mcp-platform-starter` 4.0.0 (Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, `mcp-contracts` 7.0.0).

## Supported Jira versions

Jira Data Center 9.12 to 11.x, over REST API v2 (`/rest/api/2`). The tests use response fixtures for 9.12 and 11.3 written
from the vendor documentation, not recorded from a live instance. Jira Cloud, Jira Server and Data Center older than 9.12
are not supported.

At startup and every 5 minutes (30 seconds while Jira does not answer) the server reads `GET /rest/api/2/serverInfo`
without credentials and settles on one of three states:

| State | When | Effect |
| --- | --- | --- |
| `supported` | Data Center 9.12.0 to 11.x | all tools |
| `degraded` | older than 9.12, 12.0 or newer, or a Cloud deployment | reads work; every tool that changes data fails with `upstream_unavailable`, whatever the settings |
| `unavailable` | no answer, `serverInfo` blocked, no version in the answer, or the time before the first probe result | reads work; writes are closed unless `MCP_JIRA_WRITES_ON_UNKNOWN_VERSION=true` |

`MCP_JIRA_WRITES_ON_UNKNOWN_VERSION=true` opens writes only in the `unavailable` state, for example behind a proxy that blocks
anonymous `serverInfo`. It never opens them for a version known to be untested. The state is in the log
(`backend capability: probe=jira state=...`), in the metric `mcp_backend_capability{probe="jira",state}` and in the
`backendCapability` detail of `/actuator/health`.

## Tools

32 tools: 19 `READ_ONLY`, 11 `UPDATE` and 2 `DESTRUCTIVE` (`delete_issue` and `remove_issue_link`, which need the confirmation code the gateway checks). A client
sees them as `jira__<name>`. The generated table with each tool's title, risk, action and required group is in
`docs/reference/tools.md` of the `mcp-docs` repository (link below). It is not repeated here.

Reads cover issues, comments, transitions, projects, assignable users, worklogs and attachment metadata, and since 2.0.0 also the
change history of an issue (`get_issue_changelog`), the statuses of a project per issue type (`list_statuses`), the issue types a
project offers for creating issues (`list_issue_types`) and the fields of one of them (`get_create_fields`: id, name, whether it
is required, schema type, at most 20 allowed values per field), link types (`list_link_types`), the account behind the credential
(`get_current_user`, no e-mail) and the text of an attachment (`read_attachment`). Writes cover comments, transitions, creating
and editing issues, assignment, links, worklogs and watchers. Search queries are built by the server from structured arguments;
no tool accepts raw JQL.

`read_attachment` returns text types only (`text/*`, JSON, XML) that decode as strict UTF-8 without NUL bytes (anything else is `binary_attachment`), up to 200000 bytes, cut at 50000 characters; other files come back
empty with `omitted` set to `binary_attachment` or `attachment_too_large`. The file is downloaded from the configured Jira
address, never from the `content` link in Jira's answer.

`get_issue` also lists the links of the issue (link id, type name, direction with its sentence, key of the other issue; at most 50,
`linksTruncated`; the first 200 links are judged). Links to issues the caller may not read are dropped before the limit, so neither their keys nor their number show.

`get_issue_changelog` judges every issue key in the old and new values by its project (the policy decision on `Jira::Project`,
once per project in a call, never by resolving the issue, because an old key of a moved issue would resolve to its new project).
A key of a project the caller may not read is replaced by `<hidden-issue>`. A change of a field that points at other issues or
projects (Link, Parent, Parent Link, Epic Link, Epic Child, Key, Sub-task, project and any name containing link, parent, epic,
sub-task) is shown only when its value is nothing but keys of readable projects; otherwise the whole change is left out, with no
marker, and an entry left without changes is left out too, so a page can be shorter than 25. Link changes read "This issue blocks
ABC-5", which has text besides the key, so they are always left out. Workflow, Fix Version/s, Version/s, Component/s and Sprint
values of entries older than the last change of the `project` field are null.

`add_watcher` adds the given user, or the caller when no username is given. `unwatch_issue` removes the caller only: a tool that
takes other users off a watcher list is not offered. `remove_issue_link` is `DESTRUCTIVE` like `delete_issue`: it needs the destructive
group, the project scope on both issues and the confirmation code, which is bound to the keys in the arguments (an issue that was
moved is refused until the call names its current key). It removes one link by id and takes the keys of both linked issues, as
`link_issues` does; the link must join exactly these two issues. Link ids come from `get_issue`.

### Boards and sprints

`list_boards`, `list_sprints` and `list_sprint_issues` use the Agile REST API (`/rest/agile/1.0`) of Jira Software and fail like a
missing resource on an instance without it. Boards and sprints are not resources of the policy, so they are judged by the projects
behind them:

| Tool | Argument | Judged by |
| --- | --- | --- |
| `list_boards` | `project` | the project (as `search_issues`); boards located in a project the policy does not allow, or without a recognized location, are left out |
| `list_sprints` | `boardId` | every project of the board (`/board/{id}/project`); a sprint created on another board is listed only when that board passes the same check |
| `list_sprint_issues` | `sprintId` | every project of the board the sprint belongs to, then each issue by the issue policy, as `search_issues` does |

A board with a project the caller may not read, with no readable project, with more than 50 projects, or whose project list Jira
does not return in full (`isLast` false or `total` above the keys returned) is refused as
`not_found_or_denied`, the same answer as for a board that does not exist.

## Build and test

JDK 25 is required (`JAVA_HOME` must point to it; an older `java` in `PATH` fails with `UnsupportedClassVersionError`).

```bash
export JAVA_HOME=/path/to/jdk-25
./mvnw verify      # build and tests, jar in mcp-app/target/mcp-jira.jar
```

The tests need neither Docker nor a Jira. `TestGateway` from `mcp-platform-test` publishes a JWKS made from the contract test
keys and signs internal JWTs, and `StubBackend` answers like the Jira REST API. `./mvnw verify` also leaves the manifest in
`mcp-app/target/mcp-manifest.json`; it is the document the running server serves at `/.well-known/mcp-manifest` for the gateway to pull, and an administrator approves every new or changed tool there.
`docker build -t mcp-jira:dev .` builds the container image (`Dockerfile`, `eclipse-temurin:25-jre`, port 8443) from that jar and needs a Docker daemon. The GitHub, GitLab and Jenkins files run `./mvnw -B verify`.

## Policies

The Cedar policies of Jira live here, in `mcp-app/src/main/resources/mcp/policies/`, and are part of the jar and of the manifest:

| File | Content |
| --- | --- |
| `schema.cedarschema` | namespace `Jira`: `Jira::Project` (with the classification attribute `category`) and `Jira::Issue` (parent: its project), actions `read`, `update`, `delete`, `admin` |
| `resource-types.json` | the declarations of both types: `idPattern` (project key, issue key), `scopePattern`, `earlyScopeCheck` for projects, the classification and the parent |
| `read.cedar` | reading needs `mcp-jira-read`; it follows the user's own rights in Jira |
| `update.cedar` | changing a project or an issue needs `mcp-jira-write` and a scope on the project; deleting an issue needs `mcp-jira-destructive` and the scope; a project is never deleted |

What is not written here is generated by the platform: the group a tool needs, the closed `restricted` projects (from the declared
`category`, for the project and for its issues) and the limits of the service account. Who holds which project scope is
administrator data in the gateway (the scope mapping), not part of this repository. The resolvers give each issue and project the
scope `Jira::Project/<key>` of the project Jira reports, so an issue moved to another project is judged by the new one.
`JiraPolicyDecisionsTest` states the decisions with `PolicyDecisions`; a change to any file here changes `policiesHash` in the
manifest, and an administrator has to approve it before the server decides anything.

## Running against a real Jira

`.run/` has two IntelliJ configurations. Start `LocalGateway` (the gateway of `mcp-platform-test` on `http://127.0.0.1:18500/mcp`,
serving the JWKS on port 18445 and pushing the policy data to the server with the policies of this working tree and the mapping in
`.run/scope-mapping.json`), then `McpJira (dev, behind LocalGateway)` with `MCP_JIRA_URL` set to your Jira. Connect an MCP client to
the gateway with `Authorization: Bearer <your PAT>`. Edit `.run/scope-mapping.json` and the `--groups` of the `LocalGateway`
configuration for your project keys, and restart `LocalGateway` after a change to the policies (it approves the hash of the files
it read). The configurations need the `4.0.0` artifacts in `~/.m2` (`./mvnw verify` of the starter).

## Configuration

The base configuration (`mcp-app/src/main/resources/application.yml`) is the production one. Outside the `dev` profile the
server does not start without these:

| Variable | Meaning |
| --- | --- |
| `MCP_GATEWAY_JWKS_URI` | public key set of the gateway's internal JWT: `https://<domain of the platform>/.well-known/mcp-jwks.json` |
| `MCP_CONNECT_URL` | portal page where a user connects an account; returned in `credential_required` |
| `MCP_JIRA_URL` | base address of Jira without `/rest/api/2`; `https` only |

Also used: `MCP_JIRA_SSL_BUNDLE` (SSL bundle for a Jira with an internal CA), `MCP_PAGING_CURSOR_KEY` (Base64, at least 32 bytes,
optional for one instance (kept in the state directory), required and the same on every instance for several; `openssl rand -base64 32`), `MCP_TLS_CERT` and `MCP_TLS_KEY`, or `MCP_TLS_KEYSTORE` with `MCP_TLS_STORE_PASSWORD` (your own certificate; without them the server generates a
self-signed one into `MCP_STATE_DIR`, and `MCP_PLAIN_HTTP=true` means no TLS inside the platform network), `MCP_REDIS_HOST` and the other `MCP_REDIS_*`
(one-time `jti` in Redis instead of memory: for several instances behind a balancer, which also need the same `MCP_PAGING_CURSOR_KEY`) and `MCP_PORT`/`MCP_BIND` (default `8443`, `0.0.0.0`). `MCP_JIRA_ALLOW_PLAIN_HTTP=true` allows `http` and exists only for the
mocks of the development stack. The full list with defaults is in `docs/reference/env-servers.md` of `mcp-docs`.

The account behind a PAT needs the matching Jira project permissions (Browse Projects for reads, Create Issues, Edit Issues,
Transition Issues and so on for writes). A PAT has no scopes; Jira decides everything on its side, and the platform policy
can only narrow what the account is allowed to do.

## Documentation

Installation, operation, upgrades, the checks to run on a first real instance and the tool reference live in
[mcp-docs](https://github.com/aiwcorpo/mcp-docs):

- `docs/reference/tools.md`: tools with risk and required group
- `docs/reference/env-servers.md`: environment variables of every server
- `docs/operator/real-systems.md`: supported product versions, read-only checks on a real instance

See also `CONTRIBUTING.md`, `SECURITY.md` and `CHANGELOG.md`. Licensed under Apache-2.0.
