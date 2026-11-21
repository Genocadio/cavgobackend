import assert from "node:assert/strict";
import { test } from "node:test";

process.env.DATABASE_URL ||= "postgres://postgres:postgres@localhost:5432/adminaggregate_test";

async function loadService() {
  const module = await import("../services/syncStatusService");
  return module.buildPackageSyncStatus;
}

const now = Date.parse("2026-11-21T12:00:00.000Z");

test("empty package table has no freshness and is not stale", async () => {
  const build = await loadService();
  const status = build(undefined, now, 3600);

  assert.equal(status.lastSyncedAt, null);
  assert.equal(status.packageCount, 0);
  assert.equal(status.ageSeconds, null);
  assert.equal(status.stale, false);
  assert.equal(status.staleThresholdSeconds, 3600);
});

test("recent package snapshots are fresh", async () => {
  const build = await loadService();
  const status = build(
    { lastSyncedAt: new Date("2026-11-21T11:35:00.000Z"), packageCount: "8" },
    now,
    3600
  );

  assert.equal(status.lastSyncedAt, "2026-11-21T11:35:00.000Z");
  assert.equal(status.packageCount, 8);
  assert.equal(status.ageSeconds, 1500);
  assert.equal(status.stale, false);
});

test("snapshots older than the threshold are marked stale", async () => {
  const build = await loadService();
  const status = build(
    { lastSyncedAt: "2026-11-20T10:00:00.000Z", packageCount: 8 },
    now,
    3600
  );

  assert.equal(status.ageSeconds, 93_600);
  assert.equal(status.stale, true);
});

test("an invalid timestamp fails closed without marking stale", async () => {
  const build = await loadService();
  const status = build({ lastSyncedAt: "not-a-date", packageCount: 8 }, now, 3600);

  assert.equal(status.lastSyncedAt, null);
  assert.equal(status.ageSeconds, null);
  assert.equal(status.stale, false);
});
