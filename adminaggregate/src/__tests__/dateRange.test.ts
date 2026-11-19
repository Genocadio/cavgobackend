import assert from "node:assert/strict";
import { test } from "node:test";
import { resolveDateRange, resolveTimestampRange, toMilliseconds } from "../metrics/dateRange";

test("resolveDateRange defaults to today", () => {
  const { start, end } = resolveDateRange();
  assert.equal(start.getTime(), new Date().setHours(0, 0, 0, 0));
  assert.equal(end.getTime(), new Date().setHours(23, 59, 59, 999));
});

test("resolveDateRange with a start only runs from that day through now", () => {
  const before = Date.now();
  const { start, end } = resolveDateRange("2026-01-05");
  assert.equal(start.getTime(), new Date(2026, 0, 5, 0, 0, 0, 0).getTime());
  assert.ok(end.getTime() >= before);
});

test("resolveDateRange with both bounds includes the whole end day", () => {
  const { start, end } = resolveDateRange("2026-01-05", "2026-01-07");
  assert.equal(start.getTime(), new Date(2026, 0, 5, 0, 0, 0, 0).getTime());
  assert.equal(end.getTime(), new Date(2026, 0, 7, 23, 59, 59, 999).getTime());
});

test("resolveDateRange with an end only starts at the epoch", () => {
  const { start, end } = resolveDateRange(undefined, "2026-01-07");
  assert.equal(start.getTime(), 0);
  assert.equal(end.getTime(), new Date(2026, 0, 7, 23, 59, 59, 999).getTime());
});

test("toMilliseconds accepts both seconds and milliseconds", () => {
  assert.equal(toMilliseconds(1_700_000_000), 1_700_000_000_000);
  assert.equal(toMilliseconds(1_700_000_000_000), 1_700_000_000_000);
});

test("resolveTimestampRange converts second-based bounds", () => {
  const { start, end } = resolveTimestampRange(1_700_000_000, 1_700_086_400);
  assert.equal(start.getTime(), 1_700_000_000_000);
  assert.equal(end.getTime(), 1_700_086_400_000);
});
