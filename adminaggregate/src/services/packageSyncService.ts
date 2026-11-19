import type { CompanyPackage, PackageCarrierLeg } from "../types";
import { isPackageStatus } from "../metrics/packageStatus";
import { PACKAGE_ORIGINS, DELIVERY_TYPES } from "../metrics/packageConstants";
import * as packageRepository from "../repositories/packages";
import { pubsub, TRIGGERS } from "./pubsub";

/**
 * Intake for package snapshots pushed from ikuriyebackend.
 *
 * Mirrors the vehicle/worker push pattern: the source service POSTs to a
 * key-guarded endpoint whenever a package changes, and this service upserts a
 * minimal projection (no customer PII) used for company-scoped metrics and
 * "sent today by car/trip/driver" views.
 */

export interface PackageLegPayload {
  id?: unknown;
  driverId?: unknown;
  tripId?: unknown;
  companyId?: unknown;
  companyRefId?: unknown;
  vehicleId?: unknown;
  linkedAt?: unknown;
  fromStatus?: unknown;
  toStatus?: unknown;
}

export interface PackageSnapshotPayload {
  packageId?: unknown;
  trackingCode?: unknown;
  deliveryType?: unknown;
  packageOrigin?: unknown;
  status?: unknown;
  companyId?: unknown;
  companyRefId?: unknown;
  tripId?: unknown;
  createdAt?: unknown;
  updatedAt?: unknown;
  carrierLegs?: unknown;
}

function requiredString(value: unknown, field: string): string {
  if (typeof value !== "string" || value.trim().length === 0) {
    throw new Error(`${field} is required`);
  }
  return value;
}

function optionalString(value: unknown): string | null {
  if (value == null) return null;
  const text = String(value);
  return text.length > 0 ? text : null;
}

function requiredTimestamp(value: unknown, field: string): number {
  if (typeof value !== "string" && typeof value !== "number") {
    throw new Error(`${field} must be an ISO timestamp or epoch value`);
  }
  const time = typeof value === "number" ? value : new Date(value).getTime();
  if (!Number.isFinite(time)) {
    throw new Error(`${field} is not a valid timestamp`);
  }
  return time;
}

function parseLeg(value: unknown, index: number): PackageCarrierLeg {
  if (typeof value !== "object" || value == null) {
    throw new Error(`carrierLegs[${index}] must be an object`);
  }
  const leg = value as PackageLegPayload;
  return {
    id: requiredString(leg.id, `carrierLegs[${index}].id`),
    packageId: "",
    driverId: requiredString(leg.driverId, `carrierLegs[${index}].driverId`),
    tripId: optionalString(leg.tripId),
    companyId: optionalString(leg.companyId),
    companyRefId: optionalString(leg.companyRefId),
    vehicleId: optionalString(leg.vehicleId),
    linkedAt: requiredTimestamp(leg.linkedAt, `carrierLegs[${index}].linkedAt`),
    fromStatus: optionalString(leg.fromStatus),
    toStatus: optionalString(leg.toStatus),
  };
}

export function mapPackageSnapshot(payload: PackageSnapshotPayload): CompanyPackage {
  const id = requiredString(payload.packageId, "packageId");
  const trackingCode = requiredString(payload.trackingCode, "trackingCode");

  if (
    typeof payload.deliveryType !== "string" ||
    !(DELIVERY_TYPES as readonly string[]).includes(payload.deliveryType)
  ) {
    throw new Error(`Invalid deliveryType: ${String(payload.deliveryType)}`);
  }
  if (
    typeof payload.packageOrigin !== "string" ||
    !(PACKAGE_ORIGINS as readonly string[]).includes(payload.packageOrigin)
  ) {
    throw new Error(`Invalid packageOrigin: ${String(payload.packageOrigin)}`);
  }
  if (!isPackageStatus(payload.status)) {
    throw new Error(`Invalid status: ${String(payload.status)}`);
  }

  const rawLegs = payload.carrierLegs ?? [];
  if (!Array.isArray(rawLegs)) {
    throw new Error("carrierLegs must be an array");
  }
  const carrierLegs = rawLegs.map((leg, index) => ({ ...parseLeg(leg, index), packageId: id }));

  return {
    id,
    trackingCode,
    deliveryType: payload.deliveryType as CompanyPackage["deliveryType"],
    packageOrigin: payload.packageOrigin as CompanyPackage["packageOrigin"],
    status: payload.status,
    companyId: optionalString(payload.companyId),
    companyRefId: optionalString(payload.companyRefId),
    tripId: optionalString(payload.tripId),
    createdAt: requiredTimestamp(payload.createdAt, "createdAt"),
    updatedAt: requiredTimestamp(payload.updatedAt, "updatedAt"),
    carrierLegs,
  };
}

/** Upsert one snapshot and notify company subscribers. */
export async function syncPackageFromInternal(payload: PackageSnapshotPayload): Promise<CompanyPackage> {
  const snapshot = mapPackageSnapshot(payload);
  await packageRepository.upsertPackageSnapshot(snapshot);

  for (const companyId of new Set(
    [snapshot.companyId, snapshot.companyRefId, ...snapshot.carrierLegs.flatMap((leg) => [leg.companyId, leg.companyRefId])]
      .filter((value): value is string => value != null)
  )) {
    pubsub.publish(TRIGGERS.COMPANY_PACKAGES_UPDATED(companyId), { companyId });
  }

  return snapshot;
}
