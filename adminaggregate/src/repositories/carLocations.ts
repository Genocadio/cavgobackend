import { db } from "../db/client";
import { carLocations } from "../db/schema";

export interface CarLocationData {
  carId: string;
  driverId: string | null;
  latitude: number;
  longitude: number;
  speed: number;
  bearing: number | null;
  accuracy: number | null;
  timestamp: number;
}

export async function createCarLocation(location: CarLocationData): Promise<void> {
  await db.insert(carLocations).values({
    carId: location.carId,
    driverId: location.driverId,
    latitude: location.latitude,
    longitude: location.longitude,
    speed: location.speed,
    bearing: location.bearing,
    accuracy: location.accuracy,
    timestamp: new Date(location.timestamp),
  });
}


