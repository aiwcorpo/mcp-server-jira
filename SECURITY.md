# Security policy

## Reporting a vulnerability

Report vulnerabilities privately through GitHub: Security tab of this repository, "Report a vulnerability"
(private vulnerability reporting). Do not open a public issue for a security problem.

Include the affected version (`pom.xml`), the tool involved, the Jira version, the configuration and steps to
reproduce. In scope: a request that gets past the internal JWT check, a way to read a Jira credential from a log or a
tool result, a result that returns an issue or project the policy denies, a tool that writes to a resource other than
the one the policy judged, and text from Jira that reaches a model as anything but untrusted data.

## Scope

In scope: the code in this repository (tools, the Jira adapter, resolvers, the version probe).

Out of scope:

- The `dev` profile relaxes TLS and the replay store by design; it listens on the loopback address only and
  is refused in a container, a Kubernetes pod and under systemd.
- The gateway, the credential broker, the policies and the starter are separate repositories.
- Jira itself, and permissions of the account whose personal access token the user connects.

## Supported versions

Only the latest released version receives fixes. Published versions are immutable; a fix ships as a new release.
