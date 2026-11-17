# CavGo Gateway — URL Schema

This document describes the URLs clients use today and how the live server
routes them. Verified against `server.nexxserve.dev` on 2026-11-17.

**Production origin is `https://api.gocavgo.com`.** The old
`https://api.med.rw/gocavgo` origin is retired (it returns HTTP 530) and must
not be used in new code. Authentication moved to Nexxauth at
`https://auth.nexxserve.dev/cavgo` — `auth.med.rw/master` is retired too.

## 1. Live topology

```
client
  │
  ▼
https://api.gocavgo.com                 nginx :443 (letsencrypt TLS)
  │
  ├─ /package-media/**, /profiles/**  → 127.0.0.1:9000  MinIO (S3, presigned GET)
  │
  └─ everything else                  → 127.0.0.1:8084  cavgo-gateway
                                          (container port 8080)
                                          │
                                          ├─ Eureka services (lb://)
                                          └─ file-backed routes (routes.json)

https://auth.nexxserve.dev/cavgo        Nexxauth (separate service/host)
```

The gateway is the single entry point for **API** traffic; object downloads
bypass it. Nexxauth is a separate identity host and is never proxied through
`api.gocavgo.com`.

## 2. Base URLs

| Environment | Base URL to the gateway |
|---|---|
| Local (docker compose) | `http://localhost:8080` |
| Local (default Spring profile) | `http://localhost:8070` |
| Production (canonical) | `https://api.gocavgo.com` |

The nginx vhost also accepts two legacy shapes for old clients, but they are
**not** the canonical form and should not be used in new code:

| Legacy request | nginx rewrites it to |
|---|---|
| `/gocavgo/<path>` | `/<path>` |
| `/gocavgo/api/<path>` | `/<path>` |
| `/api/(main\|navig\|maps\|book\|ride\|ikuriye)/<path>` | `/<namespace>/<path>` |

These rewrites are server-level and re-run location matching, so they work for
both gateway routes and the MinIO bucket paths. For example,
`https://api.gocavgo.com/gocavgo/main/vehicles` and
`https://api.gocavgo.com/main/vehicles` reach the same gateway route, but only
the second is canonical.

Historical note: the old production URL was `https://api.med.rw/gocavgo`.
nginx used to strip `/gocavgo` there; today it does the same stripping on
`api.gocavgo.com` for backwards compatibility.

## 3. What nginx does on `api.gocavgo.com`

- Listens on 443 with TLS (certbot-managed) and 301-redirects HTTP → HTTPS.
- Per-IP rate limit: 20 r/s, burst 40 (`api_rl`), 50 concurrent connections
  (`api_conn`); the shared zones live in `/etc/nginx/conf.d/rate-limit.conf`.
- WebSocket upgrades use the shared `$connection_upgrade` map from
  `/etc/nginx/conf.d/websocket-map.conf`.
- `client_max_body_size 100m` — required for photo/video uploads. The backend
  allows the same 100 MB (`spring.servlet.multipart.max-file-size` /
  `max-request-size`); a request above the nginx cap is rejected with HTTP 413.
- `proxy_read_timeout` / `proxy_send_timeout` = 360 s and `proxy_buffering off`
  for SSE, long-running responses, and streaming.
- CORS is emitted by the gateway (`CORS_ALLOWED_ORIGINS`, reflected origin), not
  by ikuriyebackend. nginx simply forwards the preflight; multipart uploads with
  `Authorization` work from the deployed web origin.

### MinIO download path

```
location ~ ^/(package-media|profiles)/ {
    proxy_pass http://127.0.0.1:9000;
    proxy_set_header Host $host;      # SigV4 signature uses the public hostname
    proxy_buffering off;
}
```

Buckets `package-media` and `profiles` are created by ikuriyebackend on boot.
Uploads go through the gateway (`/ikuriye/api/files/upload`); the backend
returns presigned GET URLs built with `MINIO_PUBLIC_ENDPOINT`, which is
`https://api.gocavgo.com` in production. nginx forwards those URLs to MinIO
**without rewriting the bucket path**, so the SigV4 signature stays valid.
MinIO's S3 port 9000 itself is loopback-only; only the console on 9001 is
bound to all interfaces.

## 4. Path schema

```
<BASE_URL> / <namespace> / <resource-path>
```

- **`<namespace>`** — short keyword identifying the backend service
  (`main`, `navig`, `maps`, `book`, `ride`, `ikuriye`, plus the deployment-only
  `agg`, `ussd`, `mqtt` routes described below).
- **`<resource-path>`** — the rest of the path, passed on to the backend.

The canonical form has **no `/api` prefix**. The `/api/...` prefix that used to
exist was an older gateway convention; the `/api` inside `/ikuriye/api/files/...`
is different — it belongs to ikuriyebackend's own controller mapping.

## 5. Route table

Static routes in `Cavgogateway/src/main/resources/application*.yml`:

| Gateway path | Backend service | What the backend receives |
|---|---|---|
| `/main/**` | `cavgomain` | `/main/**` (unchanged) |
| `/navig/events/**` | `cavgotrips` (Go) | `/events/**` — SSE, 5-min timeout, matched before `/navig/**` |
| `/navig/**` | `cavgotrips` (Go) | `/routes`, `/trips`, `/locations`, … |
| `/maps/**` | `Navigation` (Java) | `/api/**` (rewritten, e.g. `/maps/routes/calculate` → `/api/routes/calculate`) |
| `/book/**` | `cavgobooking` (Go) | `/…` |
| `/ride/**` | `ridehail` | `/…` |
| `/ikuriye/api/**` | `ikuriyebackend` | `/api/**` — kept as-is, this is ikuriyebackend's native controller path |
| `/ikuriye/graphql` (WS) | `ikuriyebackend` | WebSocket upgrade, matched before the HTTP route |
| `/ikuriye/graphql` (HTTP) | `ikuriyebackend` | GraphQL queries/mutations |
| `/ikuriye/**` | `ikuriyebackend` | `/api/**` (rewritten; the two specific routes above win) |

Additional routes on the live deployment come from a mounted file
(`APP_ROUTES_FILE=/opt/cavgo-data/routes.json`, from
`~/cavgobackend/gateway-config/routes.json` on the server). They exist because
these services do not register with Eureka:

| Gateway path | Target | Notes |
|---|---|---|
| `/agg/graphql` (WS), `/agg/**` (HTTP) | `adminaggregate:4000` | Rewrites `/agg/(.*)` → `/$1`; external: `https://api.gocavgo.com/agg/graphql` |
| `/ussd/**` | `cavgoussd:6050` | Rewrites to `/api/ussd/**`; telco callback |
| `/mqtt/**`, `/api/mqtt/**` | `cavgomqt:6090` | MQTT bridge HTTP API (passed through unchanged) |

File routes are checked before YAML routes, so keep ids unique. See section 9
for editing them without a restart.

### Why `/maps` rewrites to `/api` but `/navig` just strips

The gateway strips the namespace so each backend receives its own native paths:

- `cavgotrips`, `cavgobooking`, `ridehail` serve paths **without** `/api`
  (`/routes`, `/trips`, …) → `StripPrefix=1`.
- `cavgomain` serves `/main/...` → no strip at all.
- `Navigation` serves paths **with** `/api` (`/api/routes/calculate`, `/api/gps`,
  …) → `RewritePath` replaces `/maps/` with `/api/`.
- `ikuriyebackend` serves `/api/files/...` → `/ikuriye/{rest}` is rewritten to
  `/api/{rest}`; the explicit `/ikuriye/api/**` route preserves that path
  verbatim because generated file URLs depend on it.

## 6. Breaking change: old vs new

| Old (not canonical) | New |
|---|---|
| `/api/main/**` | `/main/**` |
| `/api/navig/events/**` | `/navig/events/**` |
| `/api/navig/**` | `/navig/**` |
| `/api/maps/**` | `/maps/**` |
| `/api/book/**` | `/book/**` |
| `/api/ride/**` | `/ride/**` |
| `/api/ikuriye/**` | `/ikuriye/**` (rewritten to `/api/**`) |

nginx still accepts most old shapes via the compatibility rewrites in section
2, but clients should use the canonical `/<namespace>/...` paths. No new client
should be written with a `med.rw` host.

## 7. Production configuration values

These are the values actually set on the live `cavgo-ikuriye` container:

| Variable | Value |
|---|---|
| `NEXXAUTH_BASE_URL` | `https://auth.nexxserve.dev/cavgo` |
| `BACKEND_BASE_URL` | `https://api.gocavgo.com/ikuriye` |
| `MINIO_ENDPOINT` | `http://core-minio:9000` (internal) |
| `MINIO_PUBLIC_ENDPOINT` | `https://api.gocavgo.com` (client-facing) |
| `STORAGE_PROVIDER` | `auto` (MinIO → Supabase → local) |

Client defaults match:

- `twese-web`: `NEXT_PUBLIC_API_URL=https://api.gocavgo.com`,
  `NEXT_PUBLIC_NEXXAUTH_BASE_URL=https://auth.nexxserve.dev/cavgo`.
- `twese-android`: `CAVGO_BASE_URL=https://api.gocavgo.com`,
  `NEXXAUTH_BASE_URL=https://auth.nexxserve.dev/cavgo`.

Old values to remove from configs and docs:

- `https://api.med.rw/gocavgo/...` → `https://api.gocavgo.com/...`
- `https://auth.med.rw/master` → `https://auth.nexxserve.dev/cavgo`

## 8. Examples

```bash
# Core (cavgomain)
curl https://api.gocavgo.com/main/vehicles

# Trips data (cavgotrips)
curl https://api.gocavgo.com/navig/routes
curl https://api.gocavgo.com/navig/trips

# Navigation service (Java) — route calculation
curl -X POST https://api.gocavgo.com/maps/routes/calculate \
  -H "Content-Type: application/json" \
  -d '{"waypoints":[...]}'

# Navigation — GPS updates, trip state, reset
curl -X POST https://api.gocavgo.com/maps/gps ...
curl https://api.gocavgo.com/maps/trips
curl -X DELETE https://api.gocavgo.com/maps/reset

# SSE live tracking (cavgotrips)
curl -N -H "Accept: text/event-stream" \
  https://api.gocavgo.com/navig/events/{uuid}

# Bookings, ridehail, USSD
curl https://api.gocavgo.com/book/...
curl https://api.gocavgo.com/ride/...
curl https://api.gocavgo.com/ussd/callback

# Aggregator GraphQL (file-backed route)
curl https://api.gocavgo.com/agg/graphql -H "Content-Type: application/json" \
  -d '{"query":"..."}'

# Ikuriye (delivery) — REST + GraphQL
curl https://api.gocavgo.com/ikuriye/api/files/local/...
curl https://api.gocavgo.com/ikuriye/graphql \
  -H "Content-Type: application/json" -d '{"query":"..."}'

# MinIO object (presigned URL, no /ikuriye prefix)
curl https://api.gocavgo.com/package-media/photos/<file>
```

Local equivalents use `http://localhost:8080` with the same paths.

## 9. Reference: gateway config and runtime routes

Routes are defined in `Cavgogateway/src/main/resources/`:
`application.yml` (default profile) and `application-docker.yml` (docker
profile, identical routes). The gateway resolves `lb://<ServiceName>` against
Eureka (`eurekacavgo`); services register as `cavgomain`, `cavgotrips`,
`cavgobooking`, `ridehail`, `Navigation`, `ikuriyebackend`.

The gateway also has a **file-backed route repository**
(`FileRouteDefinitionRepository`), configurable with `APP_ROUTES_FILE` /
`app.routes.file`. Live it is `/opt/cavgo-data/routes.json`; in compose the
mount is `./gateway-config:/opt/cavgo-data`. Those routes are loaded before the
YAML routes and can be changed without a restart:

```bash
# On the server, after editing ~/cavgobackend/gateway-config/routes.json:
curl -X POST http://127.0.0.1:8084/actuator/gateway/refresh

# Inspect
curl http://127.0.0.1:8084/actuator/gateway/routes
curl http://127.0.0.1:8084/actuator/gateway/routedefinitions
```

Actuator CRUD (`POST /actuator/gateway/routes/{id}`, `DELETE ...`) writes
through to the file, so changes survive restarts. Predicate/filter args use
normalized keys (`pattern` for `Path`, `parts` for `StripPrefix`, `_genkey_0`
for positional filter args in JSON). Protect `/actuator/**` if the gateway port
is ever exposed publicly — today only nginx on 443 is public.

## 10. Quick verification on the server

```bash
# Effective nginx config (includes the live vhost and its rewrites)
sudo nginx -T | grep -E 'server_name|client_max_body_size|location|rewrite'

# Rate-limit zones and the WebSocket map
cat /etc/nginx/conf.d/rate-limit.conf /etc/nginx/conf.d/websocket-map.conf

# Routes the gateway is actually serving
curl -s http://127.0.0.1:8084/actuator/gateway/routedefinitions

# Public checks
curl -I https://api.gocavgo.com/navig/routes
curl -X OPTIONS https://api.gocavgo.com/ikuriye/api/files/upload \
  -H 'Origin: https://twese.gocavgo.com' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization'
```
