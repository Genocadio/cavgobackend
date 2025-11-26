import { ApiVehicle, ApiWorker } from './apiClient';

export interface GraphQLCar {
  id: string;
  companyId: string;
  companyCode: string;
  plate: string;
  model: string;
  make: string;
  capacity: number;
  connectionStatus: 'ONLINE' | 'OFFLINE';
  operationalStatus: 'WORKING' | 'MAINTENANCE' | 'DEACTIVATED';
  currentLocation: {
    latitude: number;
    longitude: number;
    address: string | null;
    timestamp: string;
    bearing: number | null;
    speed: number | null;
  } | null;
  lastUpdated: string;
}

export interface GraphQLDriver {
  id: string;
  name: string;
  phone: string;
  email: string;
  licenseNumber: string;
  rating: number; // Non-nullable in GraphQL schema
  totalTrips: number;
  totalDistance: number;
  totalRevenue: number;
  lastTripTimestamp: string | null;
}

/**
 * Maps API operational status to GraphQL operational status
 */
function mapOperationalStatus(
  apiStatus: 'AVAILABLE' | 'MAINTENANCE' | 'OUT_OF_SERVICE' | 'OCCUPIED'
): 'WORKING' | 'MAINTENANCE' | 'DEACTIVATED' {
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
 * Maps API vehicle to GraphQL Car
 */
export function mapVehicleToCar(vehicle: ApiVehicle): GraphQLCar {
  // Validate currentLocation coordinates - set to null if invalid
  let currentLocation = vehicle.currentLocation;
  if (currentLocation) {
    const lat = currentLocation.latitude;
    const lng = currentLocation.longitude;
    // If coordinates are invalid (null, undefined, NaN, or not numbers), set location to null
    if (typeof lat !== 'number' || isNaN(lat) || typeof lng !== 'number' || isNaN(lng)) {
      currentLocation = null;
    }
  }
  
  // Ensure required fields are not null/undefined - provide defaults if needed
  const plate = vehicle.plate || 'N/A';
  const model = vehicle.model || 'Unknown';
  const make = vehicle.make || 'Unknown';
  const companyCode = vehicle.companyCode || '';
  const companyId = vehicle.companyId || '';
  const capacity = vehicle.capacity != null && !isNaN(vehicle.capacity) ? vehicle.capacity : 0;
  const lastUpdated = vehicle.lastUpdated || new Date().toISOString();
  const connectionStatus = vehicle.connectionStatus || 'OFFLINE';
  
  // Ensure id is always a valid non-empty string
  const vehicleId = vehicle.id?.toString() || 'unknown';
  
  return {
    id: vehicleId,
    companyId,
    companyCode,
    plate,
    model,
    make,
    capacity,
    connectionStatus,
    operationalStatus: mapOperationalStatus(vehicle.operationalStatus),
    currentLocation,
    lastUpdated,
  };
}

/**
 * Maps API worker to GraphQL Driver
 * Only call this for workers with role='DRIVER'
 */
export function mapWorkerToDriver(worker: ApiWorker): GraphQLDriver {
  return {
    id: worker.id,
    name: worker.name,
    phone: worker.phone,
    email: worker.email || '', // Ensure email is never null (default to empty string)
    licenseNumber: worker.licenseNumber || '',
    rating: 0, // Ensure rating is never null (default to 0)
    totalTrips: 0,
    totalDistance: 0,
    totalRevenue: 0,
    lastTripTimestamp: null,
  };
}

/**
 * Maps API vehicle to GraphQL Car (for nested vehicle in worker)
 */
export function mapVehicleToCarForDriver(vehicle: ApiVehicle | null): GraphQLCar | null {
  if (!vehicle) return null;
  return mapVehicleToCar(vehicle);
}

/**
 * Checks if a driver object represents a driverless trip
 * A trip is considered driverless if:
 * - driver is null/undefined, OR
 * - driver.name is empty or null (regardless of id), OR
 * - driver.id === 0 and driver.name is empty
 */
export function isDriverless(driver: { id?: number | string; name?: string } | null | undefined): boolean {
  if (!driver) return true;
  
  const driverName = (driver.name || '').trim();
  
  // Trip is driverless if name is empty or null (regardless of id)
  if (driverName === '') return true;
  
  // Also check if id is "0" and name is empty (for backwards compatibility)
  if (driver.id) {
    const driverId = driver.id.toString();
    return driverId === '0' && driverName === '';
  }
  
  return false;
}

/**
 * Converts ApiTripVehicle (from trip response) to ApiVehicle format
 * This is needed because trip.vehicle has a different structure than the vehicle API
 */
export function convertTripVehicleToApiVehicle(
  tripVehicle: { id: number; company_id: number; company_name?: string; capacity: number; license_plate: string },
  companyId: string
): import('./apiClient').ApiVehicle {
  // Ensure id is always a valid string
  const vehicleId = tripVehicle.id?.toString() || 'unknown';
  
  return {
    id: vehicleId,
    companyId: companyId || tripVehicle.company_id?.toString() || '1',
    companyCode: tripVehicle.company_name || '',
    plate: tripVehicle.license_plate || 'N/A',
    model: 'Unknown', // Not available in trip vehicle object
    make: 'Unknown', // Not available in trip vehicle object
    capacity: tripVehicle.capacity != null && !isNaN(tripVehicle.capacity) ? tripVehicle.capacity : 0,
    connectionStatus: 'ONLINE', // Default, can be updated from trip.connection_mode if needed
    operationalStatus: 'AVAILABLE', // Default - must match ApiVehicle type
    currentLocation: null, // Not available in trip vehicle object
    lastUpdated: new Date().toISOString(),
  };
}



