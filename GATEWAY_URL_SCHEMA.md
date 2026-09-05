# CavGo Gateway — URL Schema

This document describes how URLs are structured for the CavGo backend and how
the Spring Cloud Gateway (`Cavgogateway`) routes them to the microservices.

## 1. Base URLs

The gateway is the single entry point for all client traffic. Everything after
the base URL is the **gateway path** described in section 2.

| Environment | Base URL to the gateway |
|---|---|
| Local (docker compose) | `http://localhost:8080` |
| Local (default Spring profile) | `http://localhost:8070` |
| Production (via nginx) | `https://api.med.rw/gocavgo` |

> **nginx note:** in production, nginx strips the `/gocavgo` prefix and forwards
> the rest to the gateway. So a public URL `https://api.med.rw/gocavgo/main/vehicles`
> arrives at the gateway as `/main/vehicles`. Anything written as
> `<BASE_URL>/<namespace>/...` below works with any of the base URLs above.

## 2. Path schema

```
<BASE_URL> / <namespace> / <resource-path>
```

- **`<namespace>`** — a short keyword identifying the backend service
  (`main`, `navig`, `maps`, `book`, `ride`, `ikuriye`).
- **`<resource-path>`** — the rest of the path, passed on to the backend.

There is **no `/api` prefix** anymore. The old schema was
`/api/<namespace>/...`; it has been replaced by `/<namespace>/...`
(see [section 4](#4-breaking-change-old-vs-new)).

## 3. Route table

| Gateway path | Backend service (Eureka) | What the backend receives |
|---|---|---|
| `/main/**` | `cavgomain` | `/main/**` (unchanged, e.g. `/main/vehicles`) |
| `/navig/events/**` | `cavgotrips` (Go) | `/events/**` — SSE, 5-min timeout, matched before `/navig/**` |
| `/navig/**` | `cavgotrips` (Go) | `/routes`, `/trips`, `/locations`, … |
| `/maps/**` | `Navigation` (Java) | `/api/**` (rewritten, e.g. `/maps/routes/calculate` → `/api/routes/calculate`) |
| `/book/**` | `cavgobooking` (Go) | `/…` |
| `/ride/**` | `ridehail` | `/…` |
| `/ikuriye/api/**` | `ikuriyebackend` | `/api/**` — **kept as-is**, it is the nginx contract (see below) |
| `/ikuriye/graphql` (WS) | `ikuriyebackend` | WebSocket upgrade, matched before the HTTP route |
| `/ikuriye/graphql` (HTTP) | `ikuriyebackend` | GraphQL queries/mutations |
| `/ikuriye/**` | `ikuriyebackend` | `/api/**` (rewritten; specific `/ikuriye/api/**` and `/ikuriye/graphql` routes win) |

### Why `/maps` rewrites to `/api` but `/navig` just strips

The gateway strips the namespace from the incoming path, but **the backend must
receive its own native paths**:

- `cavgotrips`, `cavgobooking`, `ridehail` serve paths **without** an `/api`
  prefix (`/routes`, `/trips`, …) → `StripPrefix=1` removes only the namespace.
- `cavgomain` serves `/main/...` → no strip at all, path forwarded unchanged.
- `Navigation` serves paths **with** an `/api` prefix (`/api/routes/calculate`,
  `/api/gps`, `/api/trips`, `/api/reset`) → the namespace is replaced by `/api`
  via `RewritePath`, since `StripPrefix` can only cut segments from the front.
- `ikuriyebackend` serves `/api/files/...` → `/ikuriye/{rest}` is rewritten to
  `/api/{rest}`.

### Why `/ikuriye/api/**` still contains `/api`

The ikuriye backend generates absolute file URLs itself, e.g.
`https://api.med.rw/gocavgo/ikuriye/api/files/local/...` (see
`ikuriyebackend/.../StorageService.java`). That path — including the `/api`
segment — is the **production nginx contract** and must keep working, so it is
left untouched. The `/api` there is part of ikuriyebackend's own controller
paths (`/api/files`), not the old gateway `/api` namespace.

## 4. Breaking change: old vs new

| Old (removed) | New |
|---|---|
| `/api/main/**` | `/main/**` |
| `/api/navig/events/**` | `/navig/events/**` |
| `/api/navig/**` | `/navig/**` |
| `/api/maps/**` | `/maps/**` |
| `/api/book/**` | `/book/**` |
| `/api/ride/**` | `/ride/**` |
| `/api/ikuriye/**` | `/ikuriye/**` (rewritten to `/api/**`) |

Clients must drop the `/api` segment:

- `EntriesCavgo` `RemoteDataManager`: `.../gocavgo/api/navig/` → `.../gocavgo/navig/`
- `EntriesCavgo` `NavigationRouteClient`: calls `/routes/calculate` with
  `NAVIGATION_BASE_URL=https://api.med.rw/gocavgo/maps`
- Any other client using `/api/navig`, `/api/main`, `/api/book`, `/api/ride`,
  `/api/maps`, or `/api/ikuriye` must switch to the new namespace paths.

## 5. Examples

```bash
# Core (cavgomain)
curl http://localhost:8080/main/vehicles

# Trips data (cavgotrips)
curl http://localhost:8080/navig/routes
curl http://localhost:8080/navig/trips

# Navigation service (Java) — route calculation
curl -X POST http://localhost:8080/maps/routes/calculate \
  -H "Content-Type: application/json" \
  -d '{"waypoints":[...]}'

# Navigation — GPS updates, trip state, reset
curl -X POST http://localhost:8080/maps/gps ...
curl http://localhost:8080/maps/trips
curl -X DELETE http://localhost:8080/maps/reset

# SSE live tracking (cavgotrips)
curl -N -H "Accept: text/event-stream" http://localhost:8080/navig/events/{uuid}

# Bookings, ridehail
curl http://localhost:8080/book/...
curl http://localhost:8080/ride/...

# Ikuriye (delivery) — REST + GraphQL
curl http://localhost:8080/ikuriye/api/files/...
curl http://localhost:8080/ikuriye/graphql -H "Content-Type: application/json" -d '{"query":"..."}'
```

## 6. Reference: gateway config

Routes are defined in `Cavgogateway/src/main/resources/`:
- `application.yml` (default profile)
- `application-docker.yml` (docker profile, identical routes)

The gateway resolves `lb://<ServiceName>` against Eureka (`eurekacavgo`).
Each backend registers with Eureka under its app name (`cavgomain`,
`cavgotrips`, `cavgobooking`, `ridehail`, `Navigation`, `ikuriyebackend`).

## 7. Runtime route management (no restart)

The gateway ships with a **file-backed route repository**
(`FileRouteDefinitionRepository`, replacing the default in-memory one). Routes
live in a JSON file — default `./routes.json`, override with
`APP_ROUTES_FILE` / `app.routes.file` (docker compose mounts
`./gateway-config/routes.json`).

This gives you three ways to change routes without restarting:

1. **Edit the file, then refresh** — edit `routes.json`, then:

   ```bash
   curl -X POST http://localhost:8080/actuator/gateway/refresh
   ```

2. **Actuator CRUD** (changes are written through to the file, so they also
   survive restarts):

   ```bash
   # List all runtime routes
   curl http://localhost:8080/actuator/gateway/routes

   # Add/update a route (body without the id — the id comes from the path)
   curl -X POST http://localhost:8080/actuator/gateway/routes/experimental \
     -H "Content-Type: application/json" \
     -d '{
       "uri": "lb://cavgomain",
       "predicates": [{"name": "Path", "args": {"pattern": "/experimental/**"}}],
       "filters": [],
       "metadata": {},
       "order": 0
     }'

   # Remove a route
   curl -X DELETE http://localhost:8080/actuator/gateway/routes/experimental

   # Refresh (safe to call after any change — harmless if already applied)
   curl -X POST http://localhost:8080/actuator/gateway/refresh
   ```

3. **Inspect** — `GET /actuator/gateway/routes` shows the file routes;
   `GET /actuator/gateway/routedefinitions` shows all definitions
   (YAML + file).

Example `routes.json`:

```json
[
  {
    "id": "experimental",
    "uri": "lb://cavgomain",
    "predicates": [
      { "name": "Path", "args": { "pattern": "/experimental/**" } }
    ],
    "filters": [],
    "metadata": {},
    "order": 0
  }
]
```

**Semantics to know:**

- File routes are checked **before** the YAML routes (the repository is ordered
  first), so a file route can shadow a YAML route with the same path — keep
  route ids unique to avoid confusion.
- Predicate/filter args use the **normalized keys** (`pattern` for `Path`,
  `parts` for `StripPrefix`), same as what `GET /actuator/gateway/routes`
  returns.
- The YAML routes in `application.yml` / `application-docker.yml` remain the
  baseline; the file only adds/overrides on top.
- The actuator endpoints are open (no auth) — in production, protect
  `/actuator/**` behind nginx or add a security filter if your deploy exposes
  the gateway port directly.

### Quick check after a refresh

```bash
curl http://localhost:8080/actuator/gateway/routes            # file routes
curl http://localhost:8080/actuator/gateway/routedefinitions  # all definitions
curl http://localhost:8080/actuator/gateway/globalfilters     # global filters
```