import type { PackageStatus } from "../types";

/**
 * Package status vocabulary. Mirrors ikuriyebackend's PackageStatus enum.
 * DELIVERED/COMPLETED/CANCELLED are terminal; everything else is in progress.
 */
export const PACKAGE_STATUSES: readonly PackageStatus[] = [
  "CREATED",
  "ACCEPTED",
  "PICKED_UP",
  "IN_TRANSIT",
  "PENDING_CONFIRMATION",
  "DELIVERED",
  "COMPLETED",
  "CANCELLED",
  "ORIGIN_OFFICE",
  "ASSIGNED_DRIVER",
  "DESTINATION_OFFICE",
  "READY_FOR_COLLECTION",
] as const;

export const TERMINAL_PACKAGE_STATUSES: readonly PackageStatus[] = [
  "DELIVERED",
  "COMPLETED",
  "CANCELLED",
] as const;

/** Non-terminal statuses — the default filter for "in progress" package views. */
export const ACTIVE_PACKAGE_STATUSES: readonly PackageStatus[] = PACKAGE_STATUSES.filter(
  (status) => !TERMINAL_PACKAGE_STATUSES.includes(status)
);

export function isPackageStatus(value: unknown): value is PackageStatus {
  return typeof value === "string" && (PACKAGE_STATUSES as readonly string[]).includes(value);
}

export function isActivePackageStatus(status: PackageStatus): boolean {
  return !TERMINAL_PACKAGE_STATUSES.includes(status);
}

/**
 * Normalize an optional client-supplied status filter. Invalid values are
 * dropped; an empty result means "no filter was requested" to the caller.
 */
export function normalizePackageStatuses(values: unknown): PackageStatus[] | null {
  if (!Array.isArray(values)) {
    return null;
  }
  const valid = values.filter(isPackageStatus);
  return valid.length > 0 ? [...new Set(valid)] : null;
}
