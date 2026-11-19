import assert from "node:assert/strict";
import { test } from "node:test";

process.env.DATABASE_URL ||= "postgres://postgres:postgres@localhost:5432/adminaggregate_test";

async function loadMapper() {
  const module = await import("../services/packageSyncService");
  return module.mapPackageSnapshot;
}

const validPayload = {
  packageId: "pkg-1",
  trackingCode: "CG-123",
  deliveryType: "OPEN",
  packageOrigin: "PRIVATE",
  status: "CREATED",
  createdAt: "2026-11-19T08:00:00Z",
  updatedAt: "2026-11-19T08:00:00Z",
  carrierLegs: [],
};

test("valid package snapshot maps to the local projection", async () => {
  const map = await loadMapper();
  const result = map({ ...validPayload, companyId: "company-a", companyRefId: "42", tripId: "7" });
  assert.equal(result.id, "pkg-1");
  assert.equal(result.companyId, "company-a");
  assert.equal(result.companyRefId, "42");
  assert.equal(result.tripId, "7");
  assert.equal(result.carrierLegs.length, 0);
  assert.equal(result.createdAt, new Date("2026-11-19T08:00:00Z").getTime());
});

test("carrier legs inherit the package id", async () => {
  const map = await loadMapper();
  const result = map({
    ...validPayload,
    carrierLegs: [
      {
        id: "leg-1",
        driverId: "driver-9",
        tripId: "7",
        companyRefId: "42",
        vehicleId: "5",
        linkedAt: "2026-11-19T08:05:00Z",
        fromStatus: "CREATED",
        toStatus: "PICKED_UP",
      },
    ],
  });
  assert.equal(result.carrierLegs.length, 1);
  assert.equal(result.carrierLegs[0]?.packageId, "pkg-1");
  assert.equal(result.carrierLegs[0]?.driverId, "driver-9");
  assert.equal(result.carrierLegs[0]?.toStatus, "PICKED_UP");
});

test("invalid snapshots fail closed", async () => {
  const map = await loadMapper();
  assert.throws(() => map({ ...validPayload, packageId: undefined }), /packageId is required/);
  assert.throws(() => map({ ...validPayload, deliveryType: "PRIVATE" }), /Invalid deliveryType/);
  assert.throws(() => map({ ...validPayload, packageOrigin: "FLEET_ROUTE" }), /Invalid packageOrigin/);
  assert.throws(() => map({ ...validPayload, status: "UNKNOWN" }), /Invalid status/);
  assert.throws(() => map({ ...validPayload, carrierLegs: "nope" }), /carrierLegs must be an array/);
  assert.throws(
    () => map({ ...validPayload, carrierLegs: [{ id: "leg-x", driverId: "d", linkedAt: "not-a-date" }] }),
    /linkedAt is not a valid timestamp/
  );
});
