import { and, desc, eq, gte, inArray, lte, sql } from "drizzle-orm";
import { db } from "../db/client";
import { packageCarrierLegs, packages } from "../db/schema";
import type {
  CompanyPackage,
  CompanyPackageActivity,
  DeliveryType,
  PackageActivityGroup,
  PackageCarrierLeg,
  PackageOrigin,
  PackageStatus,
} from "../types";
import type { DateRange } from "../metrics/dateRange";
import { isActivePackageStatus } from "../metrics/packageStatus";
import * as carRepository from "./cars";
import * as driverRepository from "./drivers";
import * as tripRepository from "./trips";

type PackageRow = typeof packages.$inferSelect;
type LegRow = typeof packageCarrierLegs.$inferSelect;

const toMs = (value: Date): number => value.getTime();

function mapLeg(row: LegRow): PackageCarrierLeg {
  return {
    id: row.id,
    packageId: row.packageId,
    driverId: row.driverId,
    tripId: row.tripId,
    companyId: row.companyId,
    companyRefId: row.companyRefId,
    vehicleId: row.vehicleId,
    linkedAt: toMs(row.linkedAt),
    fromStatus: row.fromStatus,
    toStatus: row.toStatus,
  };
}

function mapPackage(row: PackageRow, legsByPackage: Map<string, PackageCarrierLeg[]>): CompanyPackage {
  return {
    id: row.id,
    trackingCode: row.trackingCode,
    deliveryType: row.deliveryType as DeliveryType,
    packageOrigin: row.packageOrigin as PackageOrigin,
    status: row.status as PackageStatus,
    companyId: row.companyId,
    companyRefId: row.companyRefId,
    tripId: row.tripId,
    createdAt: toMs(row.createdAt),
    updatedAt: toMs(row.updatedAt),
    carrierLegs: legsByPackage.get(row.id) ?? [],
  };
}

/**
 * Upsert the whole package projection. Carrier legs are immutable snapshots,
 * so replacing them on each push keeps the local mirror authoritative.
 */
export async function upsertPackageSnapshot(snapshot: CompanyPackage): Promise<void> {
  await db
    .insert(packages)
    .values({
      id: snapshot.id,
      trackingCode: snapshot.trackingCode,
      deliveryType: snapshot.deliveryType,
      packageOrigin: snapshot.packageOrigin,
      status: snapshot.status,
      companyId: snapshot.companyId,
      companyRefId: snapshot.companyRefId,
      tripId: snapshot.tripId,
      createdAt: new Date(snapshot.createdAt),
      updatedAt: new Date(snapshot.updatedAt),
      syncedAt: new Date(),
    })
    .onConflictDoUpdate({
      target: packages.id,
      set: {
        trackingCode: snapshot.trackingCode,
        deliveryType: snapshot.deliveryType,
        packageOrigin: snapshot.packageOrigin,
        status: snapshot.status,
        companyId: snapshot.companyId,
        companyRefId: snapshot.companyRefId,
        tripId: snapshot.tripId,
        createdAt: new Date(snapshot.createdAt),
        updatedAt: new Date(snapshot.updatedAt),
        syncedAt: new Date(),
      },
    });

  await db.delete(packageCarrierLegs).where(eq(packageCarrierLegs.packageId, snapshot.id));

  if (snapshot.carrierLegs.length > 0) {
    await db.insert(packageCarrierLegs).values(
      snapshot.carrierLegs.map((leg) => ({
        id: leg.id,
        packageId: snapshot.id,
        driverId: leg.driverId,
        tripId: leg.tripId,
        companyId: leg.companyId,
        companyRefId: leg.companyRefId,
        vehicleId: leg.vehicleId,
        linkedAt: new Date(leg.linkedAt),
        fromStatus: leg.fromStatus,
        toStatus: leg.toStatus,
      }))
    );
  }
}

export interface PackageActivityFilters {
  tripId?: string | null | undefined;
  carId?: string | null | undefined;
  driverId?: string | null | undefined;
}

/**
 * Company-scoped package activity. A package is "sent by this company" when it
 * carries the company id directly or any carrier leg does, and it was either
 * created in the range or had a carrier leg linked in the range. This keeps
 * packages that passed through a company driver visible even after the
 * short-lived trip mirror is gone.
 */
export async function getCompanyPackageActivity(
  companyId: string,
  range: DateRange,
  statuses?: readonly PackageStatus[] | null,
  filters?: PackageActivityFilters
): Promise<CompanyPackageActivity> {
  const inCompany = sql`(
    ${packages.companyId} = ${companyId}
    OR ${packages.companyRefId} = ${companyId}
    OR EXISTS (
      SELECT 1 FROM package_carrier_legs leg
      WHERE leg.package_id = ${packages.id}
        AND (leg.company_id = ${companyId} OR leg.company_ref_id = ${companyId})
    )
  )`;

  const inRange = sql`(
    (${packages.createdAt} >= ${range.start} AND ${packages.createdAt} <= ${range.end})
    OR EXISTS (
      SELECT 1 FROM package_carrier_legs leg
      WHERE leg.package_id = ${packages.id}
        AND leg.linked_at >= ${range.start} AND leg.linked_at <= ${range.end}
    )
  )`;

  const conditions = [inCompany, inRange];
  const tripId = filters?.tripId ?? null;
  const carId = filters?.carId ?? null;
  const driverId = filters?.driverId ?? null;

  if (tripId) {
    conditions.push(sql`(
      ${packages.tripId} = ${tripId}
      OR EXISTS (
        SELECT 1 FROM package_carrier_legs leg
        WHERE leg.package_id = ${packages.id}
          AND leg.trip_id = ${tripId}
      )
    )`);
  }

  if (carId) {
    conditions.push(sql`EXISTS (
      SELECT 1 FROM package_carrier_legs leg
      WHERE leg.package_id = ${packages.id}
        AND leg.vehicle_id = ${carId}
    )`);
  }

  if (driverId) {
    conditions.push(sql`EXISTS (
      SELECT 1 FROM package_carrier_legs leg
      WHERE leg.package_id = ${packages.id}
        AND leg.driver_id = ${driverId}
    )`);
  }

  // Fetch the full in-scope window so the summary counts all statuses, then
  // apply the requested status filter to the returned `packages` list.
  const rows = await db
    .select()
    .from(packages)
    .where(and(...conditions))
    .orderBy(desc(packages.createdAt));

  if (rows.length === 0) {
    return {
      companyId,
      startTime: toMs(range.start),
      endTime: toMs(range.end),
      totalPackages: 0,
      inProgressPackages: 0,
      packages: [],
      groups: [],
    };
  }

  const legRows = await db
    .select()
    .from(packageCarrierLegs)
    .where(inArray(packageCarrierLegs.packageId, rows.map((row) => row.id)))
    .orderBy(packageCarrierLegs.linkedAt);

  const legsByPackage = new Map<string, PackageCarrierLeg[]>();
  for (const leg of legRows) {
    const list = legsByPackage.get(leg.packageId) ?? [];
    list.push(mapLeg(leg));
    legsByPackage.set(leg.packageId, list);
  }

  const allPackages = rows.map((row) => mapPackage(row, legsByPackage));
  const statusFilter = statuses && statuses.length > 0 ? new Set<string>(statuses) : null;
  const resultPackages = statusFilter
    ? allPackages.filter((pkg) => statusFilter.has(pkg.status))
    : allPackages;
  const groups = await buildActivityGroups(resultPackages);

  return {
    companyId,
    startTime: toMs(range.start),
    endTime: toMs(range.end),
    totalPackages: allPackages.length,
    inProgressPackages: allPackages.filter((pkg) => isActivePackageStatus(pkg.status)).length,
    packages: resultPackages,
    groups,
  };
}

/** Group packages by the trip/car/driver of their most recent carrier leg. */
async function buildActivityGroups(items: CompanyPackage[]): Promise<PackageActivityGroup[]> {
  const tripCache = new Map<string, Awaited<ReturnType<typeof tripRepository.getTripById>>>();
  const carCache = new Map<string, Awaited<ReturnType<typeof carRepository.getCarById>>>();
  const driverCache = new Map<string, Awaited<ReturnType<typeof driverRepository.getDriverById>>>();

  const grouped = new Map<string, PackageActivityGroup>();

  for (const pkg of items) {
    const latestLeg = pkg.carrierLegs.reduce<PackageCarrierLeg | null>((latest, leg) => {
      if (!latest || leg.linkedAt > latest.linkedAt) return leg;
      return latest;
    }, null);

    const tripId = latestLeg?.tripId ?? pkg.tripId ?? null;
    const driverId = latestLeg?.driverId ?? null;
    const vehicleId = latestLeg?.vehicleId ?? null;

    let plate: string | null = null;
    let carId: string | null = null;
    let driverName: string | null = null;

    if (tripId) {
      if (!tripCache.has(tripId)) {
        tripCache.set(tripId, await tripRepository.getTripById(tripId));
      }
      const trip = tripCache.get(tripId);
      if (trip) {
        carId = trip.carDriver.car.id;
        plate = trip.carDriver.car.plate || null;
        const tripDriver = trip.carDriver.driver;
        if (tripDriver) {
          driverName = `${tripDriver.firstName} ${tripDriver.lastName}`.trim() || null;
        }
      }
    }

    if (!carId && vehicleId) {
      if (!carCache.has(vehicleId)) {
        carCache.set(vehicleId, await carRepository.getCarById(vehicleId));
      }
      const car = carCache.get(vehicleId);
      if (car) {
        carId = car.id;
        plate = car.plate || null;
      }
    }

    if (!driverName && driverId) {
      if (!driverCache.has(driverId)) {
        driverCache.set(driverId, await driverRepository.getDriverById(driverId));
      }
      const driver = driverCache.get(driverId);
      if (driver) {
        driverName = `${driver.firstName} ${driver.lastName}`.trim() || null;
      }
    }

    const key = tripId ?? `vehicle:${vehicleId ?? "none"}:${driverId ?? "none"}`;
    const existing = grouped.get(key);
    if (existing) {
      existing.packageCount += 1;
    } else {
      grouped.set(key, {
        tripId,
        carId,
        plate,
        driverId,
        driverName,
        packageCount: 1,
      });
    }
  }

  return [...grouped.values()].sort((a, b) => b.packageCount - a.packageCount);
}
