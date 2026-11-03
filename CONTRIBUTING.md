# Contributing

The server follows the contracts of `mcp-contracts`. A change that needs a different contract (a query field, a
resource type, the manifest) starts as a `proposed` entry in `CONTRACT_CHANGES.md` of that repository and needs
approval before it is implemented here.

## Setup

JDK 25. No container runtime is needed: tests run against `TestGateway` and an in-process stub of Jira. The
`mcp-platform-bom` artifacts (starter, contracts) must be in `~/.m2` or in the registry named by
`MCP_MAVEN_REPOSITORY_URL`. Linux and macOS with bash 3.2 or newer; Windows only through WSL2.

```bash
ci/run.sh verify     # text checks, then clean verify; the same script runs in CI
```

`docs/jak-napisac-narzedzie.md` describes how to add a tool. A new tool needs: `@ToolTitle`, `@ToolRisk`,
`@ResourceRef` on the argument that names the resource, a closed argument schema, a test class in the style of
`ReadToolsTest` or `WriteToolsTest`, and a row in the README table.

Test fixtures of a Jira version live in `mcp-app/src/test/resources/fixtures/jira-dc-<version>/`. They are written
from documented response shapes; when you have a real recording, anonymize hosts, e-mail addresses and keys first.

## Commits

Subject in English, imperative, up to 72 characters. The body describes the change, not the process. Tests go in the
same commit as the code they cover.

## License

Contributions are licensed under Apache-2.0 (see `LICENSE`).
