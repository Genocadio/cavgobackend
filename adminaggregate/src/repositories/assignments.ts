import { eq } from "drizzle-orm";
import { db } from "../db/client";
import { driverCarAssignments } from "../db/schema";
import type { DriverCarAssignment } from "../types";
import { getCarById } from "./cars";
import { getDriverById } from "./drivers";

export async function ensureDriverCarAssignment(
  driverId: string | null,
  carId: string,
): Promise<number> {
  // Verify car exists
  const car = await getCarById(carId);
  if (!car) {
    throw new Error(`Cannot create assignment: car ${carId} does not exist`);
  }

  // If driverId is provided, verify driver exists
  if (driverId) {
    const driver = await getDriverById(driverId);
    if (!driver) {
      // Driver doesn't exist yet - this can happen during sync when vehicles are synced before drivers
      // Skip assignment creation for now, it will be created when drivers are synced
      console.warn(`[ASSIGNMENT] Skipping assignment: driver ${driverId} does not exist yet (will be assigned when driver is synced)`);
      // Still ensure the car assignment exists (without driver)
      const existingAssignment = await db
        .select()
        .from(driverCarAssignments)
        .where(eq(driverCarAssignments.carId, carId))
        .limit(1);
      
      if (existingAssignment.length > 0 && existingAssignment[0]) {
        return existingAssignment[0].id;
      }
      
      // Create assignment without driver for now
      const insertedRows = await db
        .insert(driverCarAssignments)
        .values({
          driverId: null,
          carId,
        })
        .onConflictDoUpdate({
          target: driverCarAssignments.carId,
          set: {
            driverId: null,
          },
        })
        .returning({ id: driverCarAssignments.id });
      
      if (insertedRows.length === 0 || !insertedRows[0]) {
        throw new Error(`Failed to create assignment for car ${carId}`);
      }
      return insertedRows[0].id;
    }

    // Remove any existing assignment for that driver (1:1 relationship)
    await db
      .update(driverCarAssignments)
      .set({ driverId: null })
      .where(eq(driverCarAssignments.driverId, driverId));
  }

  // Remove any existing assignment for this car (1:1 relationship)
  await db
    .update(driverCarAssignments)
    .set({ driverId: null })
    .where(eq(driverCarAssignments.carId, carId));

  // Now create or update the assignment
  const insertedRows = await db
    .insert(driverCarAssignments)
    .values({
      driverId,
      carId,
    })
    .onConflictDoUpdate({
      target: driverCarAssignments.carId,
      set: {
        driverId,
      },
    })
    .returning({ id: driverCarAssignments.id });

  if (insertedRows.length === 0) {
    throw new Error("driver-car assignment insert returned no rows");
  }

  const inserted = insertedRows[0]!;
  return inserted.id;
}

export async function getDriverCarAssignmentById(
  assignmentId: number,
): Promise<DriverCarAssignment | null> {
  const [assignment] = await db
    .select()
    .from(driverCarAssignments)
    .where(eq(driverCarAssignments.id, assignmentId));

  if (!assignment) {
    return null;
  }

  const car = await getCarById(assignment.carId);
  if (!car) {
    return null;
  }

  const driver = assignment.driverId ? await getDriverById(assignment.driverId) : null;

  return {
    car,
    driver,
  };
}

export async function removeDriverFromAssignment(carId: string): Promise<void> {
  await db
    .update(driverCarAssignments)
    .set({ driverId: null })
    .where(eq(driverCarAssignments.carId, carId));
}

export async function removeAssignmentByDriverId(driverId: string): Promise<void> {
  await db
    .update(driverCarAssignments)
    .set({ driverId: null })
    .where(eq(driverCarAssignments.driverId, driverId));
}

export async function clearAssignmentIfDriverNull(driverId: string | null): Promise<void> {
  if (driverId === null) {
    // This is a helper that can be used when we receive a vehicle with null driver
    // It's already handled in ensureDriverCarAssignment, but kept for clarity
    return;
  }
}

export async function clearAssignmentIfVehicleNull(driverId: string | null): Promise<void> {
  if (driverId !== null) {
    // This is a helper that can be used when we receive a driver with null vehicle
    // Find and clear any assignment for this driver
    await db
      .update(driverCarAssignments)
      .set({ driverId: null })
      .where(eq(driverCarAssignments.driverId, driverId));
  }
}

export async function getDriverCarAssignmentByCarId(
  carId: string,
): Promise<DriverCarAssignment | null> {
  const [assignment] = await db
    .select()
    .from(driverCarAssignments)
    .where(eq(driverCarAssignments.carId, carId));

  if (!assignment) {
    return null;
  }

  const car = await getCarById(assignment.carId);
  if (!car) {
    return null;
  }

  const driver = assignment.driverId ? await getDriverById(assignment.driverId) : null;

  return {
    car,
    driver,
  };
}

export async function getDriverCarAssignmentByDriverId(
  driverId: string,
): Promise<DriverCarAssignment | null> {
  const [assignment] = await db
    .select()
    .from(driverCarAssignments)
    .where(eq(driverCarAssignments.driverId, driverId));

  if (!assignment) {
    return null;
  }

  const car = await getCarById(assignment.carId);
  if (!car) {
    return null;
  }

  const driver = await getDriverById(driverId);

  return {
    car,
    driver,
  };
}

