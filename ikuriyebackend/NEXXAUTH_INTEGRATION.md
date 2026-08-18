# Nexxauth Integration

How the Ikuriye stack authenticates with **Nexxauth** (`https://auth.med.rw/master`),
and how the backend stays in sync with it — roles, users, and permissions.

- [1. Model: who does what](#1-model-who-does-what)
- [2. Clients registered in Nexxauth](#2-clients-registered-in-nexxauth)
- [3. Login & register (email or phone + password)](#3-login--register-email-or-phone--password)
- [4. Verifying tokens on the backend](#4-verifying-tokens-on-the-backend)
- [5. Permissions come from the token](#5-permissions-come-from-the-token)
- [6. Admin user management — hand in hand with Nexxauth](#6-admin-user-management--hand-in-hand-with-nexxauth)
- [7. Local user id = Nexxauth user id](#7-local-user-id--nexxauth-user-id)
- [8. What stays on Supabase](#8-what-stays-on-supabase)
- [9. Configuration & environment](#9-configuration--environment)
- [10. Gotchas](#10-gotchas)

---

## 1. Model: who does what

| Layer | Auth responsibility | Notes |
|---|---|---|
| **Nexxauth** | Identity, passwords, refresh tokens, **roles** | The source of truth. Logs users in/out, issues RS256 org-access JWTs, stores which roles a user holds. |
| **Backend (ikuriyebackend)** | Verifies org tokens offline, mirrors users locally, **pushes role/enable changes to Nexxauth** | The `users` table is a profile/business mirror — never the auth decision. |
| **Android (ikuriye)** | Calls Nexxauth directly for register/login/refresh/logout/change-password | Sends the org-access JWT to the backend GraphQL API. |

The guiding rule: **Nexxauth decides who can log in and what roles they have; the
backend trusts the token and keeps its own copy of the profile for business
queries (search by role, worker/driver profiles, package ownership).**

## 2. Clients registered in Nexxauth

Two clients are registered in the organisation (console → clients):

| Client | Type | Used by | Auth |
|---|---|---|---|
| **ikuriye-server** | `SERVER` | Backend (`NexxauthClient`) | static `nx_` token — full org API |
| **ikuriye-android** | `ANDROID` | Android app (`NexxAuth`) | org auth endpoints with `X-Client-Id`; user JWT for `/users/me/*` |

The Android client sends `X-Client-Id` on every call (register/login/refresh and
the org API calls). Nexxauth resolves the organisation **through the client** for
the auth endpoints, so no organisation id appears in those payloads. The org API
calls (`/users/me/*`) are addressed by the organisation's **numeric id** —
`/organisations/{organisationId}/...` — never a slug. The app still configures
`NEXXAUTH_ORG_SLUG` and is pending the same numeric-id migration.

## 3. Login & register (email or phone + password)

Both happen **directly against Nexxauth** from the Android app — the backend never
sees passwords.

- **Login** — `POST /master/auth/login` with `{ identifier, identifierType?, authType: "PASSWORD", password }`.
  The app sends whatever the user typed in the single "Email or Phone" field.
  If it looks like an email it sends `identifierType: "EMAIL"`, if it looks like
  a phone `identifierType: "PHONE"`; otherwise it omits the type and Nexxauth
  tries every login-enabled identifier in order (username → email → phone).
- **Register** — `POST /master/auth/register` with `{ firstName, lastName, email?, phone?, password }`.
  The organisation's sign-in-identifier config decides whether email/phone are
  required; at least one must be provided.
- **Refresh** — `POST /master/auth/refresh` with the opaque refresh token. Nexxauth
  rotates it (single-use) and treats a replayed token as theft, revoking the whole
  session family — so the app stores it securely and never reuses an old one.

The response is an `OrgAuthResponse`: `accessToken` (RS256 org JWT) + `refreshToken`
+ `user`. The app persists both and sends the access token to the backend.

## 4. Verifying tokens on the backend`NexxauthJwtVerifier` verifies tokens **offline against the organisation's public
key, configured at deploy time** via `NEXXAUTH_PUBLIC_KEY` (the `publicKey` value
— base64 DER SPKI, no PEM headers — from
`GET /organisations/{organisationId}/keys`). The backend makes **no runtime
fetch** of the keys endpoint. Every request with a `Bearer` token:

1. The RS256 signature is verified against the configured public key.
2. `iss == "nexxauth"` and `type == "org-access"` are required.
3. `NexxauthJwtAuthenticationFilter` maps the token's **`roles` claim** to local
   Spring authorities (`ROLE_DRIVER`, `ROLE_WORKER`, …) and authenticates the
   request.

**Key rotation:** because the key is baked in rather than fetched, rotating the
organisation's key requires updating `NEXXAUTH_PUBLIC_KEY` and restarting —
tokens signed with the previous key fail once the new key is deployed.

Because authorization is derived from the token, a role change in Nexxauth takes
effect for a user **as soon as they get a fresh token** — no local propagation
step. A local `users` row marked `DISABLED` additionally blocks the request even
with a still-valid token (the mirror of Nexxauth's `enabled` flag).

## 5. Permissions come from the token

Nexxauth ships **role names in the token, never permissions** (permissions are
resolved by Nexxauth server-side on its own API). For the backend GraphQL API:

- Coarse gating (`@PreAuthorize("hasRole('SUPER_ADMIN')")`, `hasAnyRole(…)`) is
  driven entirely by the token's roles via the filter above.
- When the token carries several roles (e.g. `["worker", "driver"]`), **all** of
  them become authorities; resolvers pick the most privileged one
  (`NexxauthRoles.primaryRole`) for single-role business logic.
- **Whenever the backend assigns a permission/role to a user, it also assigns it
  to the user in Nexxauth** (see below) — so the token reflects it immediately and
  the two systems can never disagree.

## 6. Admin user management — hand in hand with Nexxauth

`UserService` works against Nexxauth through `NexxauthClient` (SERVER client):

| GraphQL mutation | Local action | Nexxauth call |
|---|---|---|
| `syncUser` (client after login) | upsert the local profile mirror | `GET /organisations/{organisationId}/users/{id}` |
| `assignRole(input)` | assign role, mirror locally | `PATCH /organisations/{organisationId}/users/{id}` with `{ roles: [...] }` |
| `disableUser(userId)` | mark local `DISABLED` | `PATCH .../users/{id}` with `{ enabled: false }` |

**Users are NOT created by the backend.** Registration happens directly against
Nexxauth from the Android/web apps (`POST /auth/register`); the backend only
reads existing users, assigns roles to them, and disables them. `assignRole`
requires the user to already exist in Nexxauth — if only the local mirror is
missing, it is created from the Nexxauth profile.

**Role definitions are managed through the backend** (`RoleService` / SERVER
client) against the org API:

| GraphQL operation | Nexxauth call |
|---|---|
| `roles` (query) | `GET /organisations/{organisationId}/roles` |
| `createRole(input)` | `POST /organisations/{organisationId}/roles` |
| `updateRole(input)` | `PATCH /organisations/{organisationId}/roles/{roleId}` |
| `deleteRole(roleId)` | `DELETE /organisations/{organisationId}/roles/{roleId}` |

Role names must match the local enum lower-cased (`super_admin`, `admin`,
`customer`, `worker`, `driver`) so `@PreAuthorize` checks recognize them in user
tokens; `isDefault` on a role auto-assigns it to new registrations. Reads are
Admin/Super Admin, writes are Super Admin only.

Role names are kept identical in both systems: Nexxauth roles are the local enum
names lower-cased (`super_admin`, `admin`, `customer`, `worker`, `driver`),
mapped by `NexxauthRoles`. Provision those five roles once in the Nexxauth
organisation.

## 7. Local user id = Nexxauth user id

The org JWT's `sub` claim is the Nexxauth org-user id (a number). Since the
migration (`V19`), `users.id` **is** that number — there is no mapping table.

- All user-referencing columns (`packages.creator_id`, `transfers.*`,
  `notice_viewers.user_id`, …) were converted `UUID → BIGINT` in `V19`.
- The migration **refuses to run while `users` has rows**: existing Supabase-era
  users can't be mapped to Nexxauth ids. Run
  `.github/scripts/clear_supabase_auth_data.sql` first if you accept dropping the
  user-linked history (offices and standalone locations survive).
- The Android app treats user ids as opaque strings (GraphQL `ID`), so no client
  change was needed for the type switch.

## 8. What stays on Supabase

**File uploads only.** The Android app still uploads package media and profile
pictures to Supabase Storage (`SupaClient` installs only `Storage`; `SupaMedia`
creates signed URLs). Everything else is gone:

- GoTrue auth → Nexxauth (removed from both app and backend).
- Supabase Realtime for notices → removed; the notice feed now uses the existing
  30-second GraphQL polling fallback. The backend drops the `notice_viewers` RLS
  policies in `V19`.
- Supabase Admin API / JWKS verification on the backend → `NexxauthClient` /
  `NexxauthJwtVerifier` replace them.

## 9. Configuration & environment

Backend (`application.yml` / env):

```
NEXXAUTH_BASE_URL=https://auth.med.rw/master   # fixed base URL = origin + platform slug
NEXXAUTH_ORGANISATION_ID=<numeric org id from the console>
NEXXAUTH_PUBLIC_KEY=<base64 DER SPKI from GET /organisations/{id}/keys>
NEXXAUTH_CLIENT_ID=cli_<server client id>
NEXXAUTH_CLIENT_TOKEN=nx_<server static token>
```

Android (`secrets.properties` → `BuildConfig`):

```
NEXXAUTH_BASE_URL=https://auth.med.rw/master   # includes the platform slug
NEXXAUTH_CLIENT_ID=cli_<android client id>
NEXXAUTH_ORG_SLUG=<org slug>
SUPABASE_URL / SUPABASE_KEY                     # storage only
GRAPHQL_URL                                     # unchanged
```

## 10. Gotchas

- **Refresh tokens are single-use and rotated.** Never reuse one; if a refresh
  fails, stop and force a new login (Nexxauth revokes the session family on replay).
- **Forbidden/expired tokens**: the backend answers `401` with the
  `{ errors: { message, extensions: { code } } }` shape the app already handles.
- **Forgot-password is admin-managed.** Nexxauth has no self-service reset
  endpoint yet; the app's reset dialog now explains that an administrator sets a
  temporary password instead.
- **Email/phone updates** go through Nexxauth (`PATCH /users/me` for names);
  avatar URLs are kept locally (Supabase is upload-only, the backend doesn't
  store them).
- **Key rotation requires a deploy.** The org public key is baked in via
  `NEXXAUTH_PUBLIC_KEY` (no runtime fetch); after `POST /keys/rotate` update the
  env var and restart before old tokens expire.
- **The worker portal (Next.js) is still on Supabase** until it is migrated — it
  is out of scope for this pass.
