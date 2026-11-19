import assert from "node:assert/strict";
import { test } from "node:test";
import {
  isRevenueBookingStatus,
  PACKAGE_DELIVERY_REVENUE,
  REVENUE_BOOKING_STATUSES,
  REVENUE_TRIP_STATUS,
} from "../metrics/revenue";

test("only earned booking statuses count as passenger revenue", () => {
  for (const status of REVENUE_BOOKING_STATUSES) {
    assert.equal(isRevenueBookingStatus(status), true);
  }
  for (const status of ["pending", "cancelled", "expired"]) {
    assert.equal(isRevenueBookingStatus(status), false);
  }
});

test("revenue is only earned on completed trips", () => {
  assert.equal(REVENUE_TRIP_STATUS, "completed");
});

test("package delivery revenue stays in its own zero-valued bucket", () => {
  assert.equal(PACKAGE_DELIVERY_REVENUE, 0);
});
