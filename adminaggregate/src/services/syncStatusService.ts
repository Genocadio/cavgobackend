import { sql } from "drizzle-orm";
import { db } from "../db/client";
import { packages } from "../db/schema";

/**
 * Observability for the package projection pushed by ikuriyebackend.
 *
 * `max(synced_at)` is the freshest snapshot this service has ingested. It is
 * deliberately independent of package `created_at`/`updated_at`: a quiet period
 * with no new packages should not look stale just because no deliveries moved.
 */

export const DEFAULT_STALE_THRESHOLD_SECONDS = 86_400;

export interface PackageSyncStatus {
  /** ISO-8601 timestamp of the most recent ingested package snapshot. */
  lastSyncedAt: string | null;
  packageCount: number;
  /** Seconds since that snapshot; null when nothing has ever been synced. */
  ageSeconds: number | null;
  stale: boolean;
  staleThresholdSeconds: number;
}

interface SyncStatusRow {
  lastSyncedAt: Date | string | null;
  packageCount: number | string;
}

/** Pure helper, exported so the freshness rules can be unit tested without a DB. */
export function buildPackageSyncStatus(
  row: SyncStatusRow | undefined,
  nowMs: number = Date.now(),
  staleThresholdSeconds: number = DEFAULT_STALE_THRESHOLD_SECONDS
): PackageSyncStatus {
  const parsed = row?.lastSyncedAt ? new Date(row.lastSyncedAt) : null;
  const lastSyncedAt = parsed && !Number.isNaN(parsed.getTime()) ? parsed : null;
  const ageSeconds = lastSyncedAt
    ? Math.max(0, Math.floor((nowMs - lastSyncedAt.getTime()) / 1000))
    : null;
  const packageCount = Number(row?.packageCount ?? 0) || 0;

  return {
    lastSyncedAt: lastSyncedAt ? lastSyncedAt.toISOString() : null,
    packageCount,
    ageSeconds,
    stale: packageCount > 0 && ageSeconds !== null && ageSeconds > staleThresholdSeconds,
    staleThresholdSeconds,
  };
}

export async function getPackageSyncStatus(): Promise<PackageSyncStatus> {
  const configured = Number(process.env.PACKAGE_SYNC_STALE_SECONDS ?? DEFAULT_STALE_THRESHOLD_SECONDS);
  const threshold =
    Number.isFinite(configured) && configured > 0 ? configured : DEFAULT_STALE_THRESHOLD_SECONDS;

  const [row] = await db
    .select({
      lastSyncedAt: sql<Date | null>`MAX(${packages.syncedAt})`,
      packageCount: sql<string>`COUNT(*)::text`,
    })
    .from(packages);

  return buildPackageSyncStatus(row, Date.now(), threshold);
}
