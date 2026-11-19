import assert from "node:assert/strict";
import { test } from "node:test";
import { buildBuckets, bucketKey, toTimeSeries } from "../metrics/timeSeries";

test("hourly buckets cover every hour of the day", () => {
  const buckets = buildBuckets(
    new Date(2026, 0, 5, 0, 0, 0, 0),
    new Date(2026, 0, 5, 23, 59, 59, 999),
    "hourly"
  );
  assert.equal(buckets.length, 24);
  assert.equal(buckets[0]?.label, "00:00");
  assert.equal(buckets[23]?.label, "23:00");
});

test("hourly series sums rows into buckets and zero-fills the rest", () => {
  const buckets = buildBuckets(
    new Date(2026, 0, 5, 0, 0, 0, 0),
    new Date(2026, 0, 5, 23, 59, 59, 999),
    "hourly"
  );
  const series = toTimeSeries("hourly", buckets, [
    { date: new Date(2026, 0, 5, 3, 15), value: 10 },
    { date: new Date(2026, 0, 5, 3, 45), value: 5 },
    { date: new Date(2026, 0, 5, 10, 0), value: 7 },
  ]);

  assert.equal(series.granularity, "hourly");
  assert.equal(series.unit, "hours");
  assert.equal(series.data.length, 24);
  assert.equal(series.data[0]?.value, 0);
  assert.equal(series.data[3]?.value, 15);
  assert.equal(series.data[10]?.value, 7);
});

test("daily buckets are inclusive of the end day", () => {
  const buckets = buildBuckets(
    new Date(2026, 0, 5, 8, 30),
    new Date(2026, 0, 7, 23, 59, 59, 999),
    "daily"
  );
  assert.equal(buckets.length, 3);
  assert.equal(buckets[0]?.start.getDate(), 5);
  assert.equal(buckets[2]?.start.getDate(), 7);
});

test("weekly buckets group by Monday", () => {
  const buckets = buildBuckets(new Date(2026, 0, 5), new Date(2026, 0, 20), "weekly");
  assert.equal(buckets.length, 3);
  assert.equal(buckets[0]?.start.getDate(), 5);
  assert.equal(buckets[1]?.start.getDate(), 12);
  assert.equal(buckets[2]?.start.getDate(), 19);

  const series = toTimeSeries("weekly", buckets, [
    { date: new Date(2026, 0, 6), value: 2 },
    { date: new Date(2026, 0, 13), value: 3 },
    { date: new Date(2026, 0, 18), value: 4 },
  ]);
  assert.equal(series.data[0]?.value, 2);
  // Jan 13 and Jan 18 are both in the week starting Jan 12.
  assert.equal(series.data[1]?.value, 7);
  assert.equal(series.data[2]?.value, 0);
});

test("bucket keys never collide across days", () => {
  const morning = bucketKey(new Date(2026, 0, 5, 3, 10), "hourly");
  const nextDay = bucketKey(new Date(2026, 0, 6, 3, 10), "hourly");
  assert.notEqual(morning, nextDay);
});
