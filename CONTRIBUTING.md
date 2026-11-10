# Contributing

The server follows the contracts of `mcp-contracts`. A change that needs a different contract (a query field, a
resource type, the manifest) starts as a `proposed` entry in `CONTRACT_CHANGES.md` of that repository and needs
approval before it is implemented here.

## Setup

JDK 25. No container runtime is needed: tests run against `TestGateway` and an in-process stub of Jira. The
`mcp-platform-bom` artifacts (starter, contracts) come from Maven Central, or must already be in `~/.m2`. Linux and macOS with bash 3.2 or newer; Windows only
through WSL2.

```bash
./mvnw -B verify     # the same command runs in CI
```

`docs/developer/tools.md` in `mcp-docs` describes how to add a tool. A new tool needs: `@ToolTitle`, `@ToolRisk`,
`@ResourceRef` on the argument that names the resource, a closed argument schema, a test class in the style of
`ReadToolsTest` or `WriteToolsTest`, and its name in the tools table of the README.

Test fixtures of a Jira version live in `mcp-app/src/test/resources/fixtures/jira-dc-<version>/`. They are written
from documented response shapes; when you have a real recording, anonymize hosts, e-mail addresses and keys first.

## Policies

The Cedar policies of Jira are in `mcp-app/src/main/resources/mcp/policies/` and go into the jar and the manifest:

| File | Content |
| --- | --- |
| `schema.cedarschema` | namespace `Jira`: `Jira::Project` (classification attribute `category`) and `Jira::Issue` (parent: its project); actions `read`, `update`, `delete`, `admin` |
| `resource-types.json` | the declarations of both types: `idPattern` (project key, issue key), `scopePattern`, `earlyScopeCheck` for projects, the classification and the parent |
| `read.cedar` | reading needs `mcp-jira-read`; it follows the user's own rights in Jira |
| `update.cedar` | changing a project or an issue needs `mcp-jira-write` and a scope on the project; deleting an issue needs `mcp-jira-destructive` and the scope; a project is never deleted |

The platform generates the group a tool needs, the closed `restricted` projects and the limits of the service account. The
resolvers give each issue and project the scope `Jira::Project/<key>` of the project Jira reports, so a moved issue is judged by
its new project. `JiraPolicyDecisionsTest` states the decisions; a change to any file here changes `policiesHash` in the
manifest, and an administrator approves it before the server decides anything.

The gateway pushes the scope mapping, the allowed AI clients and the emergency overrides to `POST /.well-known/mcp-policy-data`
after every change and every minute. A server without fresh data answers `tools/call` with `409 policy_data_stale`, and the
gateway pushes and repeats the call.

## Tool behavior

- `get_issue` lists at most 50 links (`linksTruncated`; the first 200 are judged). Links to issues the caller may not read are
  dropped before the limit.
- `get_issue_changelog` judges every issue key in old and new values by its project, once per project per call. A key of an
  unreadable project becomes `<hidden-issue>`. A change of a field that points at other issues or projects (Link, Parent, Epic
  Link, Sub-task, project, any name containing link, parent, epic or sub-task) is shown only when its value is nothing but keys of
  readable projects; otherwise it is left out without a marker, so a page can hold fewer than 25 entries. The last change of the
  `project` field and every older entry are not returned. When Jira returns less history than it holds, the result has no entries
  and `omitted` is `history_incomplete`.
- `read_attachment` returns `text/*`, JSON and XML that decode as strict UTF-8 without NUL bytes, up to 200000 bytes, cut at 50000
  characters; other files come back empty with `omitted` set to `binary_attachment` or `attachment_too_large`. The file is
  downloaded from the configured Jira address, never from the `content` link in Jira's answer.
- `add_watcher` adds the given user, or the caller. `unwatch_issue` removes the caller only.
- `remove_issue_link` needs the destructive group, the project scope on both issues and the confirmation code, which is bound to
  the keys in the arguments. It removes one link by id (ids come from `get_issue`); the link must join exactly the two given issues.
- Boards and sprints are not policy resources, so they are judged by their projects: `list_boards` by the `project` argument,
  `list_sprints` and `list_sprint_issues` by every project of the board (`/board/{id}/project`), the latter then issue by issue. A
  board with an unreadable project, no readable project, more than 50 projects, or an incomplete project list is refused as
  `not_found_or_denied`.

## Jira versions

At start and every 5 minutes (30 seconds while Jira does not answer) the server reads `GET /rest/api/2/serverInfo` without
credentials. The state shows in the log (`backend capability: probe=jira state=...`), in the metric
`mcp_backend_capability{probe="jira",state}` and in the `backendCapability` detail of `/actuator/health`.
`MCP_JIRA_WRITES_ON_UNKNOWN_VERSION=true` opens writes only in the `unavailable` state, never for a version known to be untested.
Fixtures for 9.12 and 11.3 are written from the vendor documentation, not recorded.

## Build details

`./mvnw verify` leaves the manifest in `mcp-app/target/mcp-manifest.json`; the running server serves it at
`/.well-known/mcp-manifest`, and an administrator approves every new or changed tool there. `TestGateway` from
`mcp-platform-test` publishes a JWKS and signs internal JWTs; `StubBackend` answers like the Jira REST API. The image
(`Dockerfile`, `eclipse-temurin:25-jre`, port 8443) needs a Docker daemon. CI runs `./mvnw -B verify`; a `v*` tag runs
`.github/workflows/release.yml`. `JAVA_HOME` must point to JDK 25, or the build fails with `UnsupportedClassVersionError`.

## Running against a real Jira

`.run/` has two IntelliJ configurations. Start `LocalGateway` (the gateway of `mcp-platform-test` on
`http://127.0.0.1:18500/mcp`, serving the JWKS on port 18445 and pushing policy data with the policies of this working tree and
the mapping in `.run/scope-mapping.json`), then `McpJira (dev, behind LocalGateway)` with `MCP_JIRA_URL` set to your Jira.
Connect an MCP client to the gateway with `Authorization: Bearer <your PAT>`. Edit `.run/scope-mapping.json` and the `--groups`
of `LocalGateway` for your project keys, and restart `LocalGateway` after a change to the policies. The configurations need the
`4.0.0` starter artifacts in `~/.m2`. `MCP_JIRA_ALLOW_PLAIN_HTTP=true` allows `http` and exists for the mocks of the development stack.

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the
same commit as the code they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
