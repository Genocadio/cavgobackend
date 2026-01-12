import { eq } from "drizzle-orm";
import type { InferModel } from "drizzle-orm";
import { db } from "../db/client";
import { tripLocations } from "../db/schema";
import type { TripLocation } from "../types";

type TripLocationRow = InferModel<typeof tripLocations>;

const mapLocation = (row: TripLocationRow): TripLocation => ({
  id: row.id,
  addres: row.address,
  lat: row.latitude,
  lng: row.longitude,
});

export async function upsertTripLocation(location: TripLocation): Promise<TripLocation> {
  await db
    .insert(tripLocations)
    .values({
      id: location.id,
      address: location.addres ?? "",
      latitude: location.lat,
      longitude: location.lng,
    })
    .onConflictDoUpdate({
      target: tripLocations.id,
      set: {
        address: location.addres ?? "",
        latitude: location.lat,
        longitude: location.lng,
      },
    });

  return location;
}

export async function getTripLocationById(id: string): Promise<TripLocation | null> {
  const [row] = await db.select().from(tripLocations).where(eq(tripLocations.id, id));
  return row ? mapLocation(row) : null;
}

