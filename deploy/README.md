# Install mcp-jira

The Jira Data Center server as one container (Docker Compose) or one systemd service. It runs behind the MCP gateway; the gateway,
the credential broker and the identity provider are installed elsewhere. Step by step for a VM: `docs/operator/install-vm.md`
in `mcp-docs`. The image is built from the `Dockerfile` in the repository root.

```
deploy/
|-- compose.yaml                 the server as a container (read-only, no capabilities)
|-- mcp-jira.env                 settings for Compose: fill in the empty values
|-- certs/                       keystore.p12 and truststore.p12 go here (never committed)
`-- systemd/
    |-- mcp-jira.service         unit (account mcp, systemd sandbox, logs to journald)
    `-- mcp-jira.env.template    settings -> /etc/mcp-jira/mcp-jira.env
```

## Prerequisites

- Docker Engine with Compose v2.20 or newer (or Java 25 and systemd), 1 GiB of memory.
- From your internal CA, a certificate for this server with:
  - CN `mcp-jira` (the gateway takes the server id from it, and the policy data are served only to a CN that is a registered
    server),
  - SAN `mcp-jira.internal` (the gateway checks this name),
  - extended key usage `serverAuth` and `clientAuth`: the gateway connects to the server, and the server connects to the gateway for
    the policy data with the same certificate.

  Also the CA certificate.
- The addresses of the gateway (internal TLS port), of Jira Data Center (`https`) and of the portal page for connecting an account.
- Redis: `compose.yaml` starts one next to the server (service `redis`, reachable from the server only, default user off, one ACL account that may run `SET` on its own `jti:` keys). `MCP_REDIS_PASSWORD` in the env file is its password: choose one (`openssl rand -hex 24`). For one Redis shared by several instances, remove the service and set `MCP_REDIS_HOST` in the compose file.

## Install

1. Copy this directory to the host.
2. Put two files in `certs/`:

    | File | What |
    | --- | --- |
    | `keystore.p12` | the server certificate and its key |
    | `truststore.p12` | the internal CA certificate |

    Both stores use one password. From PEM files:

    ```bash
    openssl pkcs12 -export -inkey mcp-jira.key -in mcp-jira.crt -certfile ca.crt -name mcp-jira -out certs/keystore.p12
    keytool -importcert -noprompt -alias ca -file ca.crt -keystore certs/truststore.p12 -storetype PKCS12
    ```

3. Fill in the empty values in `mcp-jira.env`.
4. Start:

    ```bash
    docker compose up -d
    ```

The server starts before the platform knows it. Until an administrator has approved its policies it refuses every call
(`upstream_unavailable`, reason `policies_not_approved` in the audit), which is the state the next section ends.

## Add the server to the platform

Systems and servers are data of the broker and the gateway, not settings of this directory. Nothing restarts, in either direction:
every instance sees a change within a few seconds. Adding, changing and removing need an administrator token with a fresh MFA login
(`mcp-platform-admin`); the portal (Administration) does all of this, and the calls below are the same operations for scripts.

1. **System in the broker.** Jira accepts the user's own token or password, so the system has the type `user-secret` only:

    ```bash
    curl --cert bff.crt --key bff.key --cacert ca.crt -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
      -H 'If-None-Match: *' -X PUT https://mcp-credential-broker.corp.internal:8443/v1/admin/systems/jira -d '{
        "baseUrl":"https://jira.corp.internal","adapter":"atlassian-dc","product":"jira",
        "supportedTypes":["user-secret"],"defaultType":"user-secret","secretScheme":"Bearer"}'
    ```

2. **Server in the gateway.** The `serverId` is `mcp-jira`, the address is the internal HTTPS URL of this container or service:

    ```bash
    curl --cert bff.crt --key bff.key --cacert ca.crt -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
      https://mcp-gateway.corp.internal:8443/admin/v1/servers -d '{"serverId":"mcp-jira","system":"jira",
      "internalUrl":"https://mcp-jira.corp.internal:8443/mcp","credential":{"required":true,"acceptedTypes":["user-secret"],"type":"user-secret"}}'
    ```

3. **Deploy** the server as above (`docker compose up -d`, or the VM steps below).
4. **Register the manifest.** The pipeline of the repository does it with `mcp-app/target/mcp-manifest.json`, the build artifact (it is
   attached to the GitHub Release) and the certificate `mcp-registry-ci-mcp-jira`:

    ```bash
    curl --cert ci.crt --key ci.key --cacert ca.crt -H 'Content-Type: application/json' -X PUT \
      https://mcp-gateway.corp.internal:8443/admin/v1/registry/servers/mcp-jira --data-binary @mcp-manifest.json
    ```

    The tools that only read are approved by this call; the tools that change data, and the policies of the server, wait. Until they
    are approved the server has no tools for anyone.
5. **Approve** the policies (`GET /admin/v1/registry/servers/mcp-jira/policies/pending` shows them, `POST .../policies/approve` takes
   their `policiesHash`) and the tools that wait (`POST /admin/v1/registry/servers/mcp-jira/tools/{tool}/approve`), in the portal under
   Administration, Servers. A later release that changes a policy, a declaration or a tool puts the server in `pending_approval`
   for that part until it is approved again; the server refuses calls with `policies_differ` in the meantime.
6. **Groups.** The server needs the groups `mcp-jira-read`, `mcp-jira-write` and `mcp-jira-destructive` in the identity provider (the
   gateway derives them from the risk of a tool). Changes in Jira are allowed only inside projects that a group is mapped to; map
   them in Administration, Scope mapping, or with `PUT /admin/v1/policy/scope-mapping` (the whole document, `If-Match` with the
   `ETag` of the last `GET`):

    ```json
    {"scopeMapping": [{"group": "mcp-jira-proj-ABC", "scopes": ["Jira::Project/ABC"]}]}
    ```

    A user in `mcp-jira-write` and `mcp-jira-proj-ABC` may then change issues of ABC and nothing else; reading follows the user's
    rights in Jira. Projects with the category `restricted` are closed to everyone, whatever the groups.

### Remove the server

Delete the server in the gateway (`DELETE /admin/v1/servers/mcp-jira`) and the system in the broker (`DELETE /v1/admin/systems/jira`).
Its tools leave every `tools/list`, calls are refused as for an unknown tool, and the entries of the scope mapping for `Jira::Project`
go with it. Then stop the container or the service.

## Policy data

The server pulls the scope mapping, the allowed AI clients (`claude-code`, `claude-ai` by default) and the emergency overrides from
`MCP_GATEWAY_POLICY_DATA_URL` over mTLS with its own certificate, and asks again every 30 seconds (at once when a call carries a newer
revision). A server that cannot reach the gateway at start keeps asking and refuses calls with `policy_data_unavailable`. The
policies themselves are in the jar (`mcp/policies/`); there is nothing to configure for them and no key to distribute.

## Closed network

Copy the image to your registry and point Compose at it in a `.env` file next to `compose.yaml`:

```bash
MCP_REGISTRY=artifactory.corp.test/mcp
```

## Upgrade from 1.x

Version 2.0.0 reads the policy data from the gateway and the policies from the jar, and refuses to start while a setting of the earlier
policy bundles is left. Remove those settings, set `MCP_GATEWAY_POLICY_DATA_URL`, and renew the
certificate if it has no `clientAuth` usage. Register the new manifest and approve the policies as above before the first call.

## Virtual machine without Docker

`systemd/` has a unit and its environment file for the jar (`mcp-app/target/mcp-jira.jar` after `./mvnw verify`). The variables are
the ones of `mcp-jira.env`, with the stores at `/etc/mcp-jira/tls`.

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin mcp
sudo install -d -o root -g mcp -m 0750 /opt/mcp-jira /etc/mcp-jira /etc/mcp-jira/tls
sudo install -o root -g mcp -m 0640 mcp-jira.jar /opt/mcp-jira/mcp-jira.jar
sudo install -o root -g mcp -m 0640 systemd/mcp-jira.env.template /etc/mcp-jira/mcp-jira.env   # then fill it in
sudo install -m 0644 systemd/mcp-jira.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now mcp-jira
```

Health over mTLS (the probe certificate comes from the same CA):

```bash
curl -fsS --cacert ca.crt --cert probe.crt --key probe.key https://mcp-jira.internal:8443/actuator/health
```

Update: keep the old jar for a rollback, replace it and restart.

```bash
sudo cp -a /opt/mcp-jira/mcp-jira.jar /opt/mcp-jira/mcp-jira.jar.bak
sudo install -o root -g mcp -m 0640 new-mcp-jira.jar /opt/mcp-jira/mcp-jira.jar
sudo systemctl restart mcp-jira
```
