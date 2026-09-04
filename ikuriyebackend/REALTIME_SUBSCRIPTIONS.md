# Supabase Realtime Subscriptions – Integration Guide

This document explains **what to subscribe to** in Supabase Realtime, **what data to expect**, and **what frontend actions to take** — organised by user role (Customer, Worker, Driver).

---

## Overview

The platform writes one `notices` row per event and one `notice_viewers` row per recipient.  
The **frontend subscribes only to `notice_viewers`** with a `user_id` filter, then fetches the parent `notices` row on receipt.

```
Supabase Realtime channel
       │
       ▼
┌─────────────────────────────────────┐
│  notice_viewers (filter: user_id)   │   ← Subscribed via Supabase JS client
│                                     │
│  • notice_id  → FK to notices       │
│  • user_id    = auth.uid()          │
│  • delivered_at / read_at           │
│  • created_at                       │
└─────────────────────────────────────┘
       │  on INSERT
       ▼
Fetch parent `notices` row by `notice_id`
       │
       ▼
Parse `payload` (JSONB) for resource details
Update UI (notification badge, timeline, list refresh)
```

### Schema (relevant columns)

**`notices` table** (one row per event):
| Column | Type | Example |
|---|---|---|
| `id` | UUID | `...` |
| `resource_type` | VARCHAR | `PACKAGE` or `TRANSFER` |
| `resource_id` | UUID | The package or transfer ID |
| `event_type` | VARCHAR | `PACKAGE_DELIVERED`, `TRANSFER_DONE`, etc. |
| `actor_id` | UUID | Who caused the event (nullable) |
| `title` | VARCHAR | `"Package delivered"` |
| `message` | TEXT | `"Package CAV-X7K2B9JQ has been delivered."` |
| `payload` | JSONB | Structured before/after state (see below) |
| `created_at` | TIMESTAMP | `2026-07-28T10:15:00Z` |

**`notice_viewers` table** (one row per recipient):
| Column | Type | Example |
|---|---|---|
| `id` | UUID | The viewer row ID (use this for `markNoticeRead`) |
| `notice_id` | UUID | FK → `notices.id` |
| `user_id` | UUID | The recipient |
| `delivered_at` | TIMESTAMP | Set to `now()` on creation |
| `read_at` | TIMESTAMP | `null` initially, set on `markNoticeRead` mutation |
| `created_at` | TIMESTAMP | Same as `delivered_at` |

---

## Subscription Setup

```typescript
import { createClient } from '@supabase/supabase-js'

const supabase = createClient(SUPABASE_URL, SUPABASE_ANON_KEY)

// Subscribe to the current user's notice viewer rows
const channel = supabase
  .channel('notice-viewers')
  .on(
    'postgres_changes',
    {
      event: 'INSERT',          // only new rows — the frontend also needs an initial fetch
      schema: 'public',
      table: 'notice_viewers',
      filter: `user_id=eq.${userId}`,
    },
    async (payload) => {
      const viewerRow = payload.new
      // 1. Fetch the parent notice
      const { data: notice } = await supabase
        .from('notices')
        .select('*')
        .eq('id', viewerRow.notice_id)
        .single()

      // 2. Parse the payload JSONB
      const event = JSON.parse(notice.payload)

      // 3. Update UI based on event.eventType
      handleNoticeEvent(notice, viewerRow)
    }
  )
  .subscribe()
```

> **Initial fetch:** On app load, query `myNotices` (GraphQL) or the `notice_viewers` table directly to get existing unread notices, then subscribe for live INSERTs.

---

## 1. Customer

A Customer is a **sender or receiver** of packages. They do not handle packages operationally — they create delivery requests and follow their progress.

### What they see

- Packages where their `userId` or `phone` appears in `package_people` (SENDER or RECEIVER).
- They are **never custodians** — they do not see transfer-related notices.

### Relevant events

| Event | Why the Customer should care | Frontend action |
|---|---|---|
| `PACKAGE_CREATED` | Their package was successfully created. | Show confirmation, display tracking code. |
| `PACKAGE_ACCEPTED` | A worker has accepted the package at the office. | Update package status badge → "Accepted". |
| `PACKAGE_PICKED_UP` | Package has been physically picked up. | Update status badge → "Picked up". |
| `PACKAGE_IN_TRANSIT` | Package is moving toward the destination. | Update status badge → "In transit". Show on map. |
| `PACKAGE_ORIGIN_OFFICE` | Package arrived at the origin office (FIXED_ROUTE). | Update status badge → "At origin office". |
| `PACKAGE_ASSIGNED_DRIVER` | A driver has been assigned (FIXED_ROUTE). | Show driver info (name, phone). |
| `PACKAGE_DESTINATION_OFFICE` | Package arrived at destination office (FIXED_ROUTE). | Update status badge, notify "ready for collection soon". |
| `PACKAGE_READY_FOR_COLLECTION` | Receiver can pick up (FIXED_ROUTE). | Show "Ready for collection". |
| `PACKAGE_DELIVERY_INITIATED` | Driver reached destination and delivery is awaiting confirmation. | ⚠️ Show the **delivery code** from `payload.deliveryCode` (sender/receiver only). Prompt for it at handover. |
| `PACKAGE_DELIVERED` | Package has been delivered to the receiver. | Celebrate! Show delivery confirmation. |
| `PACKAGE_COMPLETED` | Delivery is fully complete. | Show "Completed", optionally allow rating/feedback. |
| `PACKAGE_CANCELLED` | Package was cancelled. | Show cancellation reason, refund info if applicable. |

### Customer does NOT receive

- `PACKAGE_CUSTODIAN_ASSIGNED` / `PACKAGE_CUSTODIAN_REMOVED` — internal operations.
- `PACKAGE_CUSTODY_TRANSFERRED` — internal handover tracking.
- Any `TRANSFER_*` event — transfers are worker/driver operations.

### GraphQL queries the Customer uses

```graphql
# Main feed
query {
  myPackages(order: DESC) {
    items { id trackingCode status deliveryType createdAt }
    totalCount
  }
}

# Tracking detail
query {
  packageByTrackingCode(code: "CAV-X7K2B9JQ") {
    id trackingCode status deliveryType
    people { role name phone }
    locations { type placeName }
    custodians { userId name phone role }
    events { eventType description createdAt }
    details { category weight fragile }
  }
}
```

---

## 2. Worker

A Worker is an operational employee who handles packages at offices, accepts incoming packages, and manages transfers within their company.

### What they see

- Packages with status `CREATED` (available to accept).
- Packages where they are the **current custodian** (`package_custodians`).
- Transfers they created (as `creatorId`).
- Transfers where they are the `matchUserId` or their `companyId` matches.

### Relevant events

#### Package events

| Event | Why the Worker should care | Frontend action |
|---|---|---|
| `PACKAGE_CREATED` | New package available in the pool. | Show in "Available packages" list — badge or subtle highlight. |
| `PACKAGE_ACCEPTED` | A package they were watching was accepted (maybe by someone else). | Remove from available list, or update status if they are the custodian. |
| `PACKAGE_ORIGIN_OFFICE` | Package has arrived at an office (FIXED_ROUTE). | If the office is theirs, trigger handover workflow. |
| `PACKAGE_ASSIGNED_DRIVER` | A driver is assigned at their office. | Prepare handover to driver. |
| `PACKAGE_DESTINATION_OFFICE` | Package arrived at destination office. | If the office is theirs, prepare for collection or onward routing. |
| `PACKAGE_READY_FOR_COLLECTION` | Receiver can collect. | Mark as ready, notify receiver via app. |
| `PACKAGE_COMPLETED` | Package cycle finished. | Archive / remove from active queue. |
| `PACKAGE_CANCELLED` | Package was cancelled. | Remove from queue, log reason. |
| `PACKAGE_CUSTODIAN_ASSIGNED` | Someone was assigned custody of a package. | If it's not them, they should check if their packages were affected. |
| `PACKAGE_CUSTODIAN_REMOVED` | They were removed as custodian (e.g. package handed off). | Remove package from their active list. ⚠️ **Action required — they may need to acknowledge.** |
| `PACKAGE_CUSTODY_TRANSFERRED` | Custody was formally transferred. | Update handover status, remove from active custody list. |

#### Transfer events

| Event | Why the Worker should care | Frontend action |
|---|---|---|
| `TRANSFER_PENDING` | A transfer was created (by them or someone in their company). | Show in "My transfers" list. If created by someone else, show as available to accept. |
| `TRANSFER_REQUESTED` | Someone requested their transfer (CONFIRM mode). | ⚠️ **Needs action** — show approval/rejection prompt. |
| `TRANSFER_DONE` | A transfer they were involved in was completed. | Remove from active transfers, update package statuses. |
| `TRANSFER_CANCELED` | A transfer was cancelled. | Remove from active transfers. Packages go back to the pool. |
| `TRANSFER_PACKAGE_ADDED` | A package was added to one of their transfers. | Show updated package list in the transfer detail. |

### GraphQL queries the Worker uses

```graphql
# Packages available to accept (status = CREATED)
query {
  availablePackages { items { id trackingCode status people { name phone } origin destination } }
}

# My current active packages
query {
  packagesByUser(userId: $myId) { items { id trackingCode status custodians { name role } } }
}

# My transfers
query {
  myTransfers { id status ruleType acceptorType packages { packageId } }
}

# Transfers I can accept (by role match)
query {
  transfersByStatus(status: PENDING) { id creatorId matchCompanyId ... }
}
```

### Transfer acceptance logic (frontend)

```
User sees a PENDING transfer
        │
        ▼
Check transfer.acceptorType:
  WORKER  → only workers can accept
  DRIVER  → only drivers can accept
  BOTH    → either can accept
        │
        ▼
Check transfer.ruleType:
  AUTO    → "Accept" button → acceptTransfer(transferId, null)
  SECURE  → "Accept" button + code input → acceptTransfer(transferId, code)
  CONFIRM → "Request" button → acceptTransfer(transferId, null) → sets to REQUESTED
        │
        ▼
On success → refetch transfer and package state
On TRANSFER_DONE event → auto-refresh affected package statuses
```

---

## 3. Driver

A Driver transports packages between locations. They receive packages from workers, transport them, and hand them off to destination offices or deliver directly to receivers.

### What they see

- Packages where they are the **current custodian** with role `DRIVER`.
- Transfers they created.
- Transfers where `acceptorType` is `DRIVER` or `BOTH` and they are the `matchUserId` or their company matches.

### Relevant events

#### Package events

| Event | Why the Driver should care | Frontend action |
|---|---|---|
| `PACKAGE_ACCEPTED` | A worker accepted a package at the office. | Show in available packages list. |
| `PACKAGE_CUSTODIAN_ASSIGNED` | They were assigned as a driver by a worker. | ⚠️ **Notification** — a new package is waiting for them. Show route/collection info. |
| `PACKAGE_PICKED_UP` | They picked up a package (status transition). | Update status badge → "Picked up". |
| `PACKAGE_IN_TRANSIT` | They marked the package as in transit. | Update status, show navigation/ETA. |
| `PACKAGE_DELIVERED` | They delivered to the receiver. | Update status → "Delivered", prompt for confirmation. |
| `PACKAGE_COMPLETED` | Package cycle finished. | Archive, show completion summary. |
| `PACKAGE_CANCELLED` | Cancelled while in their custody. | Remove from queue, return instructions. |
| `PACKAGE_ORIGIN_OFFICE` | Package ready at origin office for pickup. | Show pickup location, navigate there. |
| `PACKAGE_ASSIGNED_DRIVER` | They were assigned to a FIXED_ROUTE package (same as CUSTODIAN_ASSIGNED). | Show in route plan. |
| `PACKAGE_DESTINATION_OFFICE` | Package dropped at destination office (part of their route). | Mark drop-off complete, continue route. |
| `PACKAGE_READY_FOR_COLLECTION` | Package ready for receiver collection. | No further action — auto-notify status. |
| `PACKAGE_CUSTODIAN_REMOVED` | They were removed as custodian (handed off, package reassigned). | Remove from active route. ⚠️ **Action required.** |
| `PACKAGE_CUSTODY_TRANSFERRED` | Formal custody transfer recorded. | Update handover status. |

#### Transfer events

| Event | Why the Driver should care | Frontend action |
|---|---|---|
| `TRANSFER_PENDING` | A transfer is available (if acceptorType allows drivers). | Show in "Available transfers" for acceptance. |
| `TRANSFER_REQUESTED` | They requested a CONFIRM transfer, waiting for worker approval. | Show "Pending approval" state. |
| `TRANSFER_DONE` | Transfer completed — packages auto-accepted into their custody. | Fetch new packages, add to route. |
| `TRANSFER_CANCELED` | Transfer cancelled. | Remove from active transfers. |
| `TRANSFER_PACKAGE_ADDED` | A package was added to their transfer. | Update transfer detail, adjust route. |

### Driver's typical workflow (frontend)

```
1. On app open → query myNotices for unread count
2. Subscribe to notice_viewers for live INSERTs
3. Show notification badge with unread count
4. On PACKAGE_CUSTODIAN_ASSIGNED:
   → Show push notification
   → Add package to route list
5. After calling initiateDelivery (their own action — no notice received):
   → Update status badge → "Awaiting confirmation"
   → Prompt receiver for the delivery code and call confirmDelivery
6. On PACKAGE_DELIVERED (by them):
   → Show confirmation screen
   → Prompt for receiver signature/photo
7. On TRANSFER_DONE:
   → Fetch newly acquired packages
   → Recalculate route
8. On PACKAGE_CUSTODIAN_REMOVED:
   → Alert with "Package reassigned" message
   → Remove from active route
```

---

## Recipient Policy (who gets each notice)

Recipients are resolved **per event** — context-aware, never "everyone". The actor who performed the action is always excluded, and only the **current** custodian is notified (past custodians were already informed by `PACKAGE_CUSTODIAN_REMOVED` at handoff).

| Event group | Recipients |
|---|---|
| Package status (`PACKAGE_CREATED`, `PACKAGE_ACCEPTED`, `PACKAGE_PICKED_UP`, `PACKAGE_IN_TRANSIT`, `PACKAGE_DELIVERED`, `PACKAGE_COMPLETED`, `PACKAGE_CANCELLED`, `PACKAGE_ORIGIN_OFFICE`, `PACKAGE_ASSIGNED_DRIVER`, `PACKAGE_DESTINATION_OFFICE`, `PACKAGE_READY_FOR_COLLECTION`) | People (sender + receiver) + current custodian − actor |
| `PACKAGE_DELIVERY_INITIATED` | People (sender + receiver) only — custodians never receive the code or this status notice |
| Custody ops (`PACKAGE_CUSTODIAN_ASSIGNED`, `PACKAGE_CUSTODY_TRANSFERRED`) | Current custodian only — never customers |
| `PACKAGE_CUSTODIAN_REMOVED` | The removed user only (targeted) |
| `TRANSFER_PENDING` | Creator + `matchUserId` (specific target) — the open pool is served live by the `newPackageTransfer` WebSocket subscription |
| `TRANSFER_REQUESTED` | Transfer owner only (the one who must approve) |
| `TRANSFER_DONE` | Requestor (the custodian who was waiting) + creator − actor |
| `TRANSFER_CANCELED` | Creator + requestor + `matchUserId` |
| `TRANSFER_PACKAGE_ADDED` | Transfer parties + package people + current custodian − actor |

---

## Event Payload Format

Each `notice.payload` (JSONB column) follows a consistent structure. Parse it to extract resource IDs, status transitions, and metadata — no additional fetch needed for basic UI updates.

### Package event payload

```json
{
  "resourceType": "PACKAGE",
  "resourceId": "uuid-of-package",
  "trackingCode": "CAV-X7K2B9JQ",
  "previousStatus": "IN_TRANSIT",
  "newStatus": "DELIVERED",
  "actorId": "uuid-of-user-who-did-it",
  "changedAt": "2026-07-28T10:15:00Z"
}
```

### PACKAGE_DELIVERY_INITIATED payload

```json
{
  "resourceType": "PACKAGE",
  "resourceId": "uuid-of-package",
  "trackingCode": "CAV-X7K2B9JQ",
  "previousStatus": "IN_TRANSIT",
  "newStatus": "PENDING_CONFIRMATION",
  "deliveryCode": "483920",
  "actorId": "uuid-of-driver-who-initiated-it",
  "changedAt": "2026-07-28T10:15:00Z"
}
```

> **Note:** `deliveryCode` is the one-time 6-digit code the sender/receiver presents in the `confirmDelivery` mutation. It is only present in this event payload — never in `PACKAGE_DELIVERED`. It can be regenerated (`regenerateDeliveryCode`) while status stays `PENDING_CONFIRMATION`.

### Transfer event payload

```json
{
  "resourceType": "TRANSFER",
  "resourceId": "uuid-of-transfer",
  "previousStatus": "PENDING",
  "newStatus": "DONE",
  "ruleType": "CONFIRM",
  "actorId": "uuid-of-user-who-did-it",
  "changedAt": "2026-07-28T10:15:00Z"
}
```

### TRANSFER_PACKAGE_ADDED payload

```json
{
  "resourceType": "TRANSFER",
  "resourceId": "uuid-of-transfer",
  "packageId": "uuid-of-added-package",
  "trackingCode": "CAV-X7K2B9JQ",
  "addedBy": "uuid-of-user-who-added-it",
  "changedAt": "2026-07-28T10:15:00Z"
}
```

---

## Frontend Integration Checklist

### On app startup

1. **Query `myNotices`** (GraphQL) to get existing unread notices.
2. **Query `unreadNoticeCount`** for the notification badge.
3. **Subscribe to Supabase Realtime** on `notice_viewers` with `user_id=eq.<uid>`.

### On receiving a Realtime INSERT on `notice_viewers`

1. Fetch the parent `notices` row by `viewer.notice_id` (GraphQL or Supabase REST).
2. Parse `notice.payload` to determine:
   - Which package/transfer changed → update local cache.
   - The new status → update any visible status badge.
   - The actor → decide whether to show a push notification (skip if `actorId === currentUserId`).
3. If `viewer.readAt` is `null`, show a notification badge on:
   - The app icon (total `unreadNoticeCount`).
   - The specific package/transfer card in the list.
4. **Do not auto-fetch** the full package/transfer graph — the payload already has the status. The user can tap through for details.

### On marking a notice as read

Use the backend `markNoticeRead` mutation — this is the canonical path for both the worker portal and the Android app (it writes `read_at` through the backend's service connection, so it works regardless of table-level RLS):

```graphql
mutation {
  markNoticeRead(viewerId: "uuid-of-the-notice-viewer-row") {
    id
    readAt
  }
}
```

- `viewerId` is the `id` of the `notice_viewers` row returned by `myNotices` (the `viewer.id` field) — **not** the `notice.id` and not the viewer row's `notice_id`.
- This sets `readAt` on the `notice_viewers` row.
- Update the local notification badge (`unreadNoticeCount -= 1`).
- Mark the specific card's notification dot as read.

**Direct Postgrest fallback (mobile):** writing `read_at` straight to the `notice_viewers` table via PostgREST also works, but only because of the UPDATE RLS policy added in `V18` (see below). Prefer the GraphQL mutation so read-state changes stay consistent with the backend schema and the `markNoticeRead` ownership check (a user can only mark their own rows read).

### Notification badge strategy

```typescript
// Badge = total unread count across all events
const { data } = await client.query({ query: UNREAD_NOTICE_COUNT })
setBadge(data.unreadNoticeCount)

// On each new Realtime INSERT:
setBadge(prev => prev + 1)

// On markNoticeRead:
setBadge(prev => Math.max(0, prev - 1))
```

---

## Quick Reference: Event ↔ Role Matrix

| Event | Customer | Worker | Driver |
|---|---|---|---|
| `PACKAGE_CREATED` | ✅ | ⚡ (available pool) | ❌ |
| `PACKAGE_ACCEPTED` | ✅ | ✅ | ✅ |
| `PACKAGE_PICKED_UP` | ✅ | ✅ | ✅ |
| `PACKAGE_IN_TRANSIT` | ✅ | ✅ | ✅ |
| `PACKAGE_DELIVERY_INITIATED` | ⚡ (show delivery code) | ❌ | ❌ |
| `PACKAGE_DELIVERED` | ✅ | ✅ | ✅ |
| `PACKAGE_COMPLETED` | ✅ | ✅ | ✅ |
| `PACKAGE_CANCELLED` | ✅ | ✅ | ✅ |
| `PACKAGE_ORIGIN_OFFICE` | ✅ | ✅ | ⚡ (navigate there) |
| `PACKAGE_ASSIGNED_DRIVER` | ✅ | ✅ | ⚡ (you're assigned) |
| `PACKAGE_DESTINATION_OFFICE` | ✅ | ✅ | ✅ |
| `PACKAGE_READY_FOR_COLLECTION` | ✅ | ✅ | ❌ |
| `PACKAGE_CUSTODIAN_ASSIGNED` | ❌ | ✅ | ⚡ (you're the custodian) |
| `PACKAGE_CUSTODIAN_REMOVED` | ❌ | ⚠️ | ⚠️ |
| `PACKAGE_CUSTODY_TRANSFERRED` | ❌ | ✅ | ✅ |
| `TRANSFER_PENDING` | ❌ | ✅ | ✅ |
| `TRANSFER_REQUESTED` | ❌ | ⚠️ (needs approval) | ✅ |
| `TRANSFER_DONE` | ❌ | ✅ | ⚡ (get packages) |
| `TRANSFER_CANCELED` | ❌ | ✅ | ✅ |
| `TRANSFER_PACKAGE_ADDED` | ❌ | ✅ | ✅ |

**Legend:** ✅ = status update / informational · ⚡ = needs attention / action recommended · ⚠️ = action required · ❌ = not delivered

---

## Supabase Realtime Channel Setup (Concrete)

```typescript
// Given: user is authenticated, we have auth.uid() as userId

// 1. Initial fetch
const { data: myViewers } = await supabase
  .from('notice_viewers')
  .select('*, notices(*)')
  .eq('user_id', userId)
  .order('created_at', { ascending: false })
  .limit(50)

// 2. Subscribe for live inserts
const channel = supabase
  .channel(`notices:${userId}`)
  .on(
    'postgres_changes',
    {
      event: 'INSERT',
      schema: 'public',
      table: 'notice_viewers',
      filter: `user_id=eq.${userId}`,
    },
    async (payload) => {
      const viewer = payload.new as NoticeViewer
      const { data: notice } = await supabase
        .from('notices')
        .select('*')
        .eq('id', viewer.notice_id)
        .single()

      if (!notice) return

      const event = JSON.parse(notice.payload)
      showNotification(notice.title, notice.message)
      updateLocalCache(event)
    }
  )
  .subscribe()

// 3. Cleanup on unmount
channel.unsubscribe()
```

### Database-level RLS

The migrations enable RLS on `notice_viewers` with two policies:

`V12__create_notice_tables.sql` — read access:

```sql
CREATE POLICY notice_viewers_select_own ON notice_viewers
  FOR SELECT
  USING (user_id = auth.uid());
```

`V18__notice_viewers_update_rls.sql` — write access (mark-as-read):

```sql
CREATE POLICY notice_viewers_update_own ON notice_viewers
  FOR UPDATE
  USING (user_id = auth.uid())
  WITH CHECK (user_id = auth.uid());
```

These ensure a user **can only see and update their own viewer rows** — even via direct Supabase API access:
- The `SELECT` policy guarantees a user only ever reads their own rows.
- The `UPDATE` policy (added in `V18`) lets a client write `read_at` on their own rows. Without it, direct Postgrest updates of `read_at` were **silently denied by RLS** — e.g. the Android app's read-marking never persisted, so read notices could reappear as unread (and delivery-confirmation popups could re-trigger) after an app restart.

> **Note:** both policies are created idempotently (guarded by `pg_policies` existence checks), so they are safe to re-run.

The `notices` table has no user-level RLS (it has no owner column), but it is never directly queried by the client — it's always accessed through the `notice_viewers` join or the `myNotices` GraphQL resolver (which is `@PreAuthorize("isAuthenticated()")`).

---

## GraphQL Queries for Notice Feed

```graphql
# Full notice feed with viewer state
query MyNotices {
  myNotices {
    id
    resourceType
    resourceId
    eventType
    actorId
    title
    message
    payload
    viewer {
      id
      noticeId
      userId
      deliveredAt
      readAt
    }
    createdAt
  }
}

# Badge count
query UnreadCount {
  unreadNoticeCount
}
```
