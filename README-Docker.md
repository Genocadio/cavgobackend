# CAVGO Microservices Docker Setup

This Docker Compose setup provides the full CAVGO stack: Java, Go and Node
services, PostgreSQL, RabbitMQ, Redis and Meilisearch, all on one `cavgo-network`
bridge so services reach each other by name.

## Services

| Service            | Port  | Stack          | Notes                                    |
| ------------------ | ----- | -------------- | ---------------------------------------- |
| RabbitMQ           | 5672  | image          | Management UI on 15672                   |
| Redis              | 6379  | image          | Cache for Navigation                     |
| PostgreSQL         | 5432  | image + init   | One database per service                 |
| Meilisearch        | 7700  | image          | Search provider (location/route/trip)    |
| Eurekacavgo        | 8761  | Java           | Service discovery                        |
| Cavgomain          | 6060  | Java           | Main service (companies, users, offices) |
| Cavgogateway       | 8080  | Java           | API gateway (runtime routes from file)   |
| Ridehail           | 6070  | Java           | Ride-hail service                        |
| Cavgotrips         | 6080  | Go             | Trips service                            |
| Cavgobooking       | 6030  | Go             | Booking service (Go)                     |
| Ikuriyebackend     | 6100  | Java           | Delivery / packages                      |
| Cavgomqt           | 6090  | Java           | MQTT GPS ingestion + Firebase            |
| Cavgoussd          | 6050  | Java           | USSD                                    |
| Adminaggregate     | 4000  | Node           | Admin aggregation                        |
| Navigation         | 6040  | Java           | Routing (OSRM)                           |

## Quick Start

1. **Copy the environment template and adjust values:**
   ```bash
   cp .env.example .env
   ```
   `MQTT_BROKER_URL`, `MQTT_USERNAME` and `MQTT_PASSWORD` are **required**
   (HiveMQ Cloud credentials) — the `cavgomqt` service refuses to start without
   them.

2. **Start all services:**
   ```bash
   docker-compose up -d
   ```

3. **View logs / stop:**
   ```bash
   docker-compose logs -f
   docker-compose down
   ```

## Databases

Each service gets its own database, created automatically on first start:

- `cavgomain`, `ridehail`, `cavgomqt`, `navigation`, `ikuriye`
- `cavgotrips`, `cavgobooks`
- `adminaggregate`

## Cross-service office sync (cavgomain ↔ ikuriyebackend)

Workers assigned to an office in cavgomain carry an `officeLocationId` that
ikuriyebackend stores on the worker's local profile (`worker_profiles.company_id`).

- **cavgomain → ikuriye (push):** when a non-DRIVER user is created or their
  office changes, cavgomain fire-and-forgets the new location to
  `POST /internal/api/users/office-sync`. Controlled by `IKURIYE_BASE_URL`.
- **ikuriye → cavgomain (pull):** when a WORKER authenticates, ikuriye pulls the
  user from cavgomain (`POST /internal/api/users/sync`) to extract the current
  `officeLocationId`; a DRIVER only fires a mirror sync. Controlled by
  `CAVGOMAIN_BASE_URL`. Both are optional — empty/absent disables that direction
  and the boot health report logs the pair as `SKIPPED`.

Both variables are wired in the compose files:

```yaml
# docker-compose.yml / docker-compose-hub.yml
cavgomain:
  environment:
    IKURIYE_BASE_URL: http://ikuriyebackend:6100
ikuriyebackend:
  environment:
    CAVGOMAIN_BASE_URL: http://cavgomain:6060
```

## Boot dependency health

Every Java service logs a connectivity report right after boot
(`ApplicationReadyEvent` — never blocks startup):

```
═══════════════ CAVGOMAIN BOOT DEPENDENCY HEALTH ═══════════════
  ✓ RabbitMQ      REACHABLE   (localhost:5672)
  ✓ PostgreSQL    REACHABLE
  ✗ NeXXauth      NOT REACHABLE (https://auth.med.rw/master)
  ✓ Ikuriye       SKIPPED — ikuriye.base-url not configured
══════════════════════════════════════════════════════════════
```

Unreachable dependencies are reported with a reason (connection refused, unknown
host, timeout), and an unconfigured optional peer logs `SKIPPED` with the config
key. Failures are warning/error log lines only — the app keeps booting.

## Environment variables

Full reference lives in [`.env.example`](./.env.example). The keys you will
most often change:

- `NEXXAUTH_BASE_URL`, `NEXXAUTH_PUBLIC_KEY`, `NEXXAUTH_CLIENT_ID`,
  `NEXXAUTH_CLIENT_TOKEN` — identity provider wiring (all Java services).
- `MQTT_BROKER_URL`, `MQTT_USERNAME`, `MQTT_PASSWORD` — HiveMQ (required).
- `IKURIYE_BASE_URL`, `CAVGOMAIN_BASE_URL` — office-sync direction (see above).
- Port overrides: `PORT_MAIN`, `PORT_GATEWAY`, `PORT_TRIPS`, `PORT_IKURIYE`, … (`PORT_*` in `.env.example`).

## GitHub Actions / CI

`.github/workflows/ci.yml` runs on push/PR to `main` (and manually via
`workflow_dispatch`). It uses change detection, so only affected services
build:

- **Java services** (cavgomain, ikuriyebackend, Eurekacavgo, Cavgogateway,
  ridehail, cavgomqt, ussdService, Navigation) — JDK 21 Gradle builds.
  `cavgomain` and `ikuriyebackend` run the full test suite (`./gradlew build`);
  the rest skip tests (`-x test`).
- **Go services** (cavgotrips, cavgoBooking) — `go build ./...`.
- **Node service** (adminaggregate) — `bun install` + `bun run build`.
- **Docker image validation** — every changed service's Dockerfile is built
  (no push) with `docker/build-push-action`.
- **Compose validation** — `docker compose config` for `docker-compose.yml`
  (dev) and `docker-compose-hub.yml` (hub), so a broken compose file fails CI.

## Docker Hub images

`docker-compose-hub.yml` runs published images (`genoyves/cavgo-system:*`) built
from each service Dockerfile instead of building locally, and points NeXXauth at
the hub organisation (`NEXXAUTH_HUB_*`). Uncomment `NEXXAUTH_HUB_*` in
`.env.example` to override the baked-in defaults.

## Troubleshooting

- Service not healthy → `docker-compose ps` and `docker-compose logs <service>`.
- Inspect the boot dependency health lines at the top of each service log.
- Hard reset: `docker-compose down -v && docker-compose up -d`
  (drops all local volumes).