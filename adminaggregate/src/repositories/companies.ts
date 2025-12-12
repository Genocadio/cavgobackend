import { eq, max, count, and, or, sql, inArray, gte, lte } from "drizzle-orm";
import type { InferModel } from "drizzle-orm";
import { db } from "../db/client";
import { companies, cars, drivers, trips, driverCarAssignments } from "../db/schema";
import type { Company } from "../types";

type CompanyRow = InferModel<typeof companies>;

const mapCompany = (row: CompanyRow): Company => ({
  id: row.id,
  companyName: row.companyName,
  email: row.email,
  phone: row.phone,
  address: row.address,
  city: row.city,
  companyCode: row.companyCode,
  status: row.status as Company["status"],
  createdAt: row.createdAt ? row.createdAt.toISOString() : null,
  updatedAt: row.updatedAt ? row.updatedAt.toISOString() : null,
  createdBy: row.createdBy,
  updatedBy: row.updatedBy,
});

export async function createCompany(company: Company): Promise<Company> {
  await db.insert(companies).values({
    id: company.id,
    companyName: company.companyName,
    email: company.email,
    phone: company.phone,
    address: company.address,
    city: company.city,
    companyCode: company.companyCode,
    status: company.status,
    createdAt: company.createdAt ? new Date(company.createdAt) : null,
    updatedAt: company.updatedAt ? new Date(company.updatedAt) : null,
    createdBy: company.createdBy,
    updatedBy: company.updatedBy,
  });
  return company;
}

export async function updateCompany(company: Company): Promise<Company> {
  await db
    .update(companies)
    .set({
      companyName: company.companyName,
      email: company.email,
      phone: company.phone,
      address: company.address,
      city: company.city,
      companyCode: company.companyCode,
      status: company.status,
      updatedAt: company.updatedAt ? new Date(company.updatedAt) : null,
      updatedBy: company.updatedBy,
    })
    .where(eq(companies.id, company.id));

  return company;
}

export async function getCompanyById(id: string): Promise<Company | null> {
  const [company] = await db.select().from(companies).where(eq(companies.id, id));
  return company ? mapCompany(company) : null;
}

export async function getAllCompanies(): Promise<Company[]> {
  const rows = await db.select().from(companies);
  return rows.map(mapCompany);
}

export async function getLatestVehicleUpdatedAt(companyId: string): Promise<string | null> {
  const result = await db
    .select({ maxUpdatedAt: max(cars.updatedAt) })
    .from(cars)
    .where(eq(cars.companyId, companyId));
  
  const maxDate = result[0]?.maxUpdatedAt;
  // If max returns null, it means there are no vehicles for this company
  if (!maxDate) {
    return null;
  }
  return maxDate.toISOString();
}

export async function getLatestDriverUpdatedAt(companyId: string): Promise<string | null> {
  const result = await db
    .select({ maxUpdatedAt: max(drivers.updatedAt) })
    .from(drivers)
    .where(eq(drivers.companyId, companyId));
  
  const maxDate = result[0]?.maxUpdatedAt;
  // If max returns null, it means there are no drivers for this company
  if (!maxDate) {
    return null;
  }
  return maxDate.toISOString();
}

export interface CompanyDashboardStats {
  company: {
    id: string;
    name: string;
    companyCode: string;
    address: string | null;
  };
  totalCars: number;
  totalDrivers: number;
  activeBuses: number;
  todayTrips: number;
  ongoingTrips: number;
}

export async function getCompanyDashboardStats(companyId: string): Promise<CompanyDashboardStats | null> {
  // Get company info
  const company = await getCompanyById(companyId);
  if (!company) {
    return null;
  }

  // Count total cars
  const carsResult = await db
    .select({ count: count() })
    .from(cars)
    .where(eq(cars.companyId, companyId));
  const totalCars = carsResult[0]?.count || 0;

  // Count total drivers
  const driversResult = await db
    .select({ count: count() })
    .from(drivers)
    .where(eq(drivers.companyId, companyId));
  const totalDrivers = driversResult[0]?.count || 0;

  // Count active buses (isOnline=true OR status in AVAILABLE/OCCUPIED)
  const activeBusesResult = await db
    .select({ count: count() })
    .from(cars)
    .where(
      and(
        eq(cars.companyId, companyId),
        or(
          eq(cars.isOnline, true),
          eq(cars.status, "AVAILABLE"),
          eq(cars.status, "OCCUPIED")
        )
      )
    );
  const activeBuses = activeBusesResult[0]?.count || 0;

  // Get all assignment IDs for company's cars
  const companyCars = await db
    .select({ id: cars.id })
    .from(cars)
    .where(eq(cars.companyId, companyId));
  
  const carIds = companyCars.map(c => c.id);
  
  let todayTrips = 0;
  let ongoingTrips = 0;

  if (carIds.length > 0) {
    // Get all assignment IDs for these cars
    const assignments = await db
      .select({ id: driverCarAssignments.id })
      .from(driverCarAssignments)
      .where(inArray(driverCarAssignments.carId, carIds));
    
    const assignmentIds = assignments.map(a => Number(a.id));

    if (assignmentIds.length > 0) {
      // Get today's date range (start and end of today)
      const todayStart = new Date();
      todayStart.setHours(0, 0, 0, 0);
      const todayEnd = new Date();
      todayEnd.setHours(23, 59, 59, 999);

      // Count total trips created today
      const todayTripsResult = await db
        .select({ count: count() })
        .from(trips)
        .where(
          and(
            inArray(trips.driverCarAssignmentId, assignmentIds),
            gte(trips.createdAt, todayStart),
            lte(trips.createdAt, todayEnd)
          )
        );
      todayTrips = todayTripsResult[0]?.count || 0;

      // Count ongoing trips (scheduled or in_progress) created today
      const ongoingTripsResult = await db
        .select({ count: count() })
        .from(trips)
        .where(
          and(
            inArray(trips.driverCarAssignmentId, assignmentIds),
            gte(trips.createdAt, todayStart),
            lte(trips.createdAt, todayEnd),
            or(
              eq(trips.status, "scheduled"),
              eq(trips.status, "in_progress")
            )
          )
        );
      ongoingTrips = ongoingTripsResult[0]?.count || 0;
    }
  }

  return {
    company: {
      id: company.id,
      name: company.companyName,
      companyCode: company.companyCode,
      address: company.address,
    },
    totalCars,
    totalDrivers,
    activeBuses,
    todayTrips,
    ongoingTrips,
  };
}

