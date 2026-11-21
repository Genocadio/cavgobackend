# Company ID spaces

There are **two different company identifiers** in CavGo. They are not
interchangeable, and comparing one against the other is the most common cause of
"packages exist but the company dashboard is empty".

## Quick reference

| | `company_id` | `company_ref_id` |
|---|---|---|
| Type | UUID string | Numeric id (rendered as text in adminaggregate) |
| Source | Local ikuriyebackend office/user profile (`userService.getCompanyIdForUser`) | cavgomain `companies.id` (also the id used by cavgotrips and `/main/staff/me`) |
| Example | `9f1c…e2a4` | `1` |
| Stored in ikuriyebackend | `packages.company_id`, `package_carrier_legs.company_id` | `packages.company_ref_id`, `package_carrier_legs.company_ref_id` |
| Stored in adminaggregate | `packages.company_id`, `cars.company_id`, `drivers.company_id` (text) | `packages.company_ref_id`, `package_carrier_legs.company_ref_id` (text) |
| Used for company package reporting | **No** | **Yes** — this is the join key |

Both columns are stored as text in adminaggregate to avoid bigint/JavaScript
precision surprises. Values are still numeric strings for `company_ref_id`.

## Where the numeric id comes from

- **cavgomain** owns it. `POST /internal/api/users/sync` returns a `companyId`
  field; ikuriyebackend reads it through `CavgomainSyncClient.getCompanyIdForUser`.
- **cavgotrips** uses the same id (`cavgo_trips.company_id`, e.g. `34` belongs to
  company `1`). When a driver takes custody, `PackageService.attachDriverContext`
  copies `trip.getCompanyId()` into `company_ref_id`.
- **cavgocompany** receives it from `/main/staff/me` as a number, converts it to
  a string, and sends it as the `companyId` argument of the
  `companyPackageActivity` GraphQL query. adminaggregate compares that argument
  against `packages.company_ref_id` / `package_carrier_legs.company_ref_id`.

```text
cavgomain companies.id = 1
        │
        ├─ /main/staff/me ──► cavgocompany (companyId "1")
        ├─ /internal/api/users/sync ──► ikuriyebackend (companyRefId 1)
        └─ cavgo_trips.company_id ──► ikuriyebackend (companyRefId 1)
                                          │
                                          └─ POST /internal/packages ──► adminaggregate
                                                                             │
                                                                             └─ companyPackageActivity(companyId: "1")
```

## Rules

1. **Never filter company package reports on `company_id`.** The company UI
   sends a numeric id; it will never match a UUID.
2. **Never compare `company_id` to `company_ref_id`**, even after casting to
   text. Different ID spaces.
3. A package created by a `WORKER`, `DRIVER`, or `FLEET_MANAGER` gets
   `company_ref_id` from the cavgomain user sync. A `CUSTOMER`-created package
   may legitimately have no company until a driver/trip attaches it.
4. A null `company_ref_id` means the package is unscoped and will not appear in
   any company report. Backfill it from cavgomain membership or a linked trip,
   then replay the snapshot (see below).
5. Adding a new field? Reuse `company_ref_id` for anything company-scoped and
   numeric. Reserve `company_id` for the legacy office UUID only.

## Replaying after a backfill

adminaggregate only stores a projection. Fixing `ikuriye.packages.company_ref_id`
does not update it automatically. Re-POST each affected snapshot to
`POST /internal/packages` with the `x-internal-api-key` header; the endpoint
upserts by `packageId` and replaces carrier legs wholesale.

## Monitoring

- `GET http://cavgo-ikuriye:6100/actuator/health` — reports
  `packageAggregatorSync` as DOWN when the aggregator env vars are missing or
  the last N pushes failed (`AGGREGATOR_SYNC_FAILURE_THRESHOLD`, default 5).
- `GET http://cavgo-adminaggregate:4000/health` — reports `max(synced_at)` as
  `packageSync.lastSyncedAt`, plus `ageSeconds` and a `stale` flag controlled by
  `PACKAGE_SYNC_STALE_SECONDS` (default 86400).
