import { eq } from "drizzle-orm";
import type { InferModel } from "drizzle-orm";
import { db } from "../db/client";
import { drivers } from "../db/schema";
import type { Driver } from "../types";

type DriverRow = InferModel<typeof drivers>;

const mapDriver = (row: DriverRow): Driver => ({
  id: row.id,
  firstName: row.firstName,
  lastName: row.lastName,
  phoneNumber: row.phoneNumber,
  email: row.email,
  status: row.status as Driver["status"],
  companyId: row.companyId,
  dateOfBirth: row.dateOfBirth ? String(row.dateOfBirth) : null,
  address: row.address,
  licenseNumber: row.licenseNumber,
  licenseExpiry: row.licenseExpiry ? String(row.licenseExpiry) : null,
  role: row.role,
  createdAt: row.createdAt ? row.createdAt.toISOString() : null,
  updatedAt: row.updatedAt ? row.updatedAt.toISOString() : null,
});

export async function createDriver(driver: Driver): Promise<Driver> {
  await db.insert(drivers).values({
    id: driver.id,
    firstName: driver.firstName,
    lastName: driver.lastName,
    phoneNumber: driver.phoneNumber,
    email: driver.email,
    status: driver.status,
    companyId: driver.companyId,
    dateOfBirth: driver.dateOfBirth || null,
    address: driver.address,
    licenseNumber: driver.licenseNumber,
    licenseExpiry: driver.licenseExpiry || null,
    role: driver.role,
    createdAt: driver.createdAt ? new Date(driver.createdAt) : null,
    updatedAt: driver.updatedAt ? new Date(driver.updatedAt) : null,
  });
  return driver;
}

export async function updateDriver(driver: Driver): Promise<Driver> {
  await db
    .update(drivers)
    .set({
      firstName: driver.firstName,
      lastName: driver.lastName,
      phoneNumber: driver.phoneNumber,
      email: driver.email,
      status: driver.status,
      companyId: driver.companyId,
      dateOfBirth: driver.dateOfBirth || null,
      address: driver.address,
      licenseNumber: driver.licenseNumber,
      licenseExpiry: driver.licenseExpiry || null,
      role: driver.role,
      updatedAt: driver.updatedAt ? new Date(driver.updatedAt) : null,
    })
    .where(eq(drivers.id, driver.id));

  return driver;
}

export async function deleteDriver(id: string): Promise<void> {
  await db.delete(drivers).where(eq(drivers.id, id));
}

export async function getDriverById(id: string): Promise<Driver | null> {
  const [driver] = await db.select().from(drivers).where(eq(drivers.id, id));
  return driver ? mapDriver(driver) : null;
}

export async function getDriversByCompany(companyId: string): Promise<Driver[]> {
  const rows = await db.select().from(drivers).where(eq(drivers.companyId, companyId));
  return rows.map(mapDriver);
}

// Helper to get driver name (concatenated firstName + lastName)
export function getDriverName(driver: Driver): string {
  return `${driver.firstName} ${driver.lastName}`;
}

// Helper to find driver by phone number
export async function getDriverByPhone(phone: string): Promise<Driver | null> {
  const [driver] = await db
    .select()
    .from(drivers)
    .where(eq(drivers.phoneNumber, phone));
  return driver ? mapDriver(driver) : null;
}




