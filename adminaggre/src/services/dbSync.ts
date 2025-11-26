import { pool } from '../db/connection';
import { ApiVehicle, ApiWorker } from './apiClient';
import { apiClient } from './apiClient';
import { ApiTrip } from './tripApiClient';
import { ApiBooking } from './bookingApiClient';
import { bookingApiClient } from './bookingApiClient';
import { logger } from '../utils/logger';
import { isDriverless } from './dataMapper';

/**
 * Sync vehicles from API to database
 */
export async function syncVehicles(vehicles: ApiVehicle[]): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Get all existing vehicle IDs from database
    const existingVehiclesResult = await client.query('SELECT id FROM vehicles');
    const existingVehicleIds = new Set(
      existingVehiclesResult.rows.map((row: any) => row.id)
    );

    // Get vehicle IDs from API
    const apiVehicleIds = new Set(vehicles.map((v) => v.id));

    // Insert or update vehicles from API
    // Use batch insert for better performance when there are many vehicles
    if (vehicles.length > 0) {
      // For small batches, use individual inserts (simpler and still fast)
      // For large batches, we could optimize further, but this should be fine
      for (const vehicle of vehicles) {
        const operationalStatus = mapOperationalStatus(vehicle.operationalStatus);
        
        await client.query(
          `INSERT INTO vehicles (
            id, company_id, company_code, plate, model, make, capacity,
            connection_status, operational_status, last_updated, updated_at
          ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, CURRENT_TIMESTAMP)
          ON CONFLICT (id) DO UPDATE SET
            company_id = EXCLUDED.company_id,
            company_code = EXCLUDED.company_code,
            plate = EXCLUDED.plate,
            model = EXCLUDED.model,
            make = EXCLUDED.make,
            capacity = EXCLUDED.capacity,
            connection_status = EXCLUDED.connection_status,
            operational_status = EXCLUDED.operational_status,
            last_updated = EXCLUDED.last_updated,
            updated_at = CURRENT_TIMESTAMP`,
          [
            vehicle.id,
            vehicle.companyId,
            vehicle.companyCode,
            vehicle.plate,
            vehicle.model,
            vehicle.make,
            vehicle.capacity,
            vehicle.connectionStatus,
            operationalStatus,
            vehicle.lastUpdated,
          ]
        );
      }
    }

    // Delete vehicles that don't exist in API
    const vehiclesToDelete = Array.from(existingVehicleIds).filter(
      (id) => !apiVehicleIds.has(id)
    );

    if (vehiclesToDelete.length > 0) {
      // First delete vehicle-driver links for these vehicles
      await client.query(
        'DELETE FROM vehicle_driver_links WHERE vehicle_id = ANY($1)',
        [vehiclesToDelete]
      );
      // Then delete the vehicles
      await client.query('DELETE FROM vehicles WHERE id = ANY($1)', [
        vehiclesToDelete,
      ]);
    }

    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    console.error('Error syncing vehicles:', error);
    throw error;
  } finally {
    client.release();
  }
}

/**
 * Sync workers from API to database
 */
export async function syncWorkers(workers: ApiWorker[]): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Get all existing worker IDs from database
    const existingWorkersResult = await client.query('SELECT id FROM workers');
    const existingWorkerIds = new Set(
      existingWorkersResult.rows.map((row: any) => row.id)
    );

    // Get worker IDs from API
    const apiWorkerIds = new Set(workers.map((w) => w.id));

    // Insert or update workers from API
    for (const worker of workers) {
      // Ensure role is not null - default to 'DRIVER' if missing
      const role = worker.role || 'DRIVER';
      // Ensure email is not null - default to empty string if missing
      const email = worker.email || '';
      
      await client.query(
        `INSERT INTO workers (
          id, name, phone, email, license_number, status, role, updated_at
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, CURRENT_TIMESTAMP)
        ON CONFLICT (id) DO UPDATE SET
          name = EXCLUDED.name,
          phone = EXCLUDED.phone,
          email = COALESCE(NULLIF(EXCLUDED.email, ''), workers.email),
          license_number = EXCLUDED.license_number,
          status = EXCLUDED.status,
          role = COALESCE(EXCLUDED.role, workers.role),
          updated_at = CURRENT_TIMESTAMP`,
        [
          worker.id,
          worker.name,
          worker.phone,
          email,
          worker.licenseNumber,
          worker.status,
          role,
        ]
      );
    }

    // Delete workers that don't exist in API
    const workersToDelete = Array.from(existingWorkerIds).filter(
      (id) => !apiWorkerIds.has(id)
    );

    if (workersToDelete.length > 0) {
      // First delete vehicle-driver links for these workers
      await client.query(
        'DELETE FROM vehicle_driver_links WHERE driver_id = ANY($1)',
        [workersToDelete]
      );
      // Then delete the workers
      await client.query('DELETE FROM workers WHERE id = ANY($1)', [
        workersToDelete,
      ]);
    }

    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    console.error('Error syncing workers:', error);
    throw error;
  } finally {
    client.release();
  }
}

/**
 * Sync vehicle-driver links based on worker vehicle assignments
 */
export async function syncVehicleDriverLinks(workers: ApiWorker[]): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');

    // Get all existing links
    const existingLinksResult = await client.query(
      'SELECT vehicle_id, driver_id FROM vehicle_driver_links'
    );
    const existingLinks = new Map<string, string>();
    existingLinksResult.rows.forEach((row: any) => {
      existingLinks.set(row.vehicle_id, row.driver_id);
    });

    // Process worker vehicle assignments
    const newLinks = new Map<string, string>();
    for (const worker of workers) {
      if (worker.role === 'DRIVER' && worker.vehicle) {
        newLinks.set(worker.vehicle.id, worker.id);
      }
    }

    // Remove links that no longer exist
    for (const [vehicleId, driverId] of existingLinks.entries()) {
      if (!newLinks.has(vehicleId) || newLinks.get(vehicleId) !== driverId) {
        await client.query(
          'DELETE FROM vehicle_driver_links WHERE vehicle_id = $1',
          [vehicleId]
        );
      }
    }

    // Add or update new links
    for (const [vehicleId, driverId] of newLinks.entries()) {
      const existingDriverId = existingLinks.get(vehicleId);
      if (existingDriverId !== driverId) {
        // Insert or update the link
        await client.query(
          `INSERT INTO vehicle_driver_links (vehicle_id, driver_id, updated_at)
           VALUES ($1, $2, CURRENT_TIMESTAMP)
           ON CONFLICT (vehicle_id) DO UPDATE SET
             driver_id = EXCLUDED.driver_id,
             updated_at = CURRENT_TIMESTAMP`,
          [vehicleId, driverId]
        );
      }
    }

    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    console.error('Error syncing vehicle-driver links:', error);
    throw error;
  } finally {
    client.release();
  }
}

/**
 * Get driver ID for a vehicle from database
 */
export async function getDriverIdForVehicle(
  vehicleId: string
): Promise<string | null> {
  const client = await pool.connect();
  try {
    const result = await client.query(
      'SELECT driver_id FROM vehicle_driver_links WHERE vehicle_id = $1',
      [vehicleId]
    );
    return result.rows.length > 0 ? result.rows[0].driver_id : null;
  } catch (error) {
    console.error('Error getting driver for vehicle:', error);
    return null;
  } finally {
    client.release();
  }
}

/**
 * Get vehicle ID for a driver from database
 */
export async function getVehicleIdForDriver(
  driverId: string
): Promise<string | null> {
  const client = await pool.connect();
  try {
    const result = await client.query(
      'SELECT vehicle_id FROM vehicle_driver_links WHERE driver_id = $1',
      [driverId]
    );
    return result.rows.length > 0 ? result.rows[0].vehicle_id : null;
  } catch (error) {
    console.error('Error getting vehicle for driver:', error);
    return null;
  } finally {
    client.release();
  }
}

/**
 * Get all driver IDs that have been linked to a vehicle (history)
 */
export async function getDriverHistoryForVehicle(
  vehicleId: string
): Promise<string[]> {
  const client = await pool.connect();
  try {
    // Note: This returns current driver only since we don't track history
    // If history tracking is needed, we'd need a separate history table
    const result = await client.query(
      'SELECT driver_id FROM vehicle_driver_links WHERE vehicle_id = $1',
      [vehicleId]
    );
    return result.rows.map((row: any) => row.driver_id);
  } catch (error) {
    console.error('Error getting driver history:', error);
    return [];
  } finally {
    client.release();
  }
}

/**
 * Helper function to map operational status
 */
function mapOperationalStatus(
  apiStatus: 'AVAILABLE' | 'MAINTENANCE' | 'OUT_OF_SERVICE' | 'OCCUPIED'
): string {
  switch (apiStatus) {
    case 'AVAILABLE':
    case 'OCCUPIED':
      return 'WORKING';
    case 'MAINTENANCE':
      return 'MAINTENANCE';
    case 'OUT_OF_SERVICE':
      return 'DEACTIVATED';
    default:
      return 'WORKING';
  }
}

/**
 * Store a vehicle within a transaction (helper for syncTrips)
 */
async function storeVehicleInTransaction(client: any, vehicle: ApiVehicle): Promise<void> {
  const operationalStatus = mapOperationalStatus(vehicle.operationalStatus);
  
  await client.query(
    `INSERT INTO vehicles (
      id, company_id, company_code, plate, model, make, capacity,
      connection_status, operational_status, last_updated, updated_at
    ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, CURRENT_TIMESTAMP)
    ON CONFLICT (id) DO UPDATE SET
      company_id = EXCLUDED.company_id,
      company_code = EXCLUDED.company_code,
      plate = EXCLUDED.plate,
      model = EXCLUDED.model,
      make = EXCLUDED.make,
      capacity = EXCLUDED.capacity,
      connection_status = EXCLUDED.connection_status,
      operational_status = EXCLUDED.operational_status,
      last_updated = EXCLUDED.last_updated,
      updated_at = CURRENT_TIMESTAMP`,
    [
      vehicle.id,
      vehicle.companyId,
      vehicle.companyCode,
      vehicle.plate,
      vehicle.model,
      vehicle.make,
      vehicle.capacity,
      vehicle.connectionStatus,
      operationalStatus,
      vehicle.lastUpdated,
    ]
  );
}

/**
 * Store a single vehicle in database (for POST endpoint)
 */
export async function storeVehicle(vehicle: ApiVehicle): Promise<void> {
  const client = await pool.connect();
  try {
    logger.info('Storing vehicle', { vehicleId: vehicle.id, companyId: vehicle.companyId });
    await storeVehicleInTransaction(client, vehicle);
  } catch (error) {
    logger.error('Error storing vehicle', { error, vehicleId: vehicle.id });
    throw error;
  } finally {
    client.release();
  }
}

/**
 * Store a worker within a transaction (helper for syncTrips)
 */
async function storeWorkerInTransaction(client: any, worker: ApiWorker): Promise<void> {
  // Ensure role is not null - default to 'DRIVER' if missing
  const role = worker.role || 'DRIVER';
  // Ensure email is not null - default to empty string if missing
  const email = worker.email || '';
  
  await client.query(
    `INSERT INTO workers (
      id, name, phone, email, license_number, status, role, updated_at
    ) VALUES ($1, $2, $3, $4, $5, $6, $7, CURRENT_TIMESTAMP)
    ON CONFLICT (id) DO UPDATE SET
      name = EXCLUDED.name,
      phone = EXCLUDED.phone,
      email = COALESCE(NULLIF(EXCLUDED.email, ''), workers.email),
      license_number = EXCLUDED.license_number,
      status = EXCLUDED.status,
      role = COALESCE(EXCLUDED.role, workers.role),
      updated_at = CURRENT_TIMESTAMP`,
    [
      worker.id,
      worker.name,
      worker.phone,
      email,
      worker.licenseNumber,
      worker.status,
      role,
    ]
  );

  // Update vehicle-driver link if worker has a vehicle
  if (worker.role === 'DRIVER' && worker.vehicle) {
    // Remove any existing links for this vehicle
    await client.query(
      'DELETE FROM vehicle_driver_links WHERE vehicle_id = $1',
      [worker.vehicle.id]
    );
    
    await client.query(
      `INSERT INTO vehicle_driver_links (vehicle_id, driver_id, updated_at)
       VALUES ($1, $2, CURRENT_TIMESTAMP)
       ON CONFLICT (vehicle_id) DO UPDATE SET
         driver_id = EXCLUDED.driver_id,
         updated_at = CURRENT_TIMESTAMP`,
      [worker.vehicle.id, worker.id]
    );
  } else if (worker.role === 'DRIVER' && !worker.vehicle) {
    // Remove link if driver no longer has vehicle
    await client.query(
      'DELETE FROM vehicle_driver_links WHERE driver_id = $1',
      [worker.id]
    );
  }
}

/**
 * Store a single worker in database (for POST endpoint)
 */
export async function storeWorker(worker: ApiWorker): Promise<void> {
  const client = await pool.connect();
  try {
    logger.info('Storing worker', { workerId: worker.id, role: worker.role });
    await storeWorkerInTransaction(client, worker);
  } catch (error) {
    logger.error('Error storing worker', { error, workerId: worker.id });
    throw error;
  } finally {
    client.release();
  }
}

/**
 * Get location custom name or fallback to place name
 */
function getLocationName(location: { custom_name?: string; place_name?: string }): string {
  return location?.custom_name || location?.place_name || '';
}

/**
 * Sync trips from API to database
 * Processes drivers and vehicles from trips, saves them if they don't exist, and links them
 */
export async function syncTrips(trips: ApiTrip[], companyId: string): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    logger.info('Starting trip sync', { companyId, tripCount: trips.length });

    // Insert or update trips from API
    for (const trip of trips) {
      const origin = trip.route?.origin || { latitude: 0, longitude: 0 };
      const destination = trip.route?.destination || { latitude: 0, longitude: 0 };

      // Process vehicle from trip
      let vehicleId = trip.vehicle_id.toString();
      if (trip.vehicle) {
        // Check if vehicle exists
        const vehicleCheck = await client.query(
          'SELECT id FROM vehicles WHERE id = $1',
          [vehicleId]
        );

        if (vehicleCheck.rows.length === 0) {
          // Vehicle doesn't exist, save it
          logger.info('Saving new vehicle from trip', { vehicleId, companyId });
          const vehicle: ApiVehicle = {
            id: vehicleId,
            companyId: companyId,
            companyCode: trip.vehicle.company_name || '',
            plate: trip.vehicle.license_plate,
            model: '',
            make: '',
            capacity: trip.vehicle.capacity,
            connectionStatus: 'ONLINE',
            operationalStatus: 'AVAILABLE',
            currentLocation: null,
            lastUpdated: new Date().toISOString(),
          };
          await storeVehicleInTransaction(client, vehicle);
        }
      }

      // Process driver from trip
      let driverId: string | null = null;
      if (trip.vehicle?.driver) {
        // Check if this is a driverless trip (id === 0 and name is empty)
        if (isDriverless(trip.vehicle.driver)) {
          // Trip is driverless - skip driver processing
          logger.debug('Trip is driverless (driver id=0, name="")', { tripId: trip.id.toString() });
        } else if (trip.vehicle.driver.id) {
          const driverApiId = trip.vehicle.driver.id.toString();
          
          // Validate driver ID - must not be "0", empty, or invalid
          if (driverApiId && driverApiId !== '0' && driverApiId.trim() !== '') {
            // Check if driver exists in workers table with role DRIVER
            const driverCheck = await client.query(
              'SELECT id, role FROM workers WHERE id = $1',
              [driverApiId]
            );

            if (driverCheck.rows.length === 0) {
              // Driver doesn't exist, save it
              logger.info('Saving new driver from trip', { driverId: driverApiId, companyId });
              const worker: ApiWorker = {
                id: driverApiId,
                name: trip.vehicle.driver.name || 'Unknown Driver',
                phone: trip.vehicle.driver.phone || '',
                email: '', // Not available in trip data
                licenseNumber: null,
                status: 'ACTIVE',
                role: 'DRIVER',
                vehicle: null,
              };
              await storeWorkerInTransaction(client, worker);
              driverId = driverApiId;
            } else if (driverCheck.rows[0].role === 'DRIVER') {
              driverId = driverApiId;
            } else {
              logger.warn('Worker exists but is not a DRIVER', { workerId: driverApiId, role: driverCheck.rows[0].role });
            }
          } else {
            // Invalid driver ID (0, empty, etc) - this is expected, no warning needed
            logger.debug('Trip has invalid driver ID', { driverId: driverApiId, tripId: trip.id.toString() });
          }
        }
      }

      // If trip has both vehicle and driver, link them (unlink previous links)
      if (vehicleId && driverId) {
        // Remove any existing links for this vehicle
        await client.query(
          'DELETE FROM vehicle_driver_links WHERE vehicle_id = $1',
          [vehicleId]
        );
        
        // Create new link
        await client.query(
          `INSERT INTO vehicle_driver_links (vehicle_id, driver_id, updated_at)
           VALUES ($1, $2, CURRENT_TIMESTAMP)
           ON CONFLICT (vehicle_id) DO UPDATE SET
             driver_id = EXCLUDED.driver_id,
             updated_at = CURRENT_TIMESTAMP`,
          [vehicleId, driverId]
        );
        logger.debug('Linked vehicle to driver', { vehicleId, driverId });
      }

      // If no driver from trip, try to get from existing links
      if (!driverId) {
        const linkResult = await client.query(
          'SELECT driver_id FROM vehicle_driver_links WHERE vehicle_id = $1',
          [vehicleId]
        );
        if (linkResult.rows.length > 0) {
          const linkedDriverId = linkResult.rows[0].driver_id;
          // Validate the linked driver exists and is a DRIVER
          if (linkedDriverId && linkedDriverId !== '0') {
            const linkedDriverCheck = await client.query(
              'SELECT id, role FROM workers WHERE id = $1 AND role = $2',
              [linkedDriverId, 'DRIVER']
            );
            if (linkedDriverCheck.rows.length > 0) {
              driverId = linkedDriverId;
            }
          }
        }
      }
      
      // Final validation: ensure driverId is valid or null
      if (driverId && (driverId === '0' || driverId.trim() === '')) {
        logger.warn('Invalid driver ID detected, setting to null', { driverId, tripId: trip.id.toString() });
        driverId = null;
      }

      await client.query(
        `INSERT INTO trips (
          id, company_id, vehicle_id, driver_id, route_id, status,
          departure_time, start_time, end_time, cancelled_time,
          distance, seats, price,
          origin_custom_name, destination_custom_name,
          origin_latitude, origin_longitude,
          destination_latitude, destination_longitude,
          current_latitude, current_longitude,
          updated_at
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, CURRENT_TIMESTAMP)
        ON CONFLICT (id) DO UPDATE SET
          company_id = EXCLUDED.company_id,
          vehicle_id = EXCLUDED.vehicle_id,
          driver_id = EXCLUDED.driver_id,
          route_id = EXCLUDED.route_id,
          status = EXCLUDED.status,
          departure_time = EXCLUDED.departure_time,
          start_time = EXCLUDED.start_time,
          end_time = EXCLUDED.end_time,
          cancelled_time = EXCLUDED.cancelled_time,
          distance = EXCLUDED.distance,
          seats = EXCLUDED.seats,
          price = EXCLUDED.price,
          origin_custom_name = EXCLUDED.origin_custom_name,
          destination_custom_name = EXCLUDED.destination_custom_name,
          origin_latitude = EXCLUDED.origin_latitude,
          origin_longitude = EXCLUDED.origin_longitude,
          destination_latitude = EXCLUDED.destination_latitude,
          destination_longitude = EXCLUDED.destination_longitude,
          current_latitude = EXCLUDED.current_latitude,
          current_longitude = EXCLUDED.current_longitude,
          updated_at = CURRENT_TIMESTAMP`,
        [
          trip.id.toString(),
          companyId,
          trip.vehicle_id.toString(),
          driverId || null,
          trip.route_id?.toString() || null,
          trip.status,
          trip.departure_time ? new Date(trip.departure_time * 1000) : null,
          trip.departure_time ? new Date(trip.departure_time * 1000) : null,
          trip.completion_time ? new Date(trip.completion_time * 1000) : null,
          trip.status === 'CANCELLED' ? new Date(trip.updated_at) : null,
          null, // distance - will be calculated if needed
          trip.seats,
          trip.price,
          getLocationName(origin),
          getLocationName(destination),
          origin.latitude,
          origin.longitude,
          destination.latitude,
          destination.longitude,
          trip.current_latitude || null,
          trip.current_longitude || null,
        ]
      );
    }

    // Update company_trip_sync with latest trip IDs
    if (trips.length > 0) {
      const incompleteTrips = trips.filter(t => t.status !== 'COMPLETED' && t.status !== 'CANCELLED');
      const completeTrips = trips.filter(t => t.status === 'COMPLETED' || t.status === 'CANCELLED');

      let latestIncompleteId: string | null = null;
      let latestCompleteId: string | null = null;

      if (incompleteTrips.length > 0) {
        const sorted = incompleteTrips.sort((a, b) => {
          const timeA = new Date(a.updated_at).getTime();
          const timeB = new Date(b.updated_at).getTime();
          return timeB - timeA;
        });
        latestIncompleteId = sorted[0].id.toString();
      }

      if (completeTrips.length > 0) {
        const sorted = completeTrips.sort((a, b) => {
          const timeA = new Date(a.updated_at).getTime();
          const timeB = new Date(b.updated_at).getTime();
          return timeB - timeA;
        });
        latestCompleteId = sorted[0].id.toString();
      }

      await client.query(
        `INSERT INTO company_trip_sync (company_id, latest_incomplete_trip_id, latest_complete_trip_id, last_sync_at)
         VALUES ($1, $2, $3, CURRENT_TIMESTAMP)
         ON CONFLICT (company_id) DO UPDATE SET
           latest_incomplete_trip_id = COALESCE(EXCLUDED.latest_incomplete_trip_id, company_trip_sync.latest_incomplete_trip_id),
           latest_complete_trip_id = COALESCE(EXCLUDED.latest_complete_trip_id, company_trip_sync.latest_complete_trip_id),
           last_sync_at = CURRENT_TIMESTAMP`,
        [companyId, latestIncompleteId, latestCompleteId]
      );
    }

    await client.query('COMMIT');
    logger.info('Trip sync completed', { companyId, tripCount: trips.length });

  // Fetch and sync bookings for trips (non-blocking, fire and forget)
  // For batch operations, don't wait - trigger async
  if (bookingApiClient.isEnabled() && trips.length > 0) {
    // For single trip, wait synchronously
    if (trips.length === 1) {
      const trip = trips[0];
      const tripId = trip.id.toString();
      try {
        const dbClient = await pool.connect();
        try {
          const dbTripResult = await dbClient.query(
            'SELECT status, end_time, last_booking_fetch FROM trips WHERE id = $1',
            [tripId]
          );
          
          let tripStatus = trip.status;
          let tripCompletionTime: Date | null = null;
          let lastBookingFetch: Date | null = null;
          
          if (dbTripResult.rows.length > 0) {
            tripStatus = dbTripResult.rows[0].status;
            tripCompletionTime = dbTripResult.rows[0].end_time;
            lastBookingFetch = dbTripResult.rows[0].last_booking_fetch;
          } else {
            tripCompletionTime = trip.completion_time ? new Date(trip.completion_time * 1000) : null;
          }
          
          // Quick check: if we have recent bookings and trip is completed, skip
          if (tripStatus === 'COMPLETED' && lastBookingFetch && tripCompletionTime && lastBookingFetch >= tripCompletionTime) {
            logger.debug('Skipping booking fetch - already up to date', { tripId });
          } else if (tripStatus !== 'CANCELLED') {
            const bookings = await bookingApiClient.getBookingsByTrip(tripId);
            if (bookings.length > 0) {
              await syncBookingsForTrip(tripId, bookings, trip);
            }
          }
        } finally {
          dbClient.release();
        }
      } catch (error) {
        logger.error('Error fetching bookings for single trip', { tripId, error });
      }
    } else {
      // For multiple trips, fire and forget (non-blocking)
      logger.info('Triggering async booking sync for trips', { companyId, tripCount: trips.length });
      
      // Don't await - let it run in background
      (async () => {
        try {
          const dbClient = await pool.connect();
          try {
            // Batch check which trips need booking fetch
            const tripIds = trips.map(t => t.id.toString());
            const existingTripsResult = await dbClient.query(
              `SELECT id, status, end_time, last_booking_fetch 
               FROM trips 
               WHERE id = ANY($1)`,
              [tripIds]
            );
            
            const tripInfoMap = new Map<string, { status: string; end_time: Date | null; last_booking_fetch: Date | null }>();
            existingTripsResult.rows.forEach((row: any) => {
              tripInfoMap.set(row.id, {
                status: row.status,
                end_time: row.end_time,
                last_booking_fetch: row.last_booking_fetch,
              });
            });
            
            // Process trips that need booking fetch
            for (const trip of trips) {
              try {
                const tripId = trip.id.toString();
                const tripInfo = tripInfoMap.get(tripId);
                
                // Quick skip checks
                if (tripInfo?.status === 'CANCELLED') {
                  continue; // Skip cancelled trips
                }
                
                // If completed and already fetched after completion, skip
                if (tripInfo?.status === 'COMPLETED' && 
                    tripInfo.last_booking_fetch && 
                    tripInfo.end_time && 
                    tripInfo.last_booking_fetch >= tripInfo.end_time) {
                  continue; // Already up to date
                }
                
                // Fetch bookings (non-blocking, but sequential to avoid overwhelming API)
                const bookings = await bookingApiClient.getBookingsByTrip(tripId);
                if (bookings.length > 0) {
                  await syncBookingsForTrip(tripId, bookings, trip);
                }
              } catch (error) {
                logger.error('Error fetching bookings for trip in batch', {
                  tripId: trip.id.toString(),
                  error,
                });
                // Continue with other trips
              }
            }
          } finally {
            dbClient.release();
          }
          
          logger.info('Async booking sync completed', { companyId, tripCount: trips.length });
        } catch (error) {
          logger.error('Error in async booking sync', { companyId, error });
        }
      })();
    }
  }
  } catch (error) {
    await client.query('ROLLBACK');
    logger.error('Error syncing trips', { error, companyId });
    throw error;
  } finally {
    client.release();
  }
}

/**
 * Store a single trip in database (for POST endpoint)
 * Processes drivers and vehicles from trip, saves them if they don't exist, and links them
 */
export async function storeTrip(trip: ApiTrip, companyId: string): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    logger.info('Storing trip', { tripId: trip.id.toString(), companyId });

    const origin = trip.route?.origin || { latitude: 0, longitude: 0 };
    const destination = trip.route?.destination || { latitude: 0, longitude: 0 };

    // Process vehicle from trip
    let vehicleId = trip.vehicle_id.toString();
    if (trip.vehicle) {
      // Check if vehicle exists
      const vehicleCheck = await client.query(
        'SELECT id FROM vehicles WHERE id = $1',
        [vehicleId]
      );

      if (vehicleCheck.rows.length === 0) {
        // Vehicle doesn't exist, save it
        logger.info('Saving new vehicle from trip', { vehicleId, companyId });
        const vehicle: ApiVehicle = {
          id: vehicleId,
          companyId: companyId,
          companyCode: trip.vehicle.company_name || '',
          plate: trip.vehicle.license_plate,
          model: '',
          make: '',
          capacity: trip.vehicle.capacity,
          connectionStatus: 'ONLINE',
          operationalStatus: 'AVAILABLE',
          currentLocation: null,
          lastUpdated: new Date().toISOString(),
        };
        await storeVehicleInTransaction(client, vehicle);
      }
    }

    // Process driver from trip
    let driverId: string | null = null;
    if (trip.vehicle?.driver) {
      // Check if this is a driverless trip (id === 0 and name is empty)
      if (isDriverless(trip.vehicle.driver)) {
        // Trip is driverless - skip driver processing
        logger.debug('Trip is driverless (driver id=0, name="")', { tripId: trip.id.toString() });
      } else if (trip.vehicle.driver.id) {
        const driverApiId = trip.vehicle.driver.id.toString();
        
        // Validate driver ID - must not be "0", empty, or invalid
        if (driverApiId && driverApiId !== '0' && driverApiId.trim() !== '') {
          // Check if driver exists in workers table with role DRIVER
          const driverCheck = await client.query(
            'SELECT id, role FROM workers WHERE id = $1',
            [driverApiId]
          );

          if (driverCheck.rows.length === 0) {
            // Driver doesn't exist, save it
            logger.info('Saving new driver from trip', { driverId: driverApiId, companyId });
            const worker: ApiWorker = {
              id: driverApiId,
              name: trip.vehicle.driver.name || 'Unknown Driver',
              phone: trip.vehicle.driver.phone || '',
              email: '', // Not available in trip data
              licenseNumber: null,
              status: 'ACTIVE',
              role: 'DRIVER',
              vehicle: null,
            };
            await storeWorkerInTransaction(client, worker);
            driverId = driverApiId;
          } else if (driverCheck.rows[0].role === 'DRIVER') {
            driverId = driverApiId;
          } else {
            logger.warn('Worker exists but is not a DRIVER', { workerId: driverApiId, role: driverCheck.rows[0].role });
          }
        } else {
          logger.warn('Invalid driver ID in trip', { driverId: driverApiId, tripId: trip.id.toString() });
        }
      }
    }

    // If trip has both vehicle and driver, link them (unlink previous links)
    if (vehicleId && driverId) {
      // Remove any existing links for this vehicle
      await client.query(
        'DELETE FROM vehicle_driver_links WHERE vehicle_id = $1',
        [vehicleId]
      );
      
      // Create new link
      await client.query(
        `INSERT INTO vehicle_driver_links (vehicle_id, driver_id, updated_at)
         VALUES ($1, $2, CURRENT_TIMESTAMP)
         ON CONFLICT (vehicle_id) DO UPDATE SET
           driver_id = EXCLUDED.driver_id,
           updated_at = CURRENT_TIMESTAMP`,
        [vehicleId, driverId]
      );
      logger.debug('Linked vehicle to driver', { vehicleId, driverId });
    }

    // If no driver from trip, try to get from existing links
    if (!driverId) {
      const linkResult = await client.query(
        'SELECT driver_id FROM vehicle_driver_links WHERE vehicle_id = $1',
        [vehicleId]
      );
      if (linkResult.rows.length > 0) {
        const linkedDriverId = linkResult.rows[0].driver_id;
        // Validate the linked driver exists and is a DRIVER
        if (linkedDriverId && linkedDriverId !== '0') {
          const linkedDriverCheck = await client.query(
            'SELECT id, role FROM workers WHERE id = $1 AND role = $2',
            [linkedDriverId, 'DRIVER']
          );
          if (linkedDriverCheck.rows.length > 0) {
            driverId = linkedDriverId;
          }
        }
      }
    }
    
    // Final validation: ensure driverId is valid or null
    if (driverId && (driverId === '0' || driverId.trim() === '')) {
      logger.warn('Invalid driver ID detected, setting to null', { driverId, tripId: trip.id.toString() });
      driverId = null;
    }

    await client.query(
      `INSERT INTO trips (
        id, company_id, vehicle_id, driver_id, route_id, status,
        departure_time, start_time, end_time, cancelled_time,
        distance, seats, price,
        origin_custom_name, destination_custom_name,
        origin_latitude, origin_longitude,
        destination_latitude, destination_longitude,
        current_latitude, current_longitude,
        updated_at
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21, CURRENT_TIMESTAMP)
      ON CONFLICT (id) DO UPDATE SET
        company_id = EXCLUDED.company_id,
        vehicle_id = EXCLUDED.vehicle_id,
        driver_id = EXCLUDED.driver_id,
        route_id = EXCLUDED.route_id,
        status = EXCLUDED.status,
        departure_time = EXCLUDED.departure_time,
        start_time = EXCLUDED.start_time,
        end_time = EXCLUDED.end_time,
        cancelled_time = EXCLUDED.cancelled_time,
        distance = EXCLUDED.distance,
        seats = EXCLUDED.seats,
        price = EXCLUDED.price,
        origin_custom_name = EXCLUDED.origin_custom_name,
        destination_custom_name = EXCLUDED.destination_custom_name,
        origin_latitude = EXCLUDED.origin_latitude,
        origin_longitude = EXCLUDED.origin_longitude,
        destination_latitude = EXCLUDED.destination_latitude,
        destination_longitude = EXCLUDED.destination_longitude,
        current_latitude = EXCLUDED.current_latitude,
        current_longitude = EXCLUDED.current_longitude,
        updated_at = CURRENT_TIMESTAMP`,
      [
        trip.id.toString(),
        companyId,
        trip.vehicle_id.toString(),
        driverId || null,
        trip.route_id?.toString() || null,
        trip.status,
        trip.departure_time ? new Date(trip.departure_time * 1000) : null,
        trip.departure_time ? new Date(trip.departure_time * 1000) : null,
        trip.completion_time ? new Date(trip.completion_time * 1000) : null,
        trip.status === 'CANCELLED' ? new Date(trip.updated_at) : null,
        null, // distance - will be calculated from route if needed
        trip.seats,
        trip.price,
        getLocationName(origin),
        getLocationName(destination),
        origin.latitude,
        origin.longitude,
        destination.latitude,
        destination.longitude,
        trip.current_latitude || null,
        trip.current_longitude || null,
      ]
    );

    // Update company_trip_sync if this is the latest trip
    const isIncomplete = trip.status !== 'COMPLETED' && trip.status !== 'CANCELLED';
    const isComplete = trip.status === 'COMPLETED' || trip.status === 'CANCELLED';

    if (isIncomplete || isComplete) {
      const syncResult = await client.query(
        'SELECT latest_incomplete_trip_id, latest_complete_trip_id FROM company_trip_sync WHERE company_id = $1',
        [companyId]
      );

      let shouldUpdate = false;
      let latestIncompleteId: string | null = null;
      let latestCompleteId: string | null = null;

      if (syncResult.rows.length > 0) {
        latestIncompleteId = syncResult.rows[0].latest_incomplete_trip_id;
        latestCompleteId = syncResult.rows[0].latest_complete_trip_id;

        if (isIncomplete) {
          if (!latestIncompleteId || new Date(trip.updated_at) > new Date()) {
            latestIncompleteId = trip.id.toString();
            shouldUpdate = true;
          }
        }
        if (isComplete) {
          if (!latestCompleteId || new Date(trip.updated_at) > new Date()) {
            latestCompleteId = trip.id.toString();
            shouldUpdate = true;
          }
        }
      } else {
        latestIncompleteId = isIncomplete ? trip.id.toString() : null;
        latestCompleteId = isComplete ? trip.id.toString() : null;
        shouldUpdate = true;
      }

      if (shouldUpdate) {
        await client.query(
          `INSERT INTO company_trip_sync (company_id, latest_incomplete_trip_id, latest_complete_trip_id, last_sync_at)
           VALUES ($1, $2, $3, CURRENT_TIMESTAMP)
           ON CONFLICT (company_id) DO UPDATE SET
             latest_incomplete_trip_id = COALESCE(EXCLUDED.latest_incomplete_trip_id, company_trip_sync.latest_incomplete_trip_id),
             latest_complete_trip_id = COALESCE(EXCLUDED.latest_complete_trip_id, company_trip_sync.latest_complete_trip_id),
             last_sync_at = CURRENT_TIMESTAMP`,
          [companyId, latestIncompleteId, latestCompleteId]
        );
      }
    }

    await client.query('COMMIT');
    logger.info('Trip stored successfully', { tripId: trip.id.toString(), companyId });
  } catch (error) {
    await client.query('ROLLBACK');
    logger.error('Error storing trip', { error, tripId: trip.id.toString(), companyId });
    throw error;
  } finally {
    client.release();
  }
}

/**
 * Get latest incomplete trip ID for a company
 */
export async function getLatestIncompleteTripId(companyId: string): Promise<string | null> {
  const client = await pool.connect();
  try {
    const result = await client.query(
      'SELECT latest_incomplete_trip_id FROM company_trip_sync WHERE company_id = $1',
      [companyId]
    );
    return result.rows.length > 0 ? result.rows[0].latest_incomplete_trip_id : null;
  } catch (error) {
    logger.error('Error getting latest incomplete trip:', error);
    return null;
  } finally {
    client.release();
  }
}

/**
 * Get latest complete trip ID for a company
 */
export async function getLatestCompleteTripId(companyId: string): Promise<string | null> {
  const client = await pool.connect();
  try {
    const result = await client.query(
      'SELECT latest_complete_trip_id FROM company_trip_sync WHERE company_id = $1',
      [companyId]
    );
    return result.rows.length > 0 ? result.rows[0].latest_complete_trip_id : null;
  } catch (error) {
    logger.error('Error getting latest complete trip:', error);
    return null;
  } finally {
    client.release();
  }
}

/**
 * Get live trip for a vehicle (status IN_PROGRESS or SCHEDULED)
 */
export async function getActiveTripForVehicle(vehicleId: string): Promise<string | null> {
  const client = await pool.connect();
  try {
    const result = await client.query(
      `SELECT id FROM trips 
       WHERE vehicle_id = $1 AND status IN ('IN_PROGRESS', 'SCHEDULED')
       ORDER BY departure_time DESC
       LIMIT 1`,
      [vehicleId]
    );
    return result.rows.length > 0 ? result.rows[0].id : null;
  } catch (error) {
    logger.error('Error getting active trip for vehicle:', error);
    return null;
  } finally {
    client.release();
  }
}

/**
 * Get live trip for a driver (status IN_PROGRESS or SCHEDULED)
 */
export async function getActiveTripForDriver(driverId: string): Promise<string | null> {
  const client = await pool.connect();
  try {
    const result = await client.query(
      `SELECT id FROM trips 
       WHERE driver_id = $1 AND status IN ('IN_PROGRESS', 'SCHEDULED')
       ORDER BY departure_time DESC
       LIMIT 1`,
      [driverId]
    );
    return result.rows.length > 0 ? result.rows[0].id : null;
  } catch (error) {
    logger.error('Error getting active trip for driver:', error);
    return null;
  } finally {
    client.release();
  }
}

/**
 * Get active trip data for a vehicle from database (for cache in batch queries)
 */
export async function getActiveTripDataForVehicle(vehicleId: string): Promise<ApiTrip | null> {
  const client = await pool.connect();
  try {
    // Query for IN_PROGRESS first, then SCHEDULED with closest departure time
    const result = await client.query(
      `SELECT 
        id, company_id, vehicle_id, driver_id, route_id, status,
        departure_time, start_time, end_time, cancelled_time,
        distance, seats, price,
        origin_custom_name, destination_custom_name,
        origin_latitude, origin_longitude,
        destination_latitude, destination_longitude,
        current_latitude, current_longitude,
        updated_at, created_at
      FROM trips 
      WHERE vehicle_id = $1 AND status IN ('IN_PROGRESS', 'SCHEDULED')
      ORDER BY 
        CASE WHEN status = 'IN_PROGRESS' THEN 0 ELSE 1 END,
        departure_time ASC
      LIMIT 1`,
      [vehicleId]
    );
    
    if (result.rows.length === 0) {
      return null;
    }
    
    const row = result.rows[0];
    
    // Reconstruct ApiTrip from database row
    const trip: ApiTrip = {
      id: parseInt(row.id, 10),
      route_id: row.route_id ? parseInt(row.route_id, 10) : 0,
      vehicle_id: parseInt(row.vehicle_id, 10),
      status: row.status as 'SCHEDULED' | 'STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED',
      departure_time: row.departure_time ? Math.floor(new Date(row.departure_time).getTime() / 1000) : 0,
      completion_time: row.end_time ? Math.floor(new Date(row.end_time).getTime() / 1000) : null,
      seats: row.seats || 0,
      price: row.price || 0,
      created_at: row.created_at ? new Date(row.created_at).toISOString() : new Date().toISOString(),
      updated_at: row.updated_at ? new Date(row.updated_at).toISOString() : new Date().toISOString(),
      current_latitude: row.current_latitude || null,
      current_longitude: row.current_longitude || null,
      route: {
        id: row.route_id ? parseInt(row.route_id, 10) : 0,
        origin: {
          latitude: row.origin_latitude || 0,
          longitude: row.origin_longitude || 0,
          custom_name: row.origin_custom_name || undefined,
        },
        destination: {
          latitude: row.destination_latitude || 0,
          longitude: row.destination_longitude || 0,
          custom_name: row.destination_custom_name || undefined,
        },
      },
      waypoints: [], // Waypoints not stored in database, will be empty
    };
    
    return trip;
  } catch (error) {
    logger.error('Error getting active trip data for vehicle:', { vehicleId, error });
    return null;
  } finally {
    client.release();
  }
}

/**
 * Get latest completed trip for a vehicle
 */
export async function getLatestCompletedTripForVehicle(vehicleId: string): Promise<{ id: string; end_time: Date } | null> {
  const client = await pool.connect();
  try {
    const result = await client.query(
      `SELECT id, end_time FROM trips 
       WHERE vehicle_id = $1 AND status = 'COMPLETED'
       ORDER BY end_time DESC
       LIMIT 1`,
      [vehicleId]
    );
    return result.rows.length > 0 ? {
      id: result.rows[0].id,
      end_time: result.rows[0].end_time
    } : null;
  } catch (error) {
    logger.error('Error getting latest completed trip for vehicle:', error);
    return null;
  } finally {
    client.release();
  }
}

/**
 * Update vehicle location from trip if trip has more recent location
 * Compares trip updated_at timestamp with vehicle currentLocation timestamp
 * Uses the location from the source with the latest timestamp
 */
export async function updateVehicleLocationFromTrip(vehicleId: string, trip: ApiTrip, vehicleFromApi: ApiVehicle | null): Promise<void> {
  // Check if trip has location data
  if (trip.current_latitude == null || trip.current_longitude == null) {
    logger.debug('Trip has no location data, skipping vehicle location update', {
      vehicleId,
      tripId: trip.id,
    });
    return;
  }

  const client = await pool.connect();
  try {
    // Get trip timestamp
    const tripTimestamp = trip.updated_at ? new Date(trip.updated_at) : null;
    
    // Get vehicle timestamp from API
    let vehicleTimestamp: Date | null = null;
    if (vehicleFromApi?.currentLocation?.timestamp) {
      vehicleTimestamp = new Date(vehicleFromApi.currentLocation.timestamp);
    }

    // Determine which location to use
    let useTripLocation = true;
    let selectedLatitude = trip.current_latitude;
    let selectedLongitude = trip.current_longitude;
    let selectedTimestamp = tripTimestamp;

    if (vehicleFromApi?.currentLocation && vehicleTimestamp && tripTimestamp) {
      // Both have timestamps, compare them
      if (vehicleTimestamp > tripTimestamp) {
        // Vehicle location is more recent
        useTripLocation = false;
        selectedLatitude = vehicleFromApi.currentLocation.latitude;
        selectedLongitude = vehicleFromApi.currentLocation.longitude;
        selectedTimestamp = vehicleTimestamp;
        logger.debug('Using vehicle location (more recent)', {
          vehicleId,
          tripId: trip.id,
          vehicleTimestamp: vehicleTimestamp.toISOString(),
          tripTimestamp: tripTimestamp.toISOString(),
        });
      } else {
        // Trip location is more recent or equal
        logger.debug('Using trip location (more recent or equal)', {
          vehicleId,
          tripId: trip.id,
          tripTimestamp: tripTimestamp.toISOString(),
          vehicleTimestamp: vehicleTimestamp.toISOString(),
        });
      }
    } else if (vehicleFromApi?.currentLocation && !tripTimestamp) {
      // Vehicle has location but trip has no timestamp, use vehicle
      useTripLocation = false;
      selectedLatitude = vehicleFromApi.currentLocation.latitude;
      selectedLongitude = vehicleFromApi.currentLocation.longitude;
      selectedTimestamp = vehicleTimestamp;
      logger.debug('Using vehicle location (trip has no timestamp)', {
        vehicleId,
        tripId: trip.id,
      });
    } else {
      // Use trip location (vehicle has no location or no timestamp)
      logger.debug('Using trip location', {
        vehicleId,
        tripId: trip.id,
        tripTimestamp: tripTimestamp?.toISOString() || 'null',
      });
    }

    // Update vehicle in database
    await client.query(
      `UPDATE vehicles 
       SET current_latitude = $1, 
           current_longitude = $2, 
           location_timestamp = $3,
           updated_at = CURRENT_TIMESTAMP
       WHERE id = $4`,
      [
        selectedLatitude,
        selectedLongitude,
        selectedTimestamp,
        vehicleId,
      ]
    );

    logger.debug('Vehicle location updated from trip', {
      vehicleId,
      tripId: trip.id,
      latitude: selectedLatitude,
      longitude: selectedLongitude,
      timestamp: selectedTimestamp?.toISOString() || 'null',
      source: useTripLocation ? 'trip' : 'vehicle',
    });
  } catch (error) {
    logger.error('Error updating vehicle location from trip:', {
      vehicleId,
      tripId: trip.id,
      error,
    });
  } finally {
    client.release();
  }
}

/**
 * Get location name from trip route by matching location ID
 */
function getLocationNameFromTripRoute(
  locationId: string,
  trip: ApiTrip
): string | null {
  if (!trip.route) return null;

  // Check origin
  if (trip.route.origin && (trip.route.origin as any).id?.toString() === locationId) {
    return (trip.route.origin as any).custom_name || (trip.route.origin as any).google_place_name || (trip.route.origin as any).place_name || null;
  }

  // Check destination
  if (trip.route.destination && (trip.route.destination as any).id?.toString() === locationId) {
    return (trip.route.destination as any).custom_name || (trip.route.destination as any).google_place_name || (trip.route.destination as any).place_name || null;
  }

  // Check waypoints - waypoints have location nested or direct properties
  if (trip.waypoints) {
    for (const waypoint of trip.waypoints) {
      const waypointLocation = (waypoint as any).location || waypoint;
      if (waypointLocation && (waypointLocation as any).id?.toString() === locationId) {
        return (waypointLocation as any).custom_name || (waypointLocation as any).google_place_name || (waypointLocation as any).place_name || null;
      }
    }
  }

  return null;
}

/**
 * Sync bookings for a trip
 */
export async function syncBookingsForTrip(
  tripId: string,
  bookings: ApiBooking[],
  trip: ApiTrip
): Promise<void> {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    logger.info('Syncing bookings for trip', { tripId, bookingCount: bookings.length });

    for (const booking of bookings) {
      // Determine pickup and dropoff location names
      let pickupLocationName: string | null = null;
      let dropoffLocationName: string | null = null;

      // If booking has payment with status COMPLETED and has tickets, use ticket location names
      if (booking.payment?.status === 'COMPLETED' && booking.tickets && booking.tickets.length > 0) {
        pickupLocationName = booking.tickets[0].pickup_location_name || null;
        dropoffLocationName = booking.tickets[0].dropoff_location_name || null;
      } else {
        // For unpaid bookings, match location IDs to trip route
        pickupLocationName = getLocationNameFromTripRoute(booking.pickup_location_id, trip);
        dropoffLocationName = getLocationNameFromTripRoute(booking.dropoff_location_id, trip);
      }

      // Determine payment method and status
      const paymentMethod = booking.payment?.payment_method || null;
      const paymentStatus = booking.payment?.status || null;

      await client.query(
        `INSERT INTO bookings (
          id, trip_id, user_phone, user_name,
          pickup_location_id, dropoff_location_id,
          pickup_location_name, dropoff_location_name,
          number_of_tickets, total_amount, status,
          booking_reference, payment_method, payment_status,
          created_at, updated_at
        ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16)
        ON CONFLICT (id) DO UPDATE SET
          trip_id = EXCLUDED.trip_id,
          user_phone = EXCLUDED.user_phone,
          user_name = EXCLUDED.user_name,
          pickup_location_id = EXCLUDED.pickup_location_id,
          dropoff_location_id = EXCLUDED.dropoff_location_id,
          pickup_location_name = EXCLUDED.pickup_location_name,
          dropoff_location_name = EXCLUDED.dropoff_location_name,
          number_of_tickets = EXCLUDED.number_of_tickets,
          total_amount = EXCLUDED.total_amount,
          status = EXCLUDED.status,
          booking_reference = EXCLUDED.booking_reference,
          payment_method = EXCLUDED.payment_method,
          payment_status = EXCLUDED.payment_status,
          updated_at = CURRENT_TIMESTAMP`,
        [
          booking.id,
          tripId,
          booking.user_phone,
          booking.user_name,
          booking.pickup_location_id,
          booking.dropoff_location_id,
          pickupLocationName,
          dropoffLocationName,
          booking.number_of_tickets,
          booking.total_amount,
          booking.status,
          booking.booking_reference,
          paymentMethod,
          paymentStatus,
          booking.created_at ? new Date(booking.created_at) : new Date(),
          booking.updated_at ? new Date(booking.updated_at) : new Date(),
        ]
      );
    }

    // Update trip's last_booking_fetch timestamp
    await client.query(
      `UPDATE trips SET last_booking_fetch = CURRENT_TIMESTAMP WHERE id = $1`,
      [tripId]
    );

    await client.query('COMMIT');
    logger.info('Bookings synced successfully', { tripId, bookingCount: bookings.length });
  } catch (error) {
    await client.query('ROLLBACK');
    logger.error('Error syncing bookings for trip', { tripId, error });
    throw error;
  } finally {
    client.release();
  }
}

/**
 * Quick check if bookings should be fetched for a trip (optimized for batch operations)
 * Returns true if bookings need to be fetched, false if we can skip
 */
export async function shouldFetchBookingsForTrip(
  tripId: string,
  tripStatus: string,
  tripCompletionTime: Date | null,
  lastBookingFetch: Date | null = null
): Promise<boolean> {
  // Skip cancelled trips
  if (tripStatus === 'CANCELLED') {
    return false;
  }

  // Always fetch for SCHEDULED or IN_PROGRESS trips
  if (tripStatus === 'SCHEDULED' || tripStatus === 'IN_PROGRESS') {
    return true;
  }

  // For COMPLETED trips, check if we need to fetch
  if (tripStatus === 'COMPLETED' && tripCompletionTime) {
    // If lastBookingFetch was provided, use it (faster for batch operations)
    if (lastBookingFetch !== null) {
      // If last_booking_fetch is null or before completion_time, fetch
      if (!lastBookingFetch || lastBookingFetch < tripCompletionTime) {
        return true;
      }
      // Already fetched after completion, skip
      return false;
    }
    
    // Otherwise query database (for single trip operations)
    const client = await pool.connect();
    try {
      const result = await client.query(
        'SELECT last_booking_fetch FROM trips WHERE id = $1',
        [tripId]
      );

      if (result.rows.length === 0) {
        return true; // Trip not found, fetch bookings
      }

      const dbLastBookingFetch = result.rows[0].last_booking_fetch;
      
      // If last_booking_fetch is null or before completion_time, fetch
      if (!dbLastBookingFetch || new Date(dbLastBookingFetch) < tripCompletionTime) {
        return true;
      }

      // Already fetched after completion, skip
      return false;
    } finally {
      client.release();
    }
  }

  // Default: fetch for other statuses
  return true;
}

/**
 * Get bookings from database for a trip
 */
export async function getBookingsFromDatabase(tripId: string): Promise<ApiBooking[]> {
  const client = await pool.connect();
  try {
    const result = await client.query(
      `SELECT 
        id, trip_id, user_phone, user_name,
        pickup_location_id, dropoff_location_id,
        pickup_location_name, dropoff_location_name,
        number_of_tickets, total_amount, status,
        booking_reference, payment_method, payment_status,
        created_at, updated_at
      FROM bookings
      WHERE trip_id = $1
      ORDER BY created_at DESC`,
      [tripId]
    );

    return result.rows.map((row: any) => ({
      id: row.id,
      trip_id: parseInt(row.trip_id, 10),
      user_phone: row.user_phone,
      user_name: row.user_name,
      pickup_location_id: row.pickup_location_id,
      dropoff_location_id: row.dropoff_location_id,
      pickup_location_name: row.pickup_location_name || undefined,
      dropoff_location_name: row.dropoff_location_name || undefined,
      number_of_tickets: row.number_of_tickets,
      total_amount: parseFloat(row.total_amount),
      status: row.status,
      booking_reference: row.booking_reference,
      created_at: row.created_at.toISOString(),
      updated_at: row.updated_at.toISOString(),
      payment: row.payment_method ? {
        id: '',
        booking_id: row.id,
        amount: row.total_amount,
        payment_method: row.payment_method,
        status: row.payment_status || '',
        transaction_id: '',
        payment_data: '',
        created_at: row.created_at.toISOString(),
        updated_at: row.updated_at.toISOString(),
      } : undefined,
    }));
  } catch (error) {
    logger.error('Error getting bookings from database', { tripId, error });
    return [];
  } finally {
    client.release();
  }
}

/**
 * Calculate trip revenue from paid bookings
 */
export async function calculateTripRevenueFromBookings(tripId: string): Promise<number> {
  const client = await pool.connect();
  try {
    const result = await client.query(
      `SELECT COALESCE(SUM(total_amount), 0) as total_revenue
       FROM bookings
       WHERE trip_id = $1 AND status = 'USED'`,
      [tripId]
    );

    return parseFloat(result.rows[0]?.total_revenue || '0');
  } catch (error) {
    logger.error('Error calculating trip revenue from bookings', { tripId, error });
    return 0;
  } finally {
    client.release();
  }
}

