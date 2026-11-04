# mcp-jira

MCP server for Jira Data Center. It is one of the native servers of the MCP platform and runs only behind the platform
gateway: it accepts mutual TLS traffic carrying the gateway's internal JWT and calls Jira with the user's personal access
token (PAT), which the gateway attaches to every request. Without a valid JWT it answers 401; there is no anonymous mode.

The server does not log users in, decide who may call which tool, or ask for confirmation of destructive actions. The
gateway does that. The server provides the tools, the Jira adapter, error mapping and the audit trail the starter writes.

Built on `mcp-platform-starter` 3.0.0-beta-14 (Java 25, Spring Boot 4.1.0, Spring AI 2.0.0, `mcp-contracts` 6.3.2).

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

19 tools: 9 `READ_ONLY`, 9 `UPDATE` and 1 `DESTRUCTIVE` (`delete_issue`, which needs the confirmation code the gateway checks). A client
sees them as `jira__<name>`. The generated table with each tool's title, risk, action and required group is in
`docs/reference/tools.md` of the `mcp-docs` repository (link below). It is not repeated here.

Reads cover issues, comments, transitions, projects, assignable users, worklogs and attachment metadata. Writes cover
comments, transitions, creating and editing issues, assignment, links and worklogs. Search queries are built by the server
from structured arguments; no tool accepts raw JQL.

## Build and test

JDK 25 is required (`JAVA_HOME` must point to it; an older `java` in `PATH` fails with `UnsupportedClassVersionError`).

```bash
export JAVA_HOME=/path/to/jdk-25
./mvnw verify      # build and tests, jar in mcp-app/target/mcp-jira.jar
make ci            # ci/run.sh verify: source hygiene, mvnw clean verify, check that every test module ran
```

The tests need neither Docker nor a Jira. `TestGateway` from `mcp-platform-test` publishes a JWKS made from the contract test
keys and signs internal JWTs, and `StubBackend` answers like the Jira REST API. `make ci` also leaves the manifest in
`mcp-app/target/mcp-manifest.json`; register it in the gateway and approve new `UPDATE` and `DESTRUCTIVE` tools there.
`ci/run.sh image` builds the container image (`Dockerfile`, `eclipse-temurin:25-jre`, port 8443) from `main` only and
needs a Docker daemon.

## Configuration

The base configuration (`mcp-app/src/main/resources/application.yml`) is the production one. Outside the `dev` profile the
server does not start without these:

| Variable | Meaning |
| --- | --- |
| `MCP_TLS_KEYSTORE`, `MCP_TLS_TRUSTSTORE`, `MCP_TLS_STORE_PASSWORD` | PKCS12 server certificate and the internal CA |
| `MCP_GATEWAY_JWKS_URI` | JWKS of the gateway's internal JWT, `https` only |
| `MCP_CONNECT_URL` | portal page where a user connects an account; returned in `credential_required` |
| `MCP_JIRA_URL` | base address of Jira without `/rest/api/2`; `https` only |
| `MCP_POLICY_URL`, `MCP_POLICY_KEY_ID`, `MCP_POLICY_PUBLIC_KEY_FILE`, `MCP_POLICY_STATE_DIR` | signed policy bundle and the directory that keeps the last accepted revision |
| `MCP_INTERNAL_URL` | server address written to the manifest |

Also used: `MCP_JIRA_SSL_BUNDLE` (SSL bundle for a Jira with an internal CA), `MCP_PAGING_CURSOR_KEY` (Base64, at least 32 bytes,
the same on every instance; `openssl rand -base64 32`), `MCP_REDIS_*` (one-time `jti` store, ACL user `mcp-jira`) and
`MCP_PORT`/`MCP_BIND` (default `8443`, `0.0.0.0`). `MCP_JIRA_ALLOW_PLAIN_HTTP=true` allows `http` and exists only for the
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
