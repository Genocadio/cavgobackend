import * as mainApiClient from "./mainApiClient";
import { mapCompanyResponseDtoToCompany } from "../mappers/companyMapper";
import { mapVehicleResponseDtoToCar } from "../mappers/vehicleMapper";
import { mapCompanyUserResponseDtoToDriver } from "../mappers/driverMapper";
import { mapTripApiItemToLocalTrip } from "../mappers/tripMapper";
import type { Trip } from "../types";
import * as companyRepository from "../repositories/companies";
import * as carRepository from "../repositories/cars";
import * as driverRepository from "../repositories/drivers";
import * as assignmentRepository from "../repositories/assignments";
import * as tripRepository from "../repositories/trips";
import * as metricsRepository from "../repositories/metrics";
import * as snapshotRepository from "../repositories/snapshots";
import { updateTripMetrics } from "./tripMetricsService";
import * as locationRepository from "../repositories/locations";

let locationPollingTimer: NodeJS.Timeout | null = null;

interface PendingAssignment {
  driverId: string | null;
  carId: string;
}

export async function syncAllData(): Promise<void> {
  console.log("Starting data sync...");

  await syncLocations();
  
  // Temporary storage for assignments to be applied after sync
  const pendingAssignments: PendingAssignment[] = [];

  // 1. Fetch all companies and upsert
  console.log("Fetching companies...");
  const companiesDto = await mainApiClient.fetchCompanies();
  for (const companyDto of companiesDto) {
    const company = mapCompanyResponseDtoToCompany(companyDto);
    const existing = await companyRepository.getCompanyById(company.id);
    if (existing) {
      await companyRepository.updateCompany(company);
    } else {
      await companyRepository.createCompany(company);
    }
  }
  console.log(`Synced ${companiesDto.length} companies`);

  // 2. For each company, sync vehicles and drivers
  for (const companyDto of companiesDto) {
    const companyId = String(companyDto.id);
    console.log(`Syncing data for company ${companyId}...`);

    try {
      // Get latest updatedAt timestamps from local DB for this specific company
      const latestVehicleUpdatedAt = await companyRepository.getLatestVehicleUpdatedAt(companyId);
      const latestDriverUpdatedAt = await companyRepository.getLatestDriverUpdatedAt(companyId);

      // Fetch vehicles with timeLimit if we have a latest timestamp for vehicles
      if (latestVehicleUpdatedAt) {
        console.log(`[SYNC] Found latest vehicle updatedAt for company ${companyId}: ${latestVehicleUpdatedAt}`);
        console.log(`[SYNC] Using incremental sync for vehicles (only fetching vehicles updated after this time)`);
      } else {
        console.log(`[SYNC] No existing vehicles found for company ${companyId}, performing full sync`);
      }
      const timeLimitVehicle = latestVehicleUpdatedAt ? latestVehicleUpdatedAt : undefined;
      console.log(`Fetching vehicles for company ${companyId}...`);
      const vehiclesDto = await mainApiClient.fetchVehiclesByCompany(companyDto.id, timeLimitVehicle);

      let syncedVehicles = 0;
      let skippedVehicles = 0;
      for (const vehicleDto of vehiclesDto) {
        try {
          const car = mapVehicleResponseDtoToCar(vehicleDto);
          const existing = await carRepository.getCarById(car.id);
          if (existing) {
            await carRepository.updateCar(car);
          } else {
            await carRepository.createCar(car);
          }

          // Store driver assignment for later (after all vehicles and drivers are synced)
          if (vehicleDto.driver) {
            const driverId = String(vehicleDto.driver.id);
            pendingAssignments.push({ driverId, carId: car.id });
            console.log(`  [SYNC] Queued assignment: driver ${driverId} -> car ${car.id}`);
          } else {
            // Queue removal of driver assignment
            pendingAssignments.push({ driverId: null, carId: car.id });
            console.log(`  [SYNC] Queued removal of driver assignment from car ${car.id}`);
          }
          syncedVehicles++;
        } catch (error) {
          console.warn(`  [SYNC] Skipping vehicle ${vehicleDto?.id ?? "unknown"} for company ${companyId}:`, error);
          skippedVehicles++;
        }
      }
      console.log(`Synced ${syncedVehicles} vehicles for company ${companyId} (${skippedVehicles} skipped)`);

      // Fetch drivers with timeLimit if we have a latest timestamp for drivers
      if (latestDriverUpdatedAt) {
        console.log(`[SYNC] Found latest driver updatedAt for company ${companyId}: ${latestDriverUpdatedAt}`);
        console.log(`[SYNC] Using incremental sync for drivers (only fetching drivers updated after this time)`);
      } else {
        console.log(`[SYNC] No existing drivers found for company ${companyId}, performing full sync`);
      }
      const timeLimitDriver = latestDriverUpdatedAt ? latestDriverUpdatedAt : undefined;
      console.log(`Fetching drivers for company ${companyId}...`);
      const driversDto = await mainApiClient.fetchDriversByCompany(companyDto.id, timeLimitDriver);

      let syncedDrivers = 0;
      let skippedDrivers = 0;
      for (const driverDto of driversDto) {
        try {
          const driver = mapCompanyUserResponseDtoToDriver(driverDto);
          const existing = await driverRepository.getDriverById(driver.id);
          if (existing) {
            await driverRepository.updateDriver(driver);
          } else {
            await driverRepository.createDriver(driver);
          }

          // Store vehicle assignment for later (after all vehicles and drivers are synced)
          if (driverDto.vehicle) {
            const vehicleId = String(driverDto.vehicle.id);
            // Check if assignment already queued (from vehicle sync)
            const existingIndex = pendingAssignments.findIndex(
              a => a.carId === vehicleId && a.driverId === null
            );
            if (existingIndex >= 0 && pendingAssignments[existingIndex]) {
              // Update existing queued assignment
              pendingAssignments[existingIndex]!.driverId = driver.id;
              console.log(`  [SYNC] Updated queued assignment: driver ${driver.id} -> car ${vehicleId}`);
            } else {
              // Add new assignment
              pendingAssignments.push({ driverId: driver.id, carId: vehicleId });
              console.log(`  [SYNC] Queued assignment: driver ${driver.id} -> car ${vehicleId}`);
            }
          } else {
            // Queue removal of vehicle assignment for this driver
            // Find and remove any assignments for this driver
            const driverAssignments = pendingAssignments.filter(a => a.driverId === driver.id);
            driverAssignments.forEach(assignment => {
              const index = pendingAssignments.indexOf(assignment);
              if (index >= 0 && assignment) {
                pendingAssignments[index]!.driverId = null;
                console.log(`  [SYNC] Queued removal of driver ${driver.id} from car ${assignment.carId}`);
              }
            });
          }
          syncedDrivers++;
        } catch (error) {
          console.warn(`  [SYNC] Skipping driver ${driverDto?.id ?? "unknown"} for company ${companyId}:`, error);
          skippedDrivers++;
        }
      }
      console.log(`Synced ${syncedDrivers} drivers for company ${companyId} (${skippedDrivers} skipped)`);
    } catch (error) {
      console.error(`[SYNC] Failed to sync company ${companyId}, continuing with next company:`, error);
    }
  }

  // Apply all pending assignments now that vehicles and drivers are synced
  console.log(`\n[SYNC] Applying ${pendingAssignments.length} pending assignments...`);
  let appliedCount = 0;
  let skippedCount = 0;
  
  for (const assignment of pendingAssignments) {
    try {
      // Verify both car and driver exist before creating assignment
      const car = await carRepository.getCarById(assignment.carId);
      if (!car) {
        console.warn(`  [SYNC] Skipping assignment: car ${assignment.carId} does not exist`);
        skippedCount++;
        continue;
      }

      if (assignment.driverId) {
        const driver = await driverRepository.getDriverById(assignment.driverId);
        if (!driver) {
          console.warn(`  [SYNC] Skipping assignment: driver ${assignment.driverId} does not exist`);
          skippedCount++;
          continue;
        }
      }

      // Apply the assignment
      await assignmentRepository.ensureDriverCarAssignment(assignment.driverId, assignment.carId);
      appliedCount++;
      if (assignment.driverId) {
        console.log(`  [SYNC] Applied assignment: driver ${assignment.driverId} -> car ${assignment.carId}`);
      } else {
        console.log(`  [SYNC] Removed driver assignment from car ${assignment.carId}`);
      }
    } catch (error) {
      console.error(`  [SYNC] Failed to apply assignment (driver: ${assignment.driverId}, car: ${assignment.carId}):`, error);
      skippedCount++;
    }
  }

  console.log(`[SYNC] Assignments applied: ${appliedCount} successful, ${skippedCount} skipped`);
  console.log("Data sync completed");
}

export async function syncLocations(): Promise<void> {
  console.log("Starting location sync...");

  const locations = await mainApiClient.fetchLocations();
  let syncedCount = 0;
  let skippedCount = 0;

  for (const locationDto of locations) {
    try {
      const location = {
        id: String(locationDto.id),
        lat: locationDto.latitude,
        lng: locationDto.longitude,
        addres: locationDto.custom_name || locationDto.google_place_name || `Location ${locationDto.id}`,
      };

      await locationRepository.upsertTripLocation(location);
      syncedCount++;
    } catch (error) {
      console.warn(`[LOCATION SYNC] Skipping location ${locationDto?.id ?? "unknown"}:`, error);
      skippedCount++;
    }
  }

  console.log(`[LOCATION SYNC] Location sync completed: ${syncedCount} synced, ${skippedCount} skipped`);
}

export function startLocationPolling(): () => void {
  console.log("[LOCATION POLLING] Starting location polling service (every 5 hours)...");

  locationPollingTimer = setInterval(() => {
    console.log("[LOCATION POLLING] Polling for locations...");
    void syncLocations().catch((error) => {
      console.error("[LOCATION POLLING] Error during location sync:", error);
    });
  }, 5 * 60 * 60 * 1000);

  return () => {
    if (locationPollingTimer) {
      console.log("[LOCATION POLLING] Stopping location polling service...");
      clearInterval(locationPollingTimer);
      locationPollingTimer = null;
    }
  };
}

export async function syncTrips(): Promise<void> {
  console.log("Starting trip sync...");
  
  // Get latest trip updated_at timestamp from local DB
  const latestTripUpdatedAt = await tripRepository.getLatestTripUpdatedAt();
  
  if (latestTripUpdatedAt) {
    console.log(`[TRIP SYNC] Found latest trip updatedAt: ${latestTripUpdatedAt}`);
    console.log(`[TRIP SYNC] Using incremental sync (only fetching trips updated after this time)`);
  } else {
    console.log(`[TRIP SYNC] No existing trips found, performing full sync`);
  }
  
  try {
    // Fetch all trips (with pagination handled in fetchTrips)
    const fetchOptions: { lastUpdateTime?: string; limit?: number; offset?: number } = {
      limit: 100,
      offset: 0,
    };
    if (latestTripUpdatedAt) {
      fetchOptions.lastUpdateTime = latestTripUpdatedAt;
    }
    const trips = await mainApiClient.fetchTrips(fetchOptions);
    
    console.log(`[TRIP SYNC] Processing ${trips.length} trips...`);
    
    let createdCount = 0;
    let updatedCount = 0;
    let skippedCount = 0;
    
    for (const tripApiItem of trips) {
      try {
        // Skip trips without vehicle_id (cannot be dedicated to a company)
        if (!tripApiItem.vehicle_id) {
          console.warn(`[TRIP SYNC] Skipping trip ${tripApiItem.id}: no vehicle_id (cannot be dedicated to a company)`);
          skippedCount++;
          continue;
        }
        
        // Extract vehicle_id and verify car exists
        const vehicleId = String(tripApiItem.vehicle_id);
        const car = await carRepository.getCarById(vehicleId);
        
        if (!car) {
          console.warn(`[TRIP SYNC] Skipping trip ${tripApiItem.id}: car ${vehicleId} does not exist in local DB (cannot be dedicated to a company)`);
          skippedCount++;
          continue;
        }
        
        // Extract driver_id from vehicle.driver.id or find by phone
        let driverId: string | null = null;
        if (tripApiItem.vehicle?.driver?.id) {
          driverId = String(tripApiItem.vehicle.driver.id);
          // Verify driver exists
          const driver = await driverRepository.getDriverById(driverId);
          if (!driver) {
            // Try to find by phone
            if (tripApiItem.vehicle.driver.phone) {
              const driverByPhone = await driverRepository.getDriverByPhone(tripApiItem.vehicle.driver.phone);
              driverId = driverByPhone?.id || null;
            } else {
              driverId = null;
            }
          }
        } else if (tripApiItem.vehicle?.driver?.phone) {
          // Try to find driver by phone
          const driverByPhone = await driverRepository.getDriverByPhone(tripApiItem.vehicle.driver.phone);
          driverId = driverByPhone?.id || null;
        }
        
        // Map trip API item to local trip with validation
        let localTrip: Trip;
        try {
          localTrip = await mapTripApiItemToLocalTrip(tripApiItem, vehicleId, driverId);
        } catch (mappingError) {
          const message = mappingError instanceof Error ? mappingError.message : String(mappingError);
          if (message.includes("missing local locations") || message.includes("missing from local storage")) {
            console.warn(`[TRIP SYNC] Trip ${tripApiItem.id} references missing local locations; refreshing locations and retrying once...`);
            await syncLocations();
            try {
              localTrip = await mapTripApiItemToLocalTrip(tripApiItem, vehicleId, driverId);
            } catch (retryError) {
              console.error(`[TRIP SYNC] Trip ${tripApiItem.id} still failed after location refresh:`, retryError instanceof Error ? retryError.message : String(retryError));
              skippedCount++;
              continue;
            }
          } else {
            console.error(`[TRIP SYNC] Trip ${tripApiItem.id} validation failed:`, message);
            console.error(`[TRIP SYNC] Trip summary for failed validation:`, {
              id: tripApiItem.id,
              status: tripApiItem.status,
              routePresent: !!tripApiItem.route,
              routeHasOrigin: !!(tripApiItem.route && tripApiItem.route.origin),
              routeHasDestination: !!(tripApiItem.route && tripApiItem.route.destination),
              waypointsCount: tripApiItem.waypoints?.length ?? 0,
            });
            skippedCount++;
            continue;
          }
        }
        
        // Check if trip exists
        const existing = await tripRepository.getTripById(localTrip.id);
        
        // Upsert trip
        if (existing) {
          await tripRepository.updateTrip(localTrip);
          updatedCount++;
        } else {
          await tripRepository.createTrip(localTrip);
          // Create initial snapshot with all seats available
          await snapshotRepository.createInitialSnapshot(localTrip, localTrip.carDriver.car.capacity);
          createdCount++;
        }
        
        // Update metrics for all trip create/update/cancel events
        // Note: Revenue/fare is set to 0 as it will be sourced from bookings data (not yet integrated)
        await updateTripMetrics({
          trip: localTrip,
          vehicleId,
          driverId,
          existingTrip: existing,
          tripDistance: localTrip.totalDistance,
          tripFare: 0, // Revenue will come from bookings, not from trip route_price/price
          startedAt: tripApiItem.departure_time || undefined,
          completedAt: tripApiItem.completion_time || undefined,
        });
      } catch (error) {
        console.error(`[TRIP SYNC] Failed to process trip ${tripApiItem.id}:`, error);
        skippedCount++;
      }
    }
    
    console.log(`[TRIP SYNC] Trip sync completed: ${createdCount} created, ${updatedCount} updated, ${skippedCount} skipped`);
    
    // If any trips were skipped due to missing locations, retry them after location sync
    if (skippedCount > 0) {
      console.log("[TRIP SYNC] Retrying skipped trips after location sync...");
      // Wait a moment for location sync to complete
      await new Promise(resolve => setTimeout(resolve, 1000));
      
      // Retry processing skipped trips
      for (const tripApiItem of trips) {
        const wasSkipped = trips.some((item: any) => 
          item.id === tripApiItem.id && 
          item._skippedDueToMissingLocations
        );
        
        if (wasSkipped) {
          try {
            // Extract vehicle_id and verify car exists for retry
            if (!tripApiItem.vehicle_id) {
              console.warn(`[TRIP SYNC] Skipping retry for trip ${tripApiItem.id}: no vehicle_id`);
              continue;
            }
            
            const retryVehicleId = String(tripApiItem.vehicle_id);
            const retryCar = await carRepository.getCarById(retryVehicleId);
            
            if (!retryCar) {
              console.warn(`[TRIP SYNC] Skipping retry for trip ${tripApiItem.id}: car ${retryVehicleId} does not exist`);
              continue;
            }
            
            // Extract driver_id for retry
            let retryDriverId: string | null = null;
            if (tripApiItem.vehicle?.driver?.id) {
              retryDriverId = String(tripApiItem.vehicle.driver.id);
              const retryDriver = await driverRepository.getDriverById(retryDriverId);
              if (!retryDriver) {
                if (tripApiItem.vehicle.driver.phone) {
                  const retryDriverByPhone = await driverRepository.getDriverByPhone(tripApiItem.vehicle.driver.phone);
                  retryDriverId = retryDriverByPhone?.id || null;
                } else {
                  retryDriverId = null;
                }
              }
            } else if (tripApiItem.vehicle?.driver?.phone) {
              const retryDriverByPhone = await driverRepository.getDriverByPhone(tripApiItem.vehicle.driver.phone);
              retryDriverId = retryDriverByPhone?.id || null;
            }
            
            const localTrip = await mapTripApiItemToLocalTrip(tripApiItem, retryVehicleId, retryDriverId);
            if (localTrip) {
              const existing = await tripRepository.getTripById(localTrip.id);
              if (existing) {
                await tripRepository.updateTrip(localTrip);
                updatedCount++;
              } else {
                await tripRepository.createTrip(localTrip);
                createdCount++;
              }
              console.log(`[TRIP SYNC] Successfully retried trip ${tripApiItem.id} after location sync`);
            }
          } catch (error) {
            console.error(`[TRIP SYNC] Failed to retry trip ${tripApiItem.id}:`, error);
          }
        }
      }
    }
  } catch (error) {
    console.error("[TRIP SYNC] Failed to sync trips:", error);
    throw error;
  }
}



