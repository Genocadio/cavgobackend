import assert from "node:assert/strict";
import { test } from "node:test";
import {
  ACTIVE_PACKAGE_STATUSES,
  isActivePackageStatus,
  isPackageStatus,
  normalizePackageStatuses,
} from "../metrics/packageStatus";

test("terminal package statuses are excluded from the active set", () => {
  assert.ok(ACTIVE_PACKAGE_STATUSES.length > 0);
  for (const terminal of ["DELIVERED", "COMPLETED", "CANCELLED"]) {
    assert.ok(!ACTIVE_PACKAGE_STATUSES.includes(terminal as any));
    assert.equal(isActivePackageStatus(terminal as any), false);
  }
  assert.equal(isActivePackageStatus("IN_TRANSIT"), true);
});

test("isPackageStatus only accepts known enum values", () => {
  assert.equal(isPackageStatus("PICKED_UP"), true);
  assert.equal(isPackageStatus("picked_up"), false);
  assert.equal(isPackageStatus("NOT_A_STATUS"), false);
  assert.equal(isPackageStatus(undefined), false);
});

test("normalizePackageStatuses drops invalid values and de-duplicates", () => {
  assert.deepEqual(normalizePackageStatuses(["CREATED", "BOGUS", "CREATED", "IN_TRANSIT"]), [
    "CREATED",
    "IN_TRANSIT",
  ]);
  assert.equal(normalizePackageStatuses([]), null);
  assert.equal(normalizePackageStatuses("CREATED"), null);
});
