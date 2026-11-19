import type { Granularity, TimeSeries } from "../types";

export interface TimeBucket {
  /** Stable grouping key for a bucket (unique across the whole range). */
  key: string;
  /** Human-readable label returned to clients. */
  label: string;
  start: Date;
  end: Date;
}

const pad2 = (value: number): string => value.toString().padStart(2, "0");

export function bucketLabel(date: Date, granularity: Granularity): string {
  switch (granularity) {
    case "hourly":
      return `${pad2(date.getHours())}:00`;
    case "daily":
      return date.toISOString().split("T")[0] || "";
    case "weekly": {
      const weekNum = Math.ceil(
        (date.getDate() + new Date(date.getFullYear(), date.getMonth(), 1).getDay()) / 7
      );
      return `Week ${weekNum}`;
    }
    case "monthly":
      return date.toLocaleString("default", { month: "long" }) || date.toISOString();
  }
}

/** Local-time bucket key, so two buckets on different days never collide. */
export function bucketKey(date: Date, granularity: Granularity): string {
  const year = date.getFullYear();
  const month = pad2(date.getMonth() + 1);
  const day = pad2(date.getDate());
  switch (granularity) {
    case "hourly":
      return `${year}-${month}-${day} ${pad2(date.getHours())}`;
    case "daily":
      return `${year}-${month}-${day}`;
    case "weekly": {
      const start = startOfWeek(date);
      return `${start.getFullYear()}-${pad2(start.getMonth() + 1)}-${pad2(start.getDate())}`;
    }
    case "monthly":
      return `${year}-${month}`;
  }
}

function startOfWeek(date: Date): Date {
  const result = new Date(date);
  const dayOfWeek = result.getDay();
  const diff = result.getDate() - dayOfWeek + (dayOfWeek === 0 ? -6 : 1);
  result.setDate(diff);
  result.setHours(0, 0, 0, 0);
  return result;
}

function startOfBucket(date: Date, granularity: Granularity): Date {
  const result = new Date(date);
  switch (granularity) {
    case "hourly":
      result.setMinutes(0, 0, 0);
      break;
    case "daily":
      result.setHours(0, 0, 0, 0);
      break;
    case "weekly":
      return startOfWeek(date);
    case "monthly":
      result.setDate(1);
      result.setHours(0, 0, 0, 0);
      break;
  }
  return result;
}

function advanceBucket(current: Date, granularity: Granularity): void {
  switch (granularity) {
    case "hourly":
      current.setHours(current.getHours() + 1);
      break;
    case "daily":
      current.setDate(current.getDate() + 1);
      break;
    case "weekly":
      current.setDate(current.getDate() + 7);
      break;
    case "monthly":
      current.setMonth(current.getMonth() + 1);
      break;
  }
}

function endOfBucket(start: Date, granularity: Granularity): Date {
  const end = new Date(start);
  switch (granularity) {
    case "hourly":
      end.setHours(end.getHours() + 1);
      break;
    case "daily":
      end.setDate(end.getDate() + 1);
      break;
    case "weekly":
      end.setDate(end.getDate() + 7);
      break;
    case "monthly":
      end.setMonth(end.getMonth() + 1);
      break;
  }
  end.setMilliseconds(end.getMilliseconds() - 1);
  return end;
}

/** Build the complete, zero-filled bucket list for a range. */
export function buildBuckets(start: Date, end: Date, granularity: Granularity): TimeBucket[] {
  const buckets: TimeBucket[] = [];
  const current = startOfBucket(start, granularity);

  while (current <= end) {
    const bucketStart = new Date(current);
    buckets.push({
      key: bucketKey(bucketStart, granularity),
      label: bucketLabel(bucketStart, granularity),
      start: bucketStart,
      end: endOfBucket(bucketStart, granularity),
    });
    advanceBucket(current, granularity);
  }

  return buckets;
}

/** Bucket raw rows into a dense time series without one query per bucket. */
export function toTimeSeries(
  granularity: Granularity,
  buckets: TimeBucket[],
  rows: ReadonlyArray<{ date: Date; value: number }>
): TimeSeries {
  const unitByGranularity: Record<Granularity, TimeSeries["unit"]> = {
    hourly: "hours",
    daily: "days",
    weekly: "weeks",
    monthly: "months",
  };

  const totals = new Map<string, number>();
  for (const bucket of buckets) {
    totals.set(bucket.key, 0);
  }
  for (const row of rows) {
    const key = bucketKey(row.date, granularity);
    if (totals.has(key)) {
      totals.set(key, (totals.get(key) || 0) + row.value);
    }
  }

  return {
    granularity,
    unit: unitByGranularity[granularity],
    data: buckets.map((bucket) => ({ label: bucket.label, value: totals.get(bucket.key) || 0 })),
  };
}
