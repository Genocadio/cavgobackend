import { ApolloServer } from '@apollo/server';
import { expressMiddleware } from '@apollo/server/express4';
import { ApolloServerPluginDrainHttpServer } from '@apollo/server/plugin/drainHttpServer';
import { createServer } from 'http';
import express from 'express';
import { makeExecutableSchema } from '@graphql-tools/schema';
import { WebSocketServer } from 'ws';
import { useServer } from 'graphql-ws/lib/use/ws';
import { PubSub } from 'graphql-subscriptions';
import cors from 'cors';
import dotenv from 'dotenv';
import { logger, requestLogger } from './utils/logger';
import { initializeDatabase, pool } from './db/connection';
import { apiClient, ApiVehicle, ApiWorker } from './services/apiClient';
import { mapVehicleToCar, mapWorkerToDriver, GraphQLCar, GraphQLDriver, isDriverless, convertTripVehicleToApiVehicle } from './services/dataMapper';
import { tripApiClient, ApiTrip } from './services/tripApiClient';
import { mapTripToGraphQL } from './services/tripMapper';
import { bookingApiClient } from './services/bookingApiClient';
import { mapBookingToGraphQL } from './services/bookingMapper';
import {
  syncVehicles,
  syncWorkers,
  syncVehicleDriverLinks,
  getDriverIdForVehicle,
  getVehicleIdForDriver,
  getDriverHistoryForVehicle,
  storeVehicle,
  storeWorker,
  syncTrips,
  storeTrip,
  getLatestIncompleteTripId,
  getLatestCompleteTripId,
  getActiveTripForVehicle,
  getActiveTripForDriver,
  getLatestCompletedTripForVehicle,
  updateVehicleLocationFromTrip,
  syncBookingsForTrip,
  getBookingsFromDatabase,
  calculateTripRevenueFromBookings,
  getActiveTripDataForVehicle,
} from './services/dbSync';

dotenv.config();

const pubsub = new PubSub();

// Type Definitions
const typeDefs = `#graphql
  type Car {
    id: ID!
    companyId: ID!
    companyCode: String!
    plate: String!
    model: String!
    make: String!
    capacity: Int!
    connectionStatus: ConnectionStatus!
    operationalStatus: OperationalStatus!
    currentLocation: Location
    lastUpdated: String!
    activeTrip: Trip
    latestTripCompletionTime: String
    isOnline: Boolean!
    driver: Driver
  }

  type Location {
    latitude: Float!
    longitude: Float!
    address: String
    timestamp: String!
    bearing: Float
    speed: Float
  }

  type TripLocation {
    placename: String!
    latitude: Float!
    longitude: Float!
    passed: Boolean!
    passedTimestamp: String
    remainingDistance: Float
    fare: Float
  }

  enum ConnectionStatus {
    ONLINE
    OFFLINE
  }

  enum OperationalStatus {
    WORKING
    MAINTENANCE
    DEACTIVATED
  }

  type Booking {
    id: ID!
    tripId: ID!
    carId: ID!
    driverId: ID!
    customerId: ID!
    customerName: String!
    phoneNumber: String!
    email: String!
    pickupLocation: Location!
    dropoffLocation: Location!
    numberOfTickets: Int!
    fare: Float!
    status: BookingStatus!
    paymentMethod: PaymentMethod
    scheduledTime: String!
    createdAt: String!
  }

  enum BookingStatus {
    PENDING_PAYMENT
    PAID
    BOARDED
    CANCELLED
    EXPIRED
  }

  enum PaymentMethod {
    MOMO
    CASH
    CARD
    TAP_TO_PAY
  }

  type Trip {
    id: ID!
    car: Car!
    driver: Driver
    startTime: String!
    endTime: String
    origin: TripLocation!
    destination: TripLocation!
    waypoints: [TripLocation!]!
    currentLocation: Location
    distance: Float
    status: TripStatus!
    departureTime: String
    remainingSeats: Int!
    bookings: [Booking!]
    totalRevenue: Float!
  }

  type TripStatusInfo {
    departureTime: String
    remainingDistance: Float
  }

  enum TripStatus {
    SCHEDULED
    STARTED
    IN_PROGRESS
    COMPLETED
    CANCELLED
  }

  type Driver {
    id: ID!
    name: String!
    phone: String!
    email: String!
    licenseNumber: String!
    rating: Float!
    totalTrips: Int!
    currentCar: Car
    totalDistance: Float!
    totalRevenue: Float!
    lastTripTimestamp: String
  }

  type Refueling {
    id: ID!
    carId: ID!
    driverId: ID!
    amount: Float!
    liters: Float!
    pricePerLiter: Float!
    location: Location!
    timestamp: String!
    receiptUrl: String
  }

  type CompanyDashboard {
    companyId: ID!
    companyCode: String!
    companyName: String!
    totalCars: Int!
    activeCars: Int!
    totalDrivers: Int!
    offlineCars: Int!
    totalTripsToday: Int!
    totalRevenueToday: Float!
    totalBookings: Int!
    pendingBookings: Int!
    averageRating: Float!
  }

  type PeakHourData {
    hour: Int!
    ticketCount: Int!
  }

  type DailyPeakHours {
    day: String!
    date: String!
    peakHours: [PeakHourData!]!
  }

  type RouteAnalysis {
    origin: String!
    destination: String!
    totalTickets: Int!
    totalRevenue: Float!
  }

  type BookingDashboard {
    companyId: ID!
    # Daily metrics
    todayTickets: Int!
    todayRevenue: Float!
    dailyTotalTickets: Int!
    dailyTotalRevenue: Float!
    
    # Weekly metrics
    weekTotalTickets: Int!
    weekTotalRevenue: Float!
    bestPerformingRoute: RouteAnalysis
    peakHourAverage: Float!
    
    # Route analysis
    routeAnalysis: [RouteAnalysis!]!
    peakHoursPerDay: [DailyPeakHours!]!
  }

  type LocationUpdate {
    carId: ID!
    location: Location!
  }

  type TripUpdate {
    tripId: ID!
    carId: ID!
    status: TripStatus!
    currentLocation: Location
    distance: Float
  }

  type Query {
    # Car queries
    getCarsByCompany(companyId: ID!): [Car!]!
    getCar(id: ID!): Car
    getCarConnectionStatus(carId: ID!): ConnectionStatus
    getCarOperationalStatus(carId: ID!): OperationalStatus
    
    # Booking queries
    getBookingsByTrip(tripId: ID!): [Booking!]!
    getBookingsByCar(carId: ID!): [Booking!]!
    
    # Trip queries
    getTripsByCar(carId: ID!): [Trip!]!
    getLiveTrips(companyId: ID!): [Trip!]!
    getTripHistory(companyId: ID!, limit: Int): [Trip!]!
    
    # Driver queries
    getCompanyDrivers(companyId: ID!): [Driver!]!
    getDriverForCar(carId: ID!): Driver
    getDriverHistory(carId: ID!): [Driver!]!
    
    # Refueling queries
    getRefuelingHistory(carId: ID!, limit: Int): [Refueling!]!
    getRefuelingByCompany(companyId: ID!): [Refueling!]!
    
    # Dashboard
    getCompanyDashboard(companyId: ID!): CompanyDashboard!
    getBookingDashboard(companyId: ID!): BookingDashboard!
  }

  type Mutation {
    # Car status management
    updateCarConnectionStatus(carId: ID!, connectionStatus: ConnectionStatus!): Car!
    updateCarOperationalStatus(carId: ID!, operationalStatus: OperationalStatus!): Car!
    
    # Location update (simulated from microservice)
    updateCarLocation(carId: ID!, latitude: Float!, longitude: Float!, address: String, bearing: Float, speed: Float): Car!
  }

  type Subscription {
    # Real-time updates
    carLocationUpdated(companyId: ID!): LocationUpdate!
    tripUpdated(companyId: ID!): TripUpdate!
    bookingCreated(companyId: ID!): Booking!
    tripBookingsUpdated(tripId: ID!): Trip!
  }
`;


// Helper functions to detect query context
function getParentQueryName(info: any): string | null {
  if (!info || !info.path) return null;
  let path = info.path;
  while (path.prev) {
    path = path.prev;
  }
  return path.key || null;
}

function isBatchQuery(queryName: string | null): boolean {
  const batchQueries = ['getCarsByCompany', 'getLiveTrips', 'getTripHistory', 'getTripsByCar'];
  return queryName ? batchQueries.includes(queryName) : false;
}

// Resolvers
const resolvers = {
  Car: {
    activeTrip: async (car: GraphQLCar, _: any, __: any, info: any) => {
      if (!tripApiClient.isEnabled()) return null;
      
      try {
        const parentQuery = getParentQueryName(info);
        const isBatch = isBatchQuery(parentQuery);
        
        // In batch context (getCarsByCompany), use cached data from database
        if (isBatch) {
          const cachedTrip = await getActiveTripDataForVehicle(car.id);
          if (cachedTrip) {
            return await mapTripToGraphQL(cachedTrip);
          }
          return null;
        }
        
        // In single context (getCar), fetch fresh data from API
        // Use a shorter timeout to prevent hanging (2 seconds)
        const timeoutPromise = new Promise<null>((resolve) => {
          setTimeout(() => resolve(null), 2000); // 2 second timeout
        });
        
        const fetchPromise = (async () => {
          // Fetch trips directly from API (no database query)
          // Use a smaller limit since we only need active trips
          const response = await tripApiClient.getTripsByCompany(car.companyId, {
            vehicleId: car.id,
            limit: 20, // Reduced from 100 for faster response
          });
          
          // Filter trips with status IN_PROGRESS or SCHEDULED
          const activeTrips = response.trips.filter(t => 
            t.status === 'IN_PROGRESS' || t.status === 'SCHEDULED'
          );
          
          if (activeTrips.length === 0) {
            return null;
          }
          
          // Priority logic: IN_PROGRESS first, then SCHEDULED with closest departure time
          let selectedTrip = activeTrips.find(t => t.status === 'IN_PROGRESS');
          
          if (!selectedTrip) {
            // No IN_PROGRESS, find SCHEDULED with closest departure time
            const scheduledTrips = activeTrips
              .filter(t => t.status === 'SCHEDULED')
              .sort((a, b) => {
                const timeA = a.departure_time || 0;
                const timeB = b.departure_time || 0;
                return timeA - timeB; // Ascending: closest first
              });
            
            selectedTrip = scheduledTrips[0];
          }
          
          if (!selectedTrip) {
            return null;
          }
          
          // Sync trip to database (non-blocking, fire and forget for performance)
          syncTrips([selectedTrip], car.companyId).catch(error => {
            logger.error('Error syncing trip in activeTrip resolver (non-blocking):', {
              carId: car.id,
              tripId: selectedTrip.id,
              error,
            });
          });
          
          // Update vehicle location if trip has location data (non-blocking)
          if (selectedTrip.current_latitude != null && selectedTrip.current_longitude != null) {
            apiClient.getVehicleById(car.id).then(vehicle => {
              updateVehicleLocationFromTrip(car.id, selectedTrip, vehicle).catch(error => {
                logger.error('Error updating vehicle location in activeTrip resolver (non-blocking):', {
                  carId: car.id,
                  error,
                });
              });
            }).catch(error => {
              logger.error('Error fetching vehicle in activeTrip resolver (non-blocking):', {
                carId: car.id,
                error,
              });
            });
          }
          
          // Map and return
          return await mapTripToGraphQL(selectedTrip);
        })();
        
        // Race between fetch and timeout
        return await Promise.race([fetchPromise, timeoutPromise]);
      } catch (error) {
        logger.error('Error getting active trip for car:', {
          carId: car.id,
          error,
        });
        return null;
      }
    },
    
    latestTripCompletionTime: async (car: GraphQLCar) => {
      if (!tripApiClient.isEnabled()) return null;
      
      try {
        const completedTrip = await getLatestCompletedTripForVehicle(car.id);
        if (!completedTrip || !completedTrip.end_time) return null;
        
        return completedTrip.end_time.toISOString();
      } catch (error) {
        logger.error('Error getting latest trip completion time:', error);
        return null;
      }
    },
    
    isOnline: (car: GraphQLCar) => {
      return car.connectionStatus === 'ONLINE';
    },
    
    driver: async (car: GraphQLCar) => {
      try {
        // Add timeout to prevent hanging
        const timeoutPromise = new Promise<null>((resolve) => {
          setTimeout(() => resolve(null), 3000); // 3 second timeout
        });
        
        const fetchPromise = (async () => {
          const driverId = await getDriverIdForVehicle(car.id);
          if (!driverId) return null;
          
          const worker = await apiClient.getWorkerById(driverId);
          if (!worker || worker.role !== 'DRIVER') return null;
          
          return mapWorkerToDriver(worker);
        })();
        
        return await Promise.race([fetchPromise, timeoutPromise]);
      } catch (error) {
        logger.error('Error getting driver for car:', { carId: car.id, error });
        return null;
      }
    },
    
    currentLocation: async (car: GraphQLCar) => {
      // If there's an active trip, use the trip's current location instead of vehicle's location
      if (tripApiClient.isEnabled()) {
        try {
          const tripId = await getActiveTripForVehicle(car.id);
          if (tripId) {
            // Fetch trips from API to get the full trip data
            const response = await tripApiClient.getTripsByCompany(car.companyId, {
              vehicleId: car.id,
              limit: 100,
            });
            
            // Find the live trip (SCHEDULED or IN_PROGRESS)
            const activeTrip = response.trips.find(t => 
              t.id.toString() === tripId && 
              (t.status === 'SCHEDULED' || t.status === 'IN_PROGRESS')
            );
            
            if (activeTrip && activeTrip.current_latitude !== undefined && activeTrip.current_longitude !== undefined) {
              // Validate trip coordinates are valid numbers
              const lat = activeTrip.current_latitude;
              const lng = activeTrip.current_longitude;
              if (typeof lat === 'number' && !isNaN(lat) && typeof lng === 'number' && !isNaN(lng)) {
                // Use trip's current location
                const mappedTrip = await mapTripToGraphQL(activeTrip);
                if (mappedTrip.currentLocation && 
                    typeof mappedTrip.currentLocation.latitude === 'number' && 
                    typeof mappedTrip.currentLocation.longitude === 'number') {
                  return mappedTrip.currentLocation;
                }
              }
            }
          }
        } catch (error) {
          logger.debug('Error getting trip current location for car, falling back to vehicle location:', error);
          // Fall through to use vehicle's location
        }
      }
      
      // Fall back to vehicle's current location, but validate coordinates
      if (!car.currentLocation) {
        return null;
      }
      
      // Validate that latitude and longitude are valid numbers
      const lat = car.currentLocation.latitude;
      const lng = car.currentLocation.longitude;
      
      if (typeof lat !== 'number' || isNaN(lat) || typeof lng !== 'number' || isNaN(lng)) {
        // Invalid coordinates, return null
        return null;
      }
      
      // Return validated location
      return car.currentLocation;
    }
  },
  
  Driver: {
    currentCar: async (driver: GraphQLDriver) => {
      try {
        const vehicleId = await getVehicleIdForDriver(driver.id);
        if (!vehicleId) return null;
        
        const vehicle = await apiClient.getVehicleById(vehicleId);
        if (!vehicle) return null;
        
        return mapVehicleToCar(vehicle);
      } catch (error) {
        logger.error('Error getting current car for driver:', error);
        return null;
      }
    },
    
    totalDistance: async (driver: GraphQLDriver) => {
      try {
        const client = await pool.connect();
        try {
          const today = new Date();
          today.setHours(0, 0, 0, 0);
          const tomorrow = new Date(today);
          tomorrow.setDate(tomorrow.getDate() + 1);
          
          // Only count completed trips
          const result = await client.query(
            `SELECT COALESCE(SUM(distance), 0) as total_distance
             FROM trips
             WHERE driver_id = $1 
             AND status = 'COMPLETED'
             AND end_time >= $2 
             AND end_time < $3`,
            [driver.id, today, tomorrow]
          );
          
          const distance = parseFloat(result.rows[0]?.total_distance || '0');
          logger.debug('Driver total distance calculated', { driverId: driver.id, distance });
          return distance;
        } finally {
          client.release();
        }
      } catch (error) {
        logger.error('Error calculating total distance', { error, driverId: driver.id });
        return 0;
      }
    },
    
    totalRevenue: async (driver: GraphQLDriver) => {
      try {
        const client = await pool.connect();
        try {
          const today = new Date();
          today.setHours(0, 0, 0, 0);
          const tomorrow = new Date(today);
          tomorrow.setDate(tomorrow.getDate() + 1);
          
          // Only count completed trips
          const result = await client.query(
            `SELECT COALESCE(SUM(price), 0) as total_revenue
             FROM trips
             WHERE driver_id = $1 
             AND status = 'COMPLETED'
             AND end_time >= $2 
             AND end_time < $3`,
            [driver.id, today, tomorrow]
          );
          
          const revenue = parseFloat(result.rows[0]?.total_revenue || '0');
          logger.debug('Driver total revenue calculated', { driverId: driver.id, revenue });
          return revenue;
        } finally {
          client.release();
        }
      } catch (error) {
        logger.error('Error calculating total revenue', { error, driverId: driver.id });
        return 0;
      }
    },
    
    lastTripTimestamp: async (driver: GraphQLDriver) => {
      try {
        const client = await pool.connect();
        try {
          const result = await client.query(
            `SELECT start_time
             FROM trips
             WHERE driver_id = $1
             ORDER BY start_time DESC
             LIMIT 1`,
            [driver.id]
          );
          
          const timestamp = result.rows.length > 0 && result.rows[0].start_time
            ? result.rows[0].start_time.toISOString()
            : null;
          logger.debug('Driver last trip timestamp retrieved', { driverId: driver.id, timestamp });
          return timestamp;
        } finally {
          client.release();
        }
      } catch (error) {
        logger.error('Error getting last trip timestamp', { error, driverId: driver.id });
        return null;
      }
    }
  },
  
  Trip: {
    car: async (trip: any) => {
      try {
        // First, try to use vehicle from trip object if available
        // trip.vehicle comes from the original ApiTrip object (before mapping)
        if (trip.vehicle && trip.vehicle.id != null && trip.vehicle.id !== 0) {
          logger.debug('Using vehicle from trip object', {
            tripId: trip.id,
            vehicleId: trip.vehicle.id,
            plate: trip.vehicle.license_plate,
            companyId: trip.vehicle.company_id,
          });
          
          // Get companyId - try from trip.vehicle.company_id or from trip context
          const companyId = trip.vehicle.company_id?.toString() || trip.companyId || '1';
          
          // Convert trip vehicle to ApiVehicle format
          const apiVehicle = convertTripVehicleToApiVehicle(trip.vehicle, companyId);
          
          // Ensure we have a valid vehicle ID
          if (!apiVehicle.id || apiVehicle.id === 'unknown') {
            logger.warn('Invalid vehicle ID from trip object', {
              tripId: trip.id,
              vehicleId: trip.vehicle.id,
            });
            return null;
          }
          
          const mappedCar = mapVehicleToCar(apiVehicle);
          
          logger.debug('Vehicle from trip object mapped to Car', {
            tripId: trip.id,
            carId: mappedCar.id,
            plate: mappedCar.plate,
            capacity: mappedCar.capacity,
          });
          
          return mappedCar;
        }
        
        // Fallback: try to fetch vehicle by ID from API
        const vehicleId = trip.vehicle_id || trip.vehicleId;
        if (!vehicleId) {
          logger.warn('No vehicle_id or vehicle object in trip', { tripId: trip.id });
          return null;
        }
        
        logger.debug('Fetching vehicle from API for trip', {
          tripId: trip.id,
          vehicleId: vehicleId.toString(),
        });
        
        const vehicle = await apiClient.getVehicleById(vehicleId.toString());
        if (!vehicle) {
          logger.warn('Vehicle not found in API', {
            tripId: trip.id,
            vehicleId: vehicleId.toString(),
          });
          return null;
        }
        
        logger.debug('Vehicle received from API', {
          tripId: trip.id,
          vehicleId: vehicle.id,
          plate: vehicle.plate,
          model: vehicle.model,
          make: vehicle.make,
          companyCode: vehicle.companyCode,
        });
        
        const mappedCar = mapVehicleToCar(vehicle);
        
        logger.debug('Vehicle mapped to Car', {
          tripId: trip.id,
          carId: mappedCar.id,
          plate: mappedCar.plate,
          model: mappedCar.model,
          make: mappedCar.make,
        });
        
        return mappedCar;
      } catch (error) {
        logger.error('Error getting car for trip:', {
          tripId: trip.id,
          vehicleId: trip.vehicle_id || trip.vehicleId,
          error,
        });
        return null;
      }
    },
    
    driver: async (trip: any) => {
      try {
        // trip.driver_id comes from the mapped trip object
        let driverId = trip.driver_id || trip.driverId;
        
        // Validate driver ID early - filter out invalid values
        if (driverId && (driverId === '0' || driverId.toString().trim() === '')) {
          driverId = null;
        }
        
        // If no driver_id in trip object, try to get it from database
        if (!driverId) {
          try {
            const client = await pool.connect();
            try {
              const result = await client.query(
                'SELECT driver_id FROM trips WHERE id = $1',
                [trip.id]
              );
              if (result.rows.length > 0 && result.rows[0].driver_id) {
                const dbDriverId = result.rows[0].driver_id;
                // Validate database driver ID
                if (dbDriverId && dbDriverId !== '0' && dbDriverId.toString().trim() !== '') {
                  driverId = dbDriverId;
                }
              }
            } finally {
              client.release();
            }
          } catch (dbError) {
            logger.debug('Error getting driver_id from database:', dbError);
          }
        }
        
        if (!driverId) {
          // If still no driver_id, try to get from trip.vehicle.driver
          if (trip.vehicle?.driver) {
            const vehicleDriver = trip.vehicle.driver;
            // Check if this is a driverless trip (name is empty/null)
            if (isDriverless(vehicleDriver)) {
              // Trip is driverless - return null since driver is now nullable
              logger.debug('Trip is driverless, returning null', {
                tripId: trip.id,
                driverId: vehicleDriver.id,
                driverName: vehicleDriver.name,
              });
              return null;
            }
            
            if (vehicleDriver.id) {
              const vehicleDriverId = vehicleDriver.id.toString();
              // Validate vehicle driver ID
              if (vehicleDriverId && vehicleDriverId !== '0' && vehicleDriverId.trim() !== '') {
                driverId = vehicleDriverId;
              }
            }
          }
        }
        
        if (!driverId) {
          // No driver found - return null since driver is now nullable
          logger.debug('No driver found for trip, returning null', {
            tripId: trip.id,
          });
          return null;
        }
        
        logger.debug('Fetching worker for trip driver', {
          tripId: trip.id,
          driverId: driverId.toString(),
        });
        
        const worker = await apiClient.getWorkerById(driverId.toString());
        if (!worker || worker.role !== 'DRIVER') {
          // Worker not found or not a driver - return null
          logger.warn('Worker not found or not a DRIVER role, returning null', {
            tripId: trip.id,
            driverId: driverId.toString(),
            workerFound: !!worker,
            role: worker?.role,
          });
          return null;
        }
        
        logger.debug('Worker found and mapped to driver', {
          tripId: trip.id,
          driverId: worker.id,
          driverName: worker.name,
          role: worker.role,
        });
        
        return mapWorkerToDriver(worker);
      } catch (error) {
        logger.error('Error getting driver for trip:', {
          tripId: trip.id,
          error,
        });
        // Return null since driver is now nullable
        return null;
      }
    },
    
    bookings: async (trip: any, _: any, __: any, info: any) => {
      try {
        const parentQuery = getParentQueryName(info);
        const isBatch = isBatchQuery(parentQuery);
        
        // In batch context (getLiveTrips, getTripHistory), use cached data from database
        if (isBatch) {
          const bookings = await getBookingsFromDatabase(trip.id);
          
          if (bookings.length === 0) {
            return [];
          }

          // Create minimal ApiTrip from GraphQL trip data for mapping
          const apiTrip: ApiTrip = {
            id: parseInt(trip.id, 10),
            route_id: 0,
            vehicle_id: parseInt(trip.vehicle_id || '0', 10),
            status: trip.status as any,
            departure_time: trip.departureTime ? Math.floor(new Date(trip.departureTime).getTime() / 1000) : 0,
            seats: trip.remainingSeats || 0,
            price: trip.totalRevenue || 0,
            created_at: trip.startTime || new Date().toISOString(),
            updated_at: trip.startTime || new Date().toISOString(),
            route: {
              id: 0,
              origin: {
                latitude: trip.origin?.latitude || 0,
                longitude: trip.origin?.longitude || 0,
                custom_name: trip.origin?.placename,
              },
              destination: {
                latitude: trip.destination?.latitude || 0,
                longitude: trip.destination?.longitude || 0,
                custom_name: trip.destination?.placename,
              },
            },
            waypoints: trip.waypoints?.map((wp: any) => ({
              location: {
                latitude: wp.latitude || 0,
                longitude: wp.longitude || 0,
                custom_name: wp.placename,
              },
            })) || [],
          } as ApiTrip;

          // Map bookings to GraphQL
          const mappedBookings = await Promise.all(
            bookings.map(booking => mapBookingToGraphQL(booking, apiTrip))
          );

          return mappedBookings;
        }
        
        // In single context, fetch from API if needed
        const bookings = await getBookingsFromDatabase(trip.id);
        
        if (bookings.length === 0) {
          return [];
        }

        // Get trip data for mapping (need route info for location coordinates)
        let apiTrip: ApiTrip | null = null;
        try {
          // Try to get trip from API to get full route data
          if (trip.companyId && tripApiClient.isEnabled()) {
            const response = await tripApiClient.getTripsByCompany(trip.companyId, {
              limit: 100,
            });
            apiTrip = response.trips.find(t => t.id.toString() === trip.id) || null;
          }
        } catch (error) {
          logger.debug('Error fetching trip from API for booking mapping', {
            tripId: trip.id,
            error,
          });
        }

        // If we don't have API trip data, create a minimal trip object from GraphQL trip
        if (!apiTrip) {
          // Use trip data from GraphQL (we have origin/destination from trip mapper)
          apiTrip = {
            id: parseInt(trip.id, 10),
            route_id: 0,
            vehicle_id: parseInt(trip.vehicle_id || '0', 10),
            status: trip.status as any,
            departure_time: trip.departureTime ? Math.floor(new Date(trip.departureTime).getTime() / 1000) : 0,
            seats: trip.remainingSeats || 0,
            price: trip.totalRevenue || 0,
            created_at: trip.startTime || new Date().toISOString(),
            updated_at: trip.startTime || new Date().toISOString(),
            route: {
              id: 0,
              origin: {
                latitude: trip.origin?.latitude || 0,
                longitude: trip.origin?.longitude || 0,
                custom_name: trip.origin?.placename,
              },
              destination: {
                latitude: trip.destination?.latitude || 0,
                longitude: trip.destination?.longitude || 0,
                custom_name: trip.destination?.placename,
              },
            },
            waypoints: trip.waypoints?.map((wp: any) => ({
              location: {
                latitude: wp.latitude || 0,
                longitude: wp.longitude || 0,
                custom_name: wp.placename,
              },
            })) || [],
          } as ApiTrip;
        }

        // Map bookings to GraphQL
        const mappedBookings = await Promise.all(
          bookings.map(booking => mapBookingToGraphQL(booking, apiTrip!))
        );

        return mappedBookings;
      } catch (error) {
        logger.error('Error getting bookings for trip:', {
          tripId: trip.id,
          error,
        });
        return [];
      }
    },
    
    totalRevenue: async (trip: any) => {
      try {
        // Calculate from paid bookings
        const revenue = await calculateTripRevenueFromBookings(trip.id);
        if (revenue > 0) {
          return revenue;
        }
        // Fallback to trip price
        return trip.price || 0;
      } catch (error) {
        logger.error('Error calculating trip revenue:', {
          tripId: trip.id,
          error,
        });
        return trip.price || 0;
      }
    }
  },
  
  Query: {
    getCarsByCompany: async (_: any, { companyId }: any) => {
      try {
        logger.info('getCarsByCompany called', { companyId });
        const startTime = Date.now();
        
        const vehicles = await apiClient.getVehiclesByCompany(companyId);
        logger.debug('Vehicles fetched from API', { 
          companyId, 
          vehicleCount: vehicles.length,
          vehicleIds: vehicles.map(v => v.id),
        });
        
        const syncStartTime = Date.now();
        await syncVehicles(vehicles);
        logger.debug('Vehicles synced to database', { 
          companyId, 
          vehicleCount: vehicles.length,
          syncDuration: Date.now() - syncStartTime,
        });
        
        const mappedCars = vehicles.map(mapVehicleToCar);
        logger.info('getCarsByCompany completed', { 
          companyId, 
          carCount: mappedCars.length,
          totalDuration: Date.now() - startTime,
        });
        
        return mappedCars;
      } catch (error) {
        logger.error('Error fetching cars by company:', { companyId, error });
        return [];
      }
    },
    
    getCar: async (_: any, { id }: any) => {
      try {
        const vehicle = await apiClient.getVehicleById(id);
        if (!vehicle) return null;
        await syncVehicles([vehicle]);
        return mapVehicleToCar(vehicle);
      } catch (error) {
        logger.error('Error fetching car:', error);
        return null;
      }
    },
    
    getCarConnectionStatus: async (_: any, { carId }: any) => {
      try {
        const vehicle = await apiClient.getVehicleById(carId);
        if (!vehicle) return null;
        return vehicle.connectionStatus;
      } catch (error) {
        logger.error('Error fetching car connection status:', error);
        return null;
      }
    },
    
    getCarOperationalStatus: async (_: any, { carId }: any) => {
      try {
        const vehicle = await apiClient.getVehicleById(carId);
        if (!vehicle) return null;
        const mapped = mapVehicleToCar(vehicle);
        return mapped.operationalStatus;
      } catch (error) {
        logger.error('Error fetching car operational status:', error);
        return null;
      }
    },
    
    getBookingsByTrip: async (_: any, { tripId }: any) => {
      if (!bookingApiClient.isEnabled()) {
        logger.debug('Booking API client is not enabled');
        return [];
      }

      try {
        logger.info('Fetching bookings for trip', { tripId });

        // Fetch bookings directly from API
        const bookings = await bookingApiClient.getBookingsByTrip(tripId);
        
        if (bookings.length === 0) {
          logger.debug('No bookings found for trip', { tripId });
          return [];
        }

        // Get trip data from API for mapping
        let apiTrip: ApiTrip | null = null;
        try {
          // Try to get trip from database first to get companyId
          const client = await pool.connect();
          try {
            const tripResult = await client.query(
              'SELECT company_id FROM trips WHERE id = $1',
              [tripId]
            );
            
            if (tripResult.rows.length > 0) {
              const companyId = tripResult.rows[0].company_id;
              if (tripApiClient.isEnabled()) {
                const response = await tripApiClient.getTripsByCompany(companyId, {
                  limit: 100,
                });
                apiTrip = response.trips.find(t => t.id.toString() === tripId) || null;
              }
            }
          } finally {
            client.release();
          }
        } catch (error) {
          logger.error('Error fetching trip data for booking mapping', {
            tripId,
            error,
          });
        }

        if (!apiTrip) {
          logger.warn('Trip not found for booking mapping', { tripId });
          return [];
        }

        // Sync bookings to database
        await syncBookingsForTrip(tripId, bookings, apiTrip);

        // Map and return bookings
        const mappedBookings = await Promise.all(
          bookings.map(booking => mapBookingToGraphQL(booking, apiTrip!))
        );

        logger.info('Bookings fetched and mapped successfully', {
          tripId,
          bookingCount: mappedBookings.length,
        });

        return mappedBookings;
      } catch (error) {
        logger.error('Error fetching bookings by trip:', {
          tripId,
          error,
        });
        return [];
      }
    },
    
    getBookingsByCar: async (_: any, { carId }: any) => {
      try {
        logger.info('Fetching bookings for car', { carId });

        // Get car's current active trip (IN_PROGRESS or SCHEDULED)
        const client = await pool.connect();
        let activeTripId: string | null = null;
        let allTripIds: string[] = [];

        try {
          // Get active trip
          const activeTripResult = await client.query(
            `SELECT id FROM trips 
             WHERE vehicle_id = $1 AND status IN ('IN_PROGRESS', 'SCHEDULED')
             ORDER BY departure_time DESC
             LIMIT 1`,
            [carId]
          );

          if (activeTripResult.rows.length > 0) {
            activeTripId = activeTripResult.rows[0].id;
          }

          // Get all trips for this car
          const allTripsResult = await client.query(
            'SELECT id FROM trips WHERE vehicle_id = $1 ORDER BY departure_time DESC',
            [carId]
          );
          allTripIds = allTripsResult.rows.map((row: any) => row.id);
        } finally {
          client.release();
        }

        // Fetch bookings for active trip from API if it exists
        if (activeTripId && bookingApiClient.isEnabled()) {
          try {
            logger.debug('Fetching bookings for active trip', {
              carId,
              activeTripId,
            });
            const bookings = await bookingApiClient.getBookingsByTrip(activeTripId);
            
            if (bookings.length > 0) {
              // Get trip data for mapping
              const dbClient = await pool.connect();
              try {
                const tripResult = await dbClient.query(
                  'SELECT company_id FROM trips WHERE id = $1',
                  [activeTripId]
                );
                
                if (tripResult.rows.length > 0) {
                  const companyId = tripResult.rows[0].company_id;
                  if (tripApiClient.isEnabled()) {
                    const response = await tripApiClient.getTripsByCompany(companyId, {
                      limit: 100,
                    });
                    const apiTrip = response.trips.find(t => t.id.toString() === activeTripId);
                    if (apiTrip) {
                      await syncBookingsForTrip(activeTripId, bookings, apiTrip);
                    }
                  }
                }
              } finally {
                dbClient.release();
              }
            }
          } catch (error) {
            logger.error('Error fetching bookings for active trip', {
              carId,
              activeTripId,
              error,
            });
          }
        }

        // Get all bookings from database for all trips of this car
        const allBookings: any[] = [];
        for (const tripId of allTripIds) {
          try {
            const bookings = await getBookingsFromDatabase(tripId);
            
            if (bookings.length > 0) {
              // Get trip data for mapping
              const dbClient = await pool.connect();
              let apiTrip: ApiTrip | null = null;
              
              try {
                const tripResult = await dbClient.query(
                  'SELECT company_id FROM trips WHERE id = $1',
                  [tripId]
                );
                
                if (tripResult.rows.length > 0 && tripApiClient.isEnabled()) {
                  const companyId = tripResult.rows[0].company_id;
                  const response = await tripApiClient.getTripsByCompany(companyId, {
                    limit: 100,
                  });
                  apiTrip = response.trips.find(t => t.id.toString() === tripId) || null;
                }
              } finally {
                dbClient.release();
              }

              if (apiTrip) {
                const mappedBookings = await Promise.all(
                  bookings.map(booking => mapBookingToGraphQL(booking, apiTrip!))
                );
                allBookings.push(...mappedBookings);
              }
            }
          } catch (error) {
            logger.error('Error getting bookings for trip', {
              carId,
              tripId,
              error,
            });
          }
        }

        logger.info('Bookings fetched for car', {
          carId,
          bookingCount: allBookings.length,
          tripCount: allTripIds.length,
        });

        return allBookings;
      } catch (error) {
        logger.error('Error fetching bookings by car:', {
          carId,
          error,
        });
        return [];
      }
    },
    
    getTripsByCar: async (_: any, { carId }: any) => {
      if (!tripApiClient.isEnabled()) {
        logger.debug('Trip API client is not enabled for getTripsByCar');
        return [];
      }
      
      try {
        logger.info('Fetching trips by car', { carId });
        
        // Get company ID from vehicle
        const vehicle = await apiClient.getVehicleById(carId);
        if (!vehicle) {
          logger.warn('Vehicle not found for getTripsByCar', { carId });
          return [];
        }
        
        logger.debug('Vehicle found, fetching trips from API', {
          carId,
          companyId: vehicle.companyId,
        });
        
        // Fetch trips directly from API (no incremental sync)
        const response = await tripApiClient.getTripsByCompany(vehicle.companyId, {
          vehicleId: carId,
          limit: 100,
        });
        
        logger.debug('Trips received from API for car', {
          carId,
          companyId: vehicle.companyId,
          totalTrips: response.trips.length,
          tripIds: response.trips.map(t => t.id),
          statuses: response.trips.map(t => t.status),
        });
        
        // Sync trips to database
        if (response.trips.length > 0) {
          logger.debug('Syncing trips to database', {
            carId,
            companyId: vehicle.companyId,
            tripCount: response.trips.length,
          });
          await syncTrips(response.trips, vehicle.companyId);
          
          // Update vehicle location from trips
          const vehicleTrip = response.trips.find(t => 
            t.vehicle_id.toString() === carId && 
            t.current_latitude != null && 
            t.current_longitude != null
          );
          if (vehicleTrip) {
            try {
              await updateVehicleLocationFromTrip(carId, vehicleTrip, vehicle);
            } catch (error) {
              logger.error('Error updating vehicle location from trip in getTripsByCar:', {
                carId,
                tripId: vehicleTrip.id,
                error,
              });
            }
          }
        }
        
        // Filter trips for this vehicle (should already be filtered by API, but double-check)
        const vehicleTrips = response.trips.filter(t => t.vehicle_id.toString() === carId);
        logger.debug('Filtered trips for vehicle', {
          carId,
          vehicleTripCount: vehicleTrips.length,
          vehicleTripIds: vehicleTrips.map(t => t.id),
        });
        
        // Map trips to GraphQL
        const mappedTrips = await Promise.all(vehicleTrips.map(t => mapTripToGraphQL(t)));
        
        // Filter out trips without valid cars (similar to getLiveTrips and getTripHistory)
        const tripsWithCars = [];
        for (const mappedTrip of mappedTrips) {
          const tripWithVehicle = mappedTrip as any;
          const hasVehicle = tripWithVehicle.vehicle || mappedTrip.vehicle_id;
          if (!hasVehicle) {
            logger.warn('Skipping trip without vehicle data', {
              tripId: mappedTrip.id,
              carId,
            });
            continue;
          }
          try {
            const carResolver = resolvers.Trip.car as any;
            const car = await carResolver(mappedTrip, {}, {} as any, {} as any);
            if (car && car.id) {
              tripsWithCars.push(mappedTrip);
            } else {
              logger.warn('Skipping trip without valid car (car resolver returned null)', {
                tripId: mappedTrip.id,
                vehicleId: mappedTrip.vehicle_id,
                carId,
                hasVehicleObject: !!tripWithVehicle.vehicle,
              });
            }
          } catch (error) {
            logger.error('Error resolving car for trip, skipping', {
              tripId: mappedTrip.id,
              vehicleId: mappedTrip.vehicle_id,
              carId,
              error,
            });
          }
        }
        
        logger.info('Trips by car processed successfully', {
          carId,
          companyId: vehicle.companyId,
          totalTripsFromAPI: response.trips.length,
          vehicleTrips: vehicleTrips.length,
          mappedTrips: mappedTrips.length,
          tripsWithCars: tripsWithCars.length,
        });
        
        return tripsWithCars;
      } catch (error) {
        logger.error('Error fetching trips by car:', {
          carId,
          error,
        });
        return [];
      }
    },
    
    getLiveTrips: async (_: any, { companyId }: any) => {
      if (!tripApiClient.isEnabled()) {
        logger.debug('Trip API client is not enabled');
        return [];
      }
      
      try {
        logger.info('Fetching live trips', { companyId });
        
        // For live trips, we need ALL active trips, not just incremental updates
        // Fetch all trips (or a large limit) to ensure we get all active ones
        const response = await tripApiClient.getTripsByCompany(companyId, {
          limit: 500, // Large limit to get all active trips
        });
        
        logger.debug('Trips received from API', {
          companyId,
          totalTrips: response.trips.length,
          tripIds: response.trips.map(t => t.id),
        });
        
        // Sync trips to database (this will update our sync tracking)
        if (response.trips.length > 0) {
          logger.debug('Syncing trips to database', {
            companyId,
            tripCount: response.trips.length,
          });
          await syncTrips(response.trips, companyId);
          
          // Update vehicle locations from trips (parallelized)
          const uniqueVehicleIds = new Set(response.trips.map(t => t.vehicle_id.toString()));
          await Promise.all(Array.from(uniqueVehicleIds).map(async (vehicleId) => {
            const vehicleTrip = response.trips.find(t => 
              t.vehicle_id.toString() === vehicleId && 
              t.current_latitude != null && 
              t.current_longitude != null
            );
            if (vehicleTrip) {
              try {
                const vehicle = await apiClient.getVehicleById(vehicleId);
                await updateVehicleLocationFromTrip(vehicleId, vehicleTrip, vehicle);
              } catch (error) {
                logger.error('Error updating vehicle location from trip in getLiveTrips:', {
                  vehicleId,
                  tripId: vehicleTrip.id,
                  error,
                });
              }
            }
          }));
        }
        
        // Filter for live trips: SCHEDULED and IN_PROGRESS only
        const liveTrips = response.trips.filter(t => 
          t.status === 'SCHEDULED' || t.status === 'IN_PROGRESS'
        );
        
        logger.debug('Filtered live trips', {
          companyId,
          liveTripCount: liveTrips.length,
          liveTripIds: liveTrips.map(t => t.id),
          statuses: liveTrips.map(t => t.status),
        });
        
        // Sort by departure_time (most recent first) and get the latest live trip per vehicle
        // If multiple live trips exist for a vehicle, the latest by departure_time is the "top active"
        const tripsByVehicle = new Map<string, typeof liveTrips[0]>();
        for (const trip of liveTrips) {
          const vehicleId = trip.vehicle_id.toString();
          const existing = tripsByVehicle.get(vehicleId);
          if (!existing) {
            tripsByVehicle.set(vehicleId, trip);
          } else {
            // Keep the trip with the latest departure_time
            const existingTime = existing.departure_time || 0;
            const currentTime = trip.departure_time || 0;
            if (currentTime > existingTime) {
              tripsByVehicle.set(vehicleId, trip);
            }
          }
        }
        
        // Map unique live trips to GraphQL
        const uniqueLiveTrips = Array.from(tripsByVehicle.values());
        logger.debug('Mapping trips to GraphQL', {
          companyId,
          uniqueTripCount: uniqueLiveTrips.length,
          uniqueTripIds: uniqueLiveTrips.map(t => t.id),
        });
        
        const mappedTrips = await Promise.all(uniqueLiveTrips.map(t => mapTripToGraphQL(t)));
        
        // Filter out trips without valid cars (parallelized)
        // Check if we have vehicle data (either from trip.vehicle or vehicle_id exists)
        const carValidationResults = await Promise.all(mappedTrips.map(async (mappedTrip) => {
          // Check if trip has vehicle data available
          const tripWithVehicle = mappedTrip as any;
          const hasVehicle = tripWithVehicle.vehicle || mappedTrip.vehicle_id;
          if (!hasVehicle) {
            logger.warn('Skipping trip without vehicle data', {
              tripId: mappedTrip.id,
            });
            return null;
          }
          
          // Try to resolve the car to ensure it's valid
          try {
            // Call the resolver - GraphQL resolvers have signature (parent, args, context, info)
            const carResolver = resolvers.Trip.car as any;
            const car = await carResolver(mappedTrip, {}, {} as any, {} as any);
            if (car && car.id) {
              return mappedTrip;
            } else {
              logger.warn('Skipping trip without valid car (car resolver returned null)', {
                tripId: mappedTrip.id,
                vehicleId: mappedTrip.vehicle_id,
                hasVehicleObject: !!(mappedTrip as any).vehicle,
              });
              return null;
            }
          } catch (error) {
            logger.error('Error resolving car for trip, skipping', {
              tripId: mappedTrip.id,
              vehicleId: mappedTrip.vehicle_id,
              error,
            });
            return null;
          }
        }));
        
        const tripsWithCars = carValidationResults.filter(t => t !== null) as typeof mappedTrips;
        
        logger.info('Live trips processed successfully', {
          companyId,
          totalTrips: response.trips.length,
          liveTrips: liveTrips.length,
          uniqueTrips: uniqueLiveTrips.length,
          mappedTrips: mappedTrips.length,
          tripsWithCars: tripsWithCars.length,
        });
        
        return tripsWithCars;
      } catch (error) {
        logger.error('Error fetching live trips:', {
          companyId,
          error,
        });
        return [];
      }
    },
    
    getTripHistory: async (_: any, { companyId, limit }: any) => {
      if (!tripApiClient.isEnabled()) return [];
      
      try {
        // Handle limit: if 0, null, undefined, or negative, use a large default
        // If limit is provided and > 0, use it; otherwise default to 100
        const effectiveLimit = (limit && limit > 0) ? limit : 100;
        
        logger.info('Fetching trip history from API', { 
          companyId, 
          requestedLimit: limit,
          effectiveLimit,
        });
        
        // Fetch trips from API directly (not considering latest from database)
        const response = await tripApiClient.getTripsByCompany(companyId, {
          limit: effectiveLimit,
        });
        
        logger.debug('Trips received from API', {
          companyId,
          totalTrips: response.trips.length,
          tripIds: response.trips.map(t => t.id),
          statuses: response.trips.map(t => t.status),
        });
        
        // Sync ALL trips to database (including IN_PROGRESS and SCHEDULED)
        if (response.trips.length > 0) {
          logger.debug('Syncing all trips to database', {
            companyId,
            tripCount: response.trips.length,
          });
          await syncTrips(response.trips, companyId);
          
          // Update vehicle locations from trips (parallelized)
          const uniqueVehicleIds = new Set(response.trips.map(t => t.vehicle_id.toString()));
          await Promise.all(Array.from(uniqueVehicleIds).map(async (vehicleId) => {
            const vehicleTrip = response.trips.find(t => 
              t.vehicle_id.toString() === vehicleId && 
              t.current_latitude != null && 
              t.current_longitude != null
            );
            if (vehicleTrip) {
              try {
                const vehicle = await apiClient.getVehicleById(vehicleId);
                await updateVehicleLocationFromTrip(vehicleId, vehicleTrip, vehicle);
              } catch (error) {
                logger.error('Error updating vehicle location from trip in getTripHistory:', {
                  vehicleId,
                  tripId: vehicleTrip.id,
                  error,
                });
              }
            }
          }));
        }
        
        // Filter out IN_PROGRESS and SCHEDULED trips from response
        const completedTrips = response.trips.filter(t => 
          t.status !== 'IN_PROGRESS' && t.status !== 'SCHEDULED'
        );
        
        logger.debug('Filtered trip history (excluding IN_PROGRESS and SCHEDULED)', {
          companyId,
          totalTrips: response.trips.length,
          completedTrips: completedTrips.length,
          filteredTripIds: completedTrips.map(t => t.id),
          filteredStatuses: completedTrips.map(t => t.status),
        });
        
        // Map filtered trips to GraphQL
        const mappedTrips = await Promise.all(completedTrips.map(t => mapTripToGraphQL(t)));
        
        // Filter out trips without valid cars (parallelized)
        // This prevents GraphQL errors for non-nullable car field
        const carValidationResults = await Promise.all(mappedTrips.map(async (mappedTrip) => {
          // Check if trip has vehicle data available
          const tripWithVehicle = mappedTrip as any;
          const hasVehicle = tripWithVehicle.vehicle || mappedTrip.vehicle_id;
          if (!hasVehicle) {
            logger.warn('Skipping trip without vehicle data', {
              tripId: mappedTrip.id,
            });
            return null;
          }
          
          // Try to resolve the car to ensure it's valid
          try {
            // Call the resolver - GraphQL resolvers have signature (parent, args, context, info)
            const carResolver = resolvers.Trip.car as any;
            const car = await carResolver(mappedTrip, {}, {} as any, {} as any);
            if (car && car.id) {
              return mappedTrip;
            } else {
              logger.warn('Skipping trip without valid car (car resolver returned null)', {
                tripId: mappedTrip.id,
                vehicleId: mappedTrip.vehicle_id,
                hasVehicleObject: !!tripWithVehicle.vehicle,
              });
              return null;
            }
          } catch (error) {
            logger.error('Error resolving car for trip, skipping', {
              tripId: mappedTrip.id,
              vehicleId: mappedTrip.vehicle_id,
              error,
            });
            return null;
          }
        }));
        
        const tripsWithCars = carValidationResults.filter(t => t !== null) as typeof mappedTrips;
        
        // Apply limit to final results
        // Use effectiveLimit to ensure we don't slice with 0 or negative values
        const limitedTrips = tripsWithCars.slice(0, effectiveLimit);
        
        logger.info('Trip history processed successfully', {
          companyId,
          requestedLimit: limit,
          effectiveLimit,
          totalTripsFromAPI: response.trips.length,
          completedTrips: completedTrips.length,
          mappedTrips: mappedTrips.length,
          tripsWithCars: tripsWithCars.length,
          returnedTrips: limitedTrips.length,
        });
        
        return limitedTrips;
      } catch (error) {
        logger.error('Error fetching trip history:', {
          companyId,
          error,
        });
        return [];
      }
    },
    
    getCompanyDrivers: async (_: any, { companyId }: any) => {
      try {
        const workers = await apiClient.getWorkersByCompany(companyId);
        await syncWorkers(workers);
        await syncVehicleDriverLinks(workers);
        
        // Filter only DRIVER role workers
        const drivers = workers.filter(w => w.role === 'DRIVER');
        return drivers.map(mapWorkerToDriver);
      } catch (error) {
        logger.error('Error fetching company drivers:', error);
        return [];
      }
    },
    
    getDriverForCar: async (_: any, { carId }: any) => {
      try {
        const driverId = await getDriverIdForVehicle(carId);
        if (!driverId) return null;
        
        const worker = await apiClient.getWorkerById(driverId);
        if (!worker || worker.role !== 'DRIVER') return null;
        
        return mapWorkerToDriver(worker);
      } catch (error) {
        logger.error('Error fetching driver for car:', error);
        return null;
      }
    },
    
    getDriverHistory: async (_: any, { carId }: any) => {
      try {
        const driverIds = await getDriverHistoryForVehicle(carId);
        if (driverIds.length === 0) return [];
        
        const drivers: GraphQLDriver[] = [];
        for (const driverId of driverIds) {
          const worker = await apiClient.getWorkerById(driverId);
          if (worker && worker.role === 'DRIVER') {
            drivers.push(mapWorkerToDriver(worker));
          }
        }
        return drivers;
      } catch (error) {
        logger.error('Error fetching driver history:', error);
        return [];
      }
    },
    
    getRefuelingHistory: () => {
      // No refueling data available
      return [];
    },
    
    getRefuelingByCompany: () => {
      // No refueling data available
      return [];
    },
    
    getCompanyDashboard: async (_: any, { companyId }: any) => {
      try {
        const vehicles = await apiClient.getVehiclesByCompany(companyId);
        await syncVehicles(vehicles);
        
        const workers = await apiClient.getWorkersByCompany(companyId);
        await syncWorkers(workers);
        await syncVehicleDriverLinks(workers);
        
        const companyCars = vehicles.map(mapVehicleToCar);
        const companyCode = companyCars.length > 0 ? companyCars[0].companyCode : '';
        
        // Get company name from vehicles API response (check for company_name or companyName field)
        // The API may return company_name even if not in TypeScript interface
        let companyName = 'Fleet Services'; // Default fallback
        if (vehicles.length > 0) {
          const firstVehicle = vehicles[0] as any;
          // Check for company_name (snake_case) or companyName (camelCase)
          companyName = firstVehicle.company_name || firstVehicle.companyName || companyCode || 'Fleet Services';
        }
        
        // Count drivers (workers with role DRIVER)
        const totalDrivers = workers.filter(w => w.role === 'DRIVER').length;
        
        // Calculate trip metrics from database (completed trips only)
        let totalTripsToday = 0;
        let totalRevenueToday = 0;
        
        try {
          const client = await pool.connect();
          try {
            const today = new Date();
            today.setHours(0, 0, 0, 0);
            const tomorrow = new Date(today);
            tomorrow.setDate(tomorrow.getDate() + 1);
            
            // Only count completed trips (status = 'COMPLETED')
            const tripResult = await client.query(
              `SELECT COUNT(*) as count, COALESCE(SUM(price), 0) as revenue
               FROM trips
               WHERE company_id = $1 
               AND status = 'COMPLETED'
               AND end_time >= $2 
               AND end_time < $3`,
              [companyId, today, tomorrow]
            );
            
            totalTripsToday = parseInt(tripResult.rows[0]?.count || '0');
            totalRevenueToday = parseFloat(tripResult.rows[0]?.revenue || '0');
            
            logger.debug('Company dashboard metrics calculated', {
              companyId,
              totalTripsToday,
              totalRevenueToday,
            });
          } finally {
            client.release();
          }
        } catch (error) {
          logger.error('Error calculating trip metrics', { error, companyId });
        }
        
        return {
          companyId,
          companyCode,
          companyName,
          totalCars: companyCars.length,
          activeCars: companyCars.filter(c => c.connectionStatus === 'ONLINE' && c.operationalStatus === 'WORKING').length,
          totalDrivers,
          offlineCars: companyCars.filter(c => c.connectionStatus === 'OFFLINE').length,
          totalTripsToday,
          totalRevenueToday,
          totalBookings: 0, // No booking data
          pendingBookings: 0, // No booking data
          averageRating: 0 // No rating data
        };
      } catch (error) {
        logger.error('Error fetching company dashboard:', error);
        throw error;
      }
    },
    
    getBookingDashboard: (_: any, { companyId }: any) => {
      // No booking data available
      return {
        companyId,
        todayTickets: 0,
        todayRevenue: 0,
        dailyTotalTickets: 0,
        dailyTotalRevenue: 0,
        weekTotalTickets: 0,
        weekTotalRevenue: 0,
        bestPerformingRoute: null,
        peakHourAverage: 0,
        routeAnalysis: [],
        peakHoursPerDay: []
      };
    }
  },
  
  Mutation: {
    updateCarConnectionStatus: async (_: any, { carId, connectionStatus }: any) => {
      try {
        const vehicle = await apiClient.getVehicleById(carId);
        if (!vehicle) {
          throw new Error(`Vehicle with id ${carId} not found`);
        }
        // Note: This mutation doesn't actually update the API, just returns the current state
        // In a real implementation, this would call an API endpoint to update the status
        const mapped = mapVehicleToCar(vehicle);
        return mapped;
      } catch (error) {
        logger.error('Error updating car connection status:', error);
        throw error;
      }
    },
    
    updateCarOperationalStatus: async (_: any, { carId, operationalStatus }: any) => {
      try {
        const vehicle = await apiClient.getVehicleById(carId);
        if (!vehicle) {
          throw new Error(`Vehicle with id ${carId} not found`);
        }
        // Note: This mutation doesn't actually update the API, just returns the current state
        // In a real implementation, this would call an API endpoint to update the status
        const mapped = mapVehicleToCar(vehicle);
        return mapped;
      } catch (error) {
        logger.error('Error updating car operational status:', error);
        throw error;
      }
    },
    
    updateCarLocation: async (_: any, { carId, latitude, longitude, address, bearing, speed }: any) => {
      try {
        const vehicle = await apiClient.getVehicleById(carId);
        if (!vehicle) {
          throw new Error(`Vehicle with id ${carId} not found`);
        }
        
        // Update location in vehicle object
        if (vehicle.currentLocation) {
          vehicle.currentLocation.latitude = latitude;
          vehicle.currentLocation.longitude = longitude;
          if (address !== undefined) vehicle.currentLocation.address = address;
          if (bearing !== undefined) vehicle.currentLocation.bearing = bearing;
          if (speed !== undefined) vehicle.currentLocation.speed = speed;
          vehicle.currentLocation.timestamp = new Date().toISOString();
        }
        vehicle.lastUpdated = new Date().toISOString();
        
        // Sync to database
        await syncVehicles([vehicle]);
        
        const mapped = mapVehicleToCar(vehicle);
        
        // Publish location update
        if (mapped.currentLocation) {
          pubsub.publish('CAR_LOCATION_UPDATED', {
            carLocationUpdated: {
              carId,
              location: mapped.currentLocation
            }
          });
        }
        
        return mapped;
      } catch (error) {
        logger.error('Error updating car location:', error);
        throw error;
      }
    }
  },
  
  Subscription: {
    carLocationUpdated: {
      subscribe: (_: any, { companyId }: any) => {
        return pubsub.asyncIterator(['CAR_LOCATION_UPDATED']);
      }
    },
    
    tripUpdated: {
      subscribe: (_: any, { companyId }: any) => {
        return pubsub.asyncIterator(['TRIP_UPDATED']);
      }
    },
    
    bookingCreated: {
      subscribe: (_: any, { companyId }: any) => {
        return pubsub.asyncIterator(['BOOKING_CREATED']);
      }
    },
    
    tripBookingsUpdated: {
      subscribe: (_: any, { tripId }: any) => {
        return pubsub.asyncIterator([`TRIP_BOOKINGS_UPDATED_${tripId}`]);
      }
    }
  }
};

// POST endpoints for aggregator updates
function setupPostEndpoints(app: express.Application) {
  // POST endpoint for vehicle updates
  app.post('/company/:companyId/vehicle', express.json(), async (req, res) => {
    try {
      const { companyId } = req.params;
      const vehicles: ApiVehicle[] = req.body;

      logger.info('Received vehicle update', {
        companyId,
        count: vehicles.length,
        vehicleIds: vehicles.map(v => v.id),
      });

      // Store each vehicle in database
      for (const vehicle of vehicles) {
        await storeVehicle(vehicle);
      }

      res.status(200).json({ message: 'Vehicles stored successfully', count: vehicles.length });
    } catch (error) {
      const { companyId } = req.params;
      logger.error('Error processing vehicle update', { error, companyId });
      res.status(500).json({ error: 'Failed to process vehicle update' });
    }
  });

  // POST endpoint for worker updates
  app.post('/company/:companyId/worker', express.json(), async (req, res) => {
    try {
      const { companyId } = req.params;
      const workers: ApiWorker[] = req.body;

      logger.info('Received worker update', {
        companyId,
        count: workers.length,
        workerIds: workers.map(w => w.id),
      });

      // Store each worker in database
      for (const worker of workers) {
        await storeWorker(worker);
      }

      res.status(200).json({ message: 'Workers stored successfully', count: workers.length });
    } catch (error) {
      const { companyId } = req.params;
      logger.error('Error processing worker update', { error, companyId });
      res.status(500).json({ error: 'Failed to process worker update' });
    }
  });

  // POST endpoint for trip updates
  app.post('/company/:companyId/trips', express.json(), async (req, res) => {
    try {
      const { companyId } = req.params;
      const body = req.body;
      
      // Handle both single trip object and array of trips
      const trips: ApiTrip[] = Array.isArray(body) ? body : [body];

      logger.info('Received trip update', {
        companyId,
        count: trips.length,
        tripIds: trips.map(t => t.id),
        isBatch: Array.isArray(body),
      });

      // Store each trip in database
      for (const trip of trips) {
        await storeTrip(trip, companyId);
      }

      res.status(200).json({ message: 'Trips stored successfully', count: trips.length });
    } catch (error) {
      const { companyId } = req.params;
      logger.error('Error processing trip update', { error, companyId });
      res.status(500).json({ error: 'Failed to process trip update' });
    }
  });
}

// Server setup
const startServer = async () => {
  logger.info('Starting server initialization...');
  // Initialize database
  logger.info('Initializing database connection...');
  try {
    await initializeDatabase();
    logger.info('Database connection established successfully');
  } catch (error) {
    logger.error('Failed to initialize database', { error });
    process.exit(1);
  }

  const app = express();
  const httpServer = createServer(app);
  
  // Add request logging middleware
  app.use(requestLogger);
  
  // Setup POST endpoints for aggregator
  setupPostEndpoints(app);
  
  const schema = makeExecutableSchema({ typeDefs, resolvers });
  
  logger.info('Setting up WebSocket server...');
  const wsServer = new WebSocketServer({
    server: httpServer,
    path: '/graphql',
  });
  
  wsServer.on('connection', (ws, req) => {
    const ip = req.socket.remoteAddress;
    logger.debug('New WebSocket connection', { ip });
    
    ws.on('error', (error) => {
      logger.error('WebSocket error', { error, ip });
    });
    
    ws.on('close', () => {
      logger.debug('WebSocket connection closed', { ip });
    });
  });
  
  const serverCleanup = useServer({ schema }, wsServer);
  
  const server = new ApolloServer({
    schema,
    plugins: [
      ApolloServerPluginDrainHttpServer({ httpServer }),
      {
        async serverWillStart() {
          return {
            async drainServer() {
              await serverCleanup.dispose();
            },
          };
        },
      },
    ],
  });
  
  await server.start();
  
  app.use(
    '/graphql',
    cors<cors.CorsRequest>(),
    express.json(),
    expressMiddleware(server)
  );
  
  const PORT = process.env.PORT || 4000;
  await new Promise<void>((resolve) => httpServer.listen({ port: PORT }, resolve));
  logger.info(`🚀 Server ready at http://localhost:${PORT}/graphql`);
  
  // Log server information
  logger.info('Server environment:', {
    nodeEnv: process.env.NODE_ENV || 'development',
    port: PORT,
    pid: process.pid,
  });
  logger.info('GraphQL server started', {
    httpPort: PORT,
    subscriptionEndpoint: `ws://localhost:${PORT}/graphql`,
    postEndpoints: ['/company/:companyId/vehicle', '/company/:companyId/worker', '/company/:companyId/trips'],
  });
}

startServer().catch((error) => {
  logger.error('Failed to start server', { error });
  process.exit(1);
});

