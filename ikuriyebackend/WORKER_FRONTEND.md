# CavGo Worker Frontend — Flow & Integration Guide

> **Status: LIVE INTEGRATION**
> The `cav-go-worker-portal/` app is wired directly to the CavGo backend over GraphQL (`/graphql` + WebSocket `/graphql`).
> There is **no demo data** — every screen reads from the real API using the authenticated worker's Supabase JWT, and every action calls a real mutation.
> The package manager is **bun** (`bun install`, `bun dev`, `bun build`).
>
> **Realtime split (no backend polling):**
> - **Notices** → workers subscribe to **Supabase Realtime** on their own `notice_viewers` rows (see §6).
> - **New packages / offers / transfers** → the backend **GraphQL subscription** `newPackageTransfer` over WebSocket.
> - A slow 60 s background sync (operational board only, never notices) is kept as a safety net for cross-worker changes neither realtime channel fires for.
> The data layer lives in `cav-go-worker-portal/lib/` (`client.ts`, `api.ts`, `auth.tsx`, `store.tsx`, `realtime.ts`, `realtime-notices.ts`) and is described in [Section 6 — How the Integration Works](#6--how-the-integration-works).

---

## Table of Contents

1. [What This Doc Is](#1--what-this-doc-is)
2. [Worker Capabilities](#2--worker-capabilities)
3. [The Flows (step-by-step)](#3--the-flows-step-by-step)
   - [Flow 1 — Accept a package / transfer](#flow-1--accept-a-package--transfer)
   - [Flow 2 — Create a package (registered or anonymous sender)](#flow-2--create-a-package-registered-or-anonymous-sender)
   - [Flow 3 — Assign a driver](#flow-3--assign-a-driver)
   - [Flow 4 — Accept a transfer created by a driver](#flow-4--accept-a-transfer-created-by-a-driver)
   - [Flow 5 — Deliver to the end customer](#flow-5--deliver-to-the-end-customer)
4. [GraphQL Reference (aligned to the backend schema)](#4--graphql-reference-aligned-to-the-backend-schema)
5. [Frontend Structure & Demo Data](#5--frontend-structure--demo-data)
6. [Switching to the Real Backend](#6--switching-to-the-real-backend)

---

## 1. What This Doc Is

This document is the contract between the **CavGo backend** (`com.gocavgo.delivary`, GraphQL) and the **worker frontend**. It describes:

- every operation a **Worker** can perform,
- the exact GraphQL mutations/queries the real backend exposes,
- the state machines the frontend must respect,
- and how the demo-data scaffold is organised so it can later be pointed at the real API without restructuring.

**Roles on the platform:** `CUSTOMER`, `WORKER`, `DRIVER`, `ADMIN`, `SUPER_ADMIN`. This app is for **WORKER** users (office employees). Workers can create packages, accept transfers, assign drivers, advance statuses, initiate/confirm delivery, and manage CONFIRM-mode transfers.

---

## 2. Worker Capabilities

| Capability | Backend operation | When |
|---|---|---|
| See available packages | `availablePackages` | Packages with status `CREATED` not claimed by an open transfer |
| Accept a transfer (AUTO/SECURE) | `acceptTransfer` | Transfer `PENDING`; acceptor type `WORKER` or `BOTH` |
| Request a transfer (CONFIRM) | `acceptTransfer` | Transfer `PENDING`, rule `CONFIRM`; sets status `REQUESTED` |
| Confirm / reject a CONFIRM request | `confirmTransfer` / `rejectTransfer` | Only the transfer **owner** (creator) |
| Create a package | `createPackage` | Sender auto-filled for CUSTOMER; **WORKER must supply sender** (registered `userId` or anonymous name+phone) |
| Assign a driver | `assignDriver` | Assigner must be current custodian or office staff; driver must be `ONLINE` |
| Advance status | `updatePackageStatus` | Per state machine; actor must be current custodian or office staff |
| Initiate delivery | `initiateDelivery` | From `IN_TRANSIT` (OPEN/FIXED_ROUTE) / `READY_FOR_COLLECTION` (FIXED_ROUTE) → `PENDING_CONFIRMATION`; generates 6-digit delivery code |
| Confirm delivery | `confirmDelivery` | From `PENDING_CONFIRMATION`; needs delivery code → `DELIVERED` |
| Regenerate delivery code | `regenerateDeliveryCode` | While `PENDING_CONFIRMATION` |
| Track packages | `packageByTrackingCode` | Public, no auth |
| Notifications | `myNotices`, `unreadNoticeCount` | Event feed; live via Supabase Realtime on `notice_viewers` (real backend) |

---

## 3. The Flows (step-by-step)

> Each flow lists the **real GraphQL operations**. The demo scaffold implements these exact operations against demo data, so the UI logic does not change when you go live.

### Flow 1 — Accept a package / transfer

A customer (or driver) created a package with a transfer. The worker accepts the **transfer** (never single packages).

```
1. Worker opens the app → availablePackages / transfersByStatus(PENDING)
2. Sees transfer with ruleType:
   - AUTO    → button "Accept"           → acceptTransfer(input: { transferId })
   - SECURE  → button "Accept" + code box → acceptTransfer(input: { transferId, transferCode })
   - CONFIRM → button "Request"           → acceptTransfer(input: { transferId })  → status REQUESTED
3. On success:
   - AUTO/SECURE: transfer → DONE; every package → ACCEPTED; worker becomes custodian (role WORKER)
   - CONFIRM: transfer → REQUESTED (requestorId = worker); owner must confirmTransfer
```

**Custody on accept:** `SENDER → WORKER`. Package status `CREATED → ACCEPTED` (in-flight packages keep their status and simply swap custodian).

### Flow 2 — Create a package (registered or anonymous sender)

A worker logs an incoming shipment. **WORKER must always provide the sender explicitly** (unlike CUSTOMER, no auto-fill).

| Sender case | How to send it |
|---|---|
| Registered user | `sender: { role: SENDER, userId: "<uuid>", name?, phone? }` — name/phone enriched from profile if missing |
| Anonymous (walk-in) | `sender: { role: SENDER, name: "Jane Doe", phone: "+1..." }` — no `userId`, stored as-is |

**Initial package state (WORKER creator):** `ORIGIN_OFFICE`, custodian `WORKER`. Custody is proven by the custodian records — no token to manage.

**Optional:** pass `transferRuleType` (`AUTO`/`SECURE`/`CONFIRM`) to auto-create a transfer with this package; for `SECURE` a transfer code is returned once.

```graphql
mutation CreatePackage($input: CreatePackageInput!) {
  createPackage(input: $input) {
    deliveryPackage { id trackingCode status deliveryType }
    transfer { id ruleType status transferCode }  # null unless transferRuleType provided
  }
}
```

### Flow 3 — Assign a driver

The package is at the office (`ORIGIN_OFFICE` for FIXED_ROUTE, or `ACCEPTED` for OPEN). A worker assigns an online driver.

```
1. assignDriver(input: { packageId, driverId, assignedBy: <worker's own id — set by backend>, notes })
   - The assigner must be the current custodian or trusted office staff (WORKER/ADMIN/SUPER_ADMIN)
2. Result:
   - FIXED_ROUTE → status ORIGIN_OFFICE → ASSIGNED_DRIVER
   - OPEN → no status change; driver added as custodian
   - Custody: WORKER → DRIVER
3. Driver then advances: IN_TRANSIT → ... (driver app)
```

### Flow 4 — Accept a transfer created by a driver

A driver grouped packages into a transfer (e.g., hand-off at the office). Worker accepts it exactly like Flow 1 — same `acceptTransfer` mutation. The `acceptorType` on the transfer decides who may accept (`WORKER` / `DRIVER` / `BOTH`).

### Flow 5 — Deliver to the end customer

The package is in transit (OPEN) or ready at the destination office (FIXED_ROUTE). The worker (as current custodian) runs the delivery leg:

```
1. initiateDelivery(input: { packageId })
   → package → PENDING_CONFIRMATION
   → returns deliveryCode (6 digits)
   → REAL BACKEND: code is published ONLY to sender/receiver via notice payload
     (custodians never receive it); client reads it from the notice feed / Realtime
   → DEMO: the code is shown in the demo notice + on screen (demo simplification)
2. Receiver presents the code → confirmDelivery(input: { packageId, deliveryCode })
   → package → DELIVERED (custody → RECEIVER)
3. updatePackageStatus(input: { packageId, status: COMPLETED, ... })
   → package → COMPLETED (code-less final step)
```

**Constraints enforced by the backend (mirrored in demo):**
- `PENDING_CONFIRMATION` / `DELIVERED` cannot be set via `updatePackageStatus` — dedicated mutations only.
- Delivery code is single-use, expires after 7 days; regenerate via `regenerateDeliveryCode` while `PENDING_CONFIRMATION`.

---

## 4. GraphQL Reference (aligned to the backend schema)

### Queries

```graphql
# Worker home: my active + available packages
query MyPackages($status: PackageStatus, $order: SortOrder, $page: Int, $size: Int) {
  myPackages(status: $status, order: $order, page: $page, size: $size) {
    items { id trackingCode deliveryType status creatorId companyId tripId
            custodians { userId name phone role } people { role userId name phone }
            locations { type latitude longitude placeName placeId officeId }
            details { category description fragile weight length width height declaredValue }
            events { eventType description createdAt } custody { fromEntity toEntity timestamp notes }
            transfers { id ruleType acceptorType status } createdAt updatedAt }
    totalCount totalPages currentPage
  }
}

query AvailablePackages { availablePackages { items { id trackingCode deliveryType status people { name phone } } } }
query PackageByTrackingCode($code: String!) { packageByTrackingCode(code: $code) { id trackingCode status events { eventType description createdAt } } }
query MyTransfers { myTransfers { id creatorId ruleType acceptorType matchCompanyId matchUserId requestorId status packages { packageId } createdAt } }
query TransfersByStatus($status: TransferStatus!) { transfersByStatus(status: $status) { id creatorId ruleType acceptorType matchCompanyId matchUserId requestorId status packages { packageId } } }
query MyNotices { myNotices { id resourceType resourceId eventType title message payload viewer { id readAt deliveredAt } createdAt } }
query UnreadNoticeCount { unreadNoticeCount }
query SearchUsers($query: String, $role: Role, $companyId: ID) { searchUsers(query: $query, role: $role, companyId: $companyId) { id firstName lastName phone role } }
```

### Mutations

```graphql
mutation AcceptTransfer($input: AcceptTransferInput!) {
  acceptTransfer(input: $input) {
    transfer { id ruleType acceptorType status requestorId }
    acceptedPackages { deliveryPackage { id trackingCode status custodians { userId role } } }
  }
}

mutation CreatePackage($input: CreatePackageInput!) {
  createPackage(input: $input) { deliveryPackage { id trackingCode status } transfer { id ruleType status transferCode } }
}

mutation AssignDriver($input: AssignDriverInput!) {
  assignDriver(input: $input) { id status custodians { userId name phone role } }
}

mutation UpdatePackageStatus($input: UpdatePackageStatusInput!) {
  updatePackageStatus(input: $input) { id status }
}

mutation InitiateDelivery($input: InitiateDeliveryInput!) {
  initiateDelivery(input: $input) { deliveryPackage { id status } deliveryCode }
}

mutation ConfirmDelivery($input: ConfirmDeliveryInput!) {
  confirmDelivery(input: $input) { id status }
}

mutation RegenerateDeliveryCode($input: RegenerateDeliveryCodeInput!) {
  regenerateDeliveryCode(input: $input) { deliveryPackage { id status } deliveryCode }
}

mutation ConfirmTransfer($transferId: ID!) { confirmTransfer(transferId: $transferId) { id status } }
mutation RejectTransfer($transferId: ID!) { rejectTransfer(transferId: $transferId) { id status } }
```

> **Note on auth:** in the real backend the authenticated worker's id is read from the JWT — the `actorId`/`assignedBy` fields you see in inputs are **overridden by the backend**, so the frontend should not send them (the demo API mimics this).

### Enums (must match backend exactly)

```
PackageStatus: CREATED, ACCEPTED, PICKED_UP, IN_TRANSIT, PENDING_CONFIRMATION, DELIVERED,
               COMPLETED, CANCELLED, ORIGIN_OFFICE, ASSIGNED_DRIVER, DESTINATION_OFFICE, READY_FOR_COLLECTION
DeliveryType: OPEN, FIXED_ROUTE
TransferRuleType: AUTO, SECURE, CONFIRM
TransferAcceptorType: WORKER, DRIVER, BOTH
TransferStatus: PENDING, REQUESTED, DONE, CANCELED
CustodianRole: WORKER, DRIVER, OFFICE, RECEIVER
PersonRole: SENDER, RECEIVER
LocationType: ORIGIN, DESTINATION
Role: SUPER_ADMIN, ADMIN, CUSTOMER, WORKER, DRIVER
SortOrder: ASC, DESC
```

### Status machines (validated by backend, mirrored in `frontend/src/lib/demo-api.ts`)

**OPEN:** `CREATED → ACCEPTED → PICKED_UP → IN_TRANSIT → PENDING_CONFIRMATION → DELIVERED → COMPLETED` (cancel anytime before terminal)

**FIXED_ROUTE (via office):** `CREATED → ORIGIN_OFFICE → ASSIGNED_DRIVER → IN_TRANSIT → DESTINATION_OFFICE → READY_FOR_COLLECTION → PENDING_CONFIRMATION → DELIVERED → COMPLETED`
**FIXED_ROUTE (direct delivery):** `CREATED → ORIGIN_OFFICE → ASSIGNED_DRIVER → IN_TRANSIT → PENDING_CONFIRMATION → DELIVERED → COMPLETED`

---

## 5. Frontend Structure & Demo Data

```
frontend/
├── app/
│   ├── layout.tsx            # App shell: sidebar + topbar + DemoApiProvider
│   ├── page.tsx              # Dashboard (stats, quick actions, recent notices)
│   ├── packages/page.tsx     # Package list (Available / My packages / All + filters)
│   ├── packages/[id]/page.tsx# Package detail: info, timeline, custody, action panel
│   ├── transfers/page.tsx    # Transfers: accept (AUTO/SECURE/CONFIRM), manage mine
│   ├── create/page.tsx       # Create package (registered / anonymous sender, optional transfer)
│   └── notices/page.tsx      # Notice feed (read/unread)
├── components/               # AppShell, StatusBadge, StatCard, Timeline, Toast, PackageActionPanel
└── lib/
    ├── types.ts              # TS types mirroring backend GraphQL types (1:1 field names)
    ├── demo-data.ts          # Seed data: worker, drivers, customers, packages, transfers, notices
    ├── demo-api.ts           # Mock API — same signatures as the GraphQL operations above
    └── store.tsx             # Stateful store (in-memory + localStorage), React context provider
```

### How the demo data works

- All data lives in **`lib/demo-data.ts`** as plain JSON-shaped objects typed by `lib/types.ts`.
- `lib/demo-api.ts` mutates the store exactly the way the backend would: validates state-machine transitions, generates delivery codes, updates custody/events, and appends **notices** (including the delivery code inside the `PACKAGE_DELIVERY_INITIATED` payload, like the real backend).
- The store is persisted to `localStorage` so refreshing keeps your demo state; a **“Reset demo data”** button restores the seed.
- A mock **“current worker”** is baked in (`demoWorker`). Switching to the real backend means replacing the auth context, not the screens.

**Demo-specific simplifications (documented, not a backend feature):**
1. The delivery code is shown on-screen and in the demo notice — the real backend only delivers it to sender/receiver via the notice feed + Supabase Realtime.
2. No pagination latency / network simulation beyond a tiny artificial delay.

---

## 6. How the Integration Works

The worker portal (`cav-go-worker-portal/`) is a Next.js app whose data layer talks to the CavGo backend in `src/main/`.

### Data layer (`cav-go-worker-portal/lib/`)

| File | Role |
|---|---|
| `client.ts` | Minimal fetch GraphQL client. Posts to `NEXT_PUBLIC_API_URL/graphql` with `Authorization: Bearer <supabase jwt>`; normalizes GraphQL/HTTP errors into `ApiError`. |
| `api.ts` | All typed operations (queries + mutations) and `toPackageItem()` mapping from backend `DeliveryPackage` to the UI view model. |
| `auth.tsx` | `AuthProvider` — Supabase GoTrue email/password sign-in (`/auth/v1/token?grant_type=password`) or pasted access token. Persists `{access, refresh, exp}` to `localStorage` and **auto-refreshes the access token**: proactively ~5 min before JWT expiry (mirrors the Android `SupaAuth.observeSession`) and on any backend 401 (single-flight `grant_type=refresh_token`, single retry in `client.ts`). Network-blip refreshes keep the session; a revoked refresh token signs the user out. Then `syncUser` + `myProfile` bootstrap. |
| `store.tsx` | `WorkspaceProvider` — loads `myPackages`, `availablePackages`, `transfersByStatus`, `myTransfers`, `myNotices`, `unreadNoticeCount`, `searchUsers(DRIVER)`, `offices`; exposes every mutation; subscribes to `newPackageTransfer` (GraphQL WS) and to `notice_viewers` (Supabase Realtime); 60 s background sync for the operational board only. |
| `realtime.ts` | Minimal `graphql-transport-ws` client for the backend `newPackageTransfer` subscription. Best-effort — the slow sync covers delivery when unavailable. |
| `realtime-notices.ts` | **Supabase Realtime** subscription on `notice_viewers` filtered by `user_id=eq.<uid>`. INSERT → refetch the notice feed + unread badge; UPDATE → sync read state across devices. Requires the Supabase dashboard setup below. |
| `types.ts` | Types mirroring the backend GraphQL schema. |
| `status.ts` | Status labels/colors, workspace groups, and the per-package action derivation from the backend state machine. |

### Auth & identity
- The backend reads the user + role from the JWT; inputs never include `actorId`/`assignedBy` (the resolvers override them).
- **There are no backend login endpoints.** The worker signs in on **Supabase GoTrue** directly (email/password → `POST /auth/v1/token?grant_type=password`); the backend only *validates* the resulting JWT (JWKS) and looks the user up via the Admin API inside `syncUser`. See `SupaAuth.kt` in the Android app for the equivalent SDK-based flow.
- First login calls `syncUser` so a local `users` row exists, then `myProfile`. Accounts must have the `WORKER` or `DRIVER` role to use the console.
- **Token refresh:** password sign-in stores the refresh token alongside the access token. The access token is refreshed automatically ~5 minutes before it expires (JWT `exp` claim, 5-min safety-net interval) and on any `401`/`EXPIRED_TOKEN` from the backend — the GraphQL client retries the request once with the fresh token. Pasted tokens carry no refresh token, so they expire to the login screen. A transient network failure during refresh keeps the session; only a rejected/revoked refresh token signs the user out.
- Delivery codes and SECURE transfer codes returned by mutations are stored in `localStorage` (per package / per transfer) so a worker can use them later; they are consumed after use, matching the backend's single-use semantics.
- **Custody is enforced by identity, not tokens:** status transitions and driver assignment require the actor to be the **current custodian** (or trusted office staff — WORKER/ADMIN/SUPER_ADMIN).

### Env vars (see `cav-go-worker-portal/.env.example`)
```
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_SUPABASE_URL=https://your-project.supabase.co
NEXT_PUBLIC_SUPABASE_ANON_KEY=your-anon-key
```

### Realtime (how the worker portal stays live)

**Notices — Supabase Realtime (no backend polling).** The backend writes a `notice_viewers` row for every recipient whenever a notice is published. The worker portal subscribes directly to its own rows:

```sql
-- one-time Supabase dashboard setup
-- 1) add notice_viewers to the supabase_realtime publication (Dashboard → Database → Replication)
alter publication supabase_realtime add table notice_viewers;

-- 2) RLS so a worker can only see their own rows
create policy "own notices" on notice_viewers
  for select using (user_id = auth.uid());

-- 3) optional: needed for DELETE events
ALTER TABLE notice_viewers REPLICA IDENTITY FULL;
```

**Packages / offers / transfers — backend GraphQL subscription.** `lib/realtime.ts` opens a WebSocket to `NEXT_PUBLIC_API_URL/graphql` using the `graphql-transport-ws` subprotocol, authenticates with the same Supabase JWT, and subscribes to `newPackageTransfer`. New package+transfer events trigger a toast and refresh of the operational board.

**Safety net.** A 60 s background sync refetches only the operational board (never notices) for changes neither realtime channel fires for (e.g. another worker accepting a transfer you are watching).

### Backend contract notes for the portal
- Worker packages come from `myPackages` (status `CREATED` globally + packages where the worker is custodian).
- Accepting custody always goes through a transfer: `acceptTransfer` (AUTO/SECURE/CONFIRM) or a claim (creates an AUTO transfer + accepts) for bare `CREATED` packages.
- `DELIVERED`/`PENDING_CONFIRMATION` are only set via `confirmDelivery`/`initiateDelivery` (the backend rejects them in `updatePackageStatus`).
- Delivery codes and transfer codes are one-time; the mutation response shows them once.
- Transfers work for **mid-route** handoffs too: accepting a transfer for an in-flight package keeps its status and just swaps the custodian.

---

*Last updated: reflects backend state after the Transfer System (AUTO/SECURE/CONFIRM), `acceptTransfer` unification, delivery-code flow, notice feed, and removal of the handover token (custody proven by current-custodian identity checks instead).*
