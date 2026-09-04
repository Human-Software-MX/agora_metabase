# Multi-tenant Agora embedding

This fork exposes `/api/agora/embed/*` so Agora can embed Metabase. In multi-tenant mode every
Agora account (tenant) gets its own isolated view: its own database connections, dashboards and
questions. Isolation is provided by Metabase's built-in **Tenants** feature; the Agora endpoints only
add tenant-aware authentication and a tenant-scoped dashboard list on top of it.

## How it works

1. The Agora backend signs a short-lived JWT for the current Agora user with the shared secret
   configured in Metabase (`MB_JWT_SHARED_SECRET`). The token identifies the tenant with the
   `@tenant` claim.
2. Agora passes that token to the Agora endpoints as the `jwt` query parameter.
3. Metabase verifies the token with its JWT SSO provider. On first login it creates the tenant, the
   tenant's collection ("Our data"), the user, and a **tenant group** named after the tenant slug,
   and adds the user to that group.
4. The session belongs to a tenant user. Tenant users only see their tenant collection, the shared
   tenant collections, and the databases their tenant groups are allowed to query. New tenant groups
   start with the most restrictive data permissions, so a tenant sees no data until an admin grants it.

## Token contract

Sign with HS256 using `MB_JWT_SHARED_SECRET`. Tokens older than 3 minutes are rejected, so mint one
per request.

```json
{
  "email": "user@customer.example",
  "first_name": "Ada",
  "last_name": "Lovelace",
  "@tenant": "customer-slug",
  "@tenant.attributes": { "customer_id": "175924" },
  "groups": ["premium"],
  "iat": 1750000000
}
```

- `@tenant` (required): tenant slug, lowercase letters, digits, `-` and `_`. It becomes the `@tenant.slug`
  user attribute, usable in row-level security, impersonation and database routing.
- `@tenant.attributes` (optional): attributes inherited by every user of the tenant. Existing values are
  never overwritten.
- `groups` (optional): extra tenant group names to sync, when JWT group sync is enabled in Metabase.

The claim names can be changed in Admin > Settings > Authentication > JWT.

## Endpoints

| Endpoint | Multi-tenant mode (`jwt` present) | Legacy mode (no `jwt`) |
| --- | --- | --- |
| `GET /api/agora/embed/dashboards?jwt=…` | Non-archived dashboards in the tenant collection tree. `embed_url` (static embed) only when the dashboard has static embedding enabled; `app_url` always. | Root-collection dashboards with static embedding enabled. |
| `GET /api/agora/embed/collection-url?jwt=…&return_to=…` | Verifies the token and returns the `collection-auth` URL plus the tenant (`null` before its first login). `return_to` is optional. | Signs a 5-minute token for `return_to` (required). |
| `GET /api/agora/embed/collection-auth?jwt=…&return_to=…` | Logs the tenant user in (provisioning tenant and user if needed), sets the embedded session cookie and redirects to `return_to` or the tenant collection. | Session for `MB_AGORA_VIEWER_EMAIL` from the legacy `token`. |

Errors come back as HTTP 400 with the reason as a plain-text body (for example `Message seems
corrupt or manipulated.` for a bad signature, `Tenants are not enabled...` when the multi-tenant
strategy is off, or `Tenant ID mismatch with existing user`). Details are also written to the
Metabase log.

Dashboards in shared tenant collections are not listed for static embedding: static embeds run
without a user, so tenant-aware data permissions would not apply. Open them through the full-app
embedded session instead (`app_url`).

## Metabase configuration

1. Build and run the Enterprise edition. `docker-compose.yml` now defaults to `MB_EDITION=ee`.
2. Environment (see `env.example`):
   - `MB_JWT_ENABLED=true`, `MB_JWT_SHARED_SECRET=<openssl rand -hex 32>`
   - `MB_ENABLE_EMBEDDING_STATIC=true`, `MB_EMBEDDING_SECRET_KEY=<openssl rand -hex 32>`
   - `MB_ENABLE_EMBEDDING_INTERACTIVE=true`, `MB_EMBEDDING_APP_ORIGINS_INTERACTIVE=https://app.agora.example`
   - Serve Metabase over HTTPS: the embedded session cookie is `SameSite=None; Secure`.
3. In Admin > People click the gear icon and choose the **Multi-tenant** user strategy. This cannot be
   set from the environment.
4. Leave JWT user provisioning enabled (the default) so tenants and users are created on first login.
5. Unset `MB_AGORA_VIEWER_EMAIL` unless you still need the legacy single-viewer mode.

## Giving a tenant its own databases

1. Admin > Databases: add the tenant's connection.
2. Admin > Permissions > Data: for the tenant group named after the slug (created on the tenant's first
   login, or create it yourself under Admin > People > Tenant groups) set **View data** to *Can view*
   and **Create queries** as needed on that database only. Leave every other database blocked.

Alternatives when tenants share a database with the same schema: use **database routing** on the
`@tenant.slug` attribute, or **row and column security** / **impersonation** with tenant attributes.
See `docs/embedding/tenants.md` and `docs/permissions/database-routing.md`.

## Where tenant content lives

- Each tenant has a dedicated collection tree ("Our data"). Dashboards and questions created by the
  tenant's users live there and are invisible to other tenants.
- Shared tenant collections (Admin > Permissions > Shared collections) hold content that every tenant
  can open, with data filtered per tenant by the permissions above.
- Internal (non-tenant) users and the root collection are never visible to tenant users.
