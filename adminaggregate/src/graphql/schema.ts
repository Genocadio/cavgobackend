import { gql } from "graphql-tag";
import * as db from "../db";
import type { Car, Driver, Trip, Destination, PackageStatus } from "../types";
import { pubsub, TRIGGERS } from "../services/pubsub";
import { resolveDateRange } from "../metrics/dateRange";
import { ACTIVE_PACKAGE_STATUSES, normalizePackageStatuses } from "../metrics/packageStatus";
import {
  assertCarAccess,
  assertCompanyAccess,
  assertDriverAccess,
  assertTripAccess,
  type AuthContext,
} from "../auth/context";

type Context = { auth?: AuthContext };

const typeDefs = gql`
  enum DriverStatus {
    ACTIVE
    INACTIVE
    SUSPENDED
    PENDING_VERIFICATION
  }

  enum VehicleStatus {
    AVAILABLE
    MAINTENANCE
    OUT_OF_SERVICE
    OCCUPIED
  }

  enum TripStatus {
    scheduled
    in_progress
    completed
    cancelled
  }

  enum BookingStatus {
    pending
    confirmed
    cancelled
    completed
    used
    expired
  }

  enum PaymentType {
    cash
    epayment
    card
  }

  enum PackageStatus {
    CREATED
    ACCEPTED
    PICKED_UP
    IN_TRANSIT
    PENDING_CONFIRMATION
    DELIVERED
    COMPLETED
    CANCELLED
    ORIGIN_OFFICE
    ASSIGNED_DRIVER
    DESTINATION_OFFICE
    READY_FOR_COLLECTION
  }

  enum PackageOrigin {
    PRIVATE
    FLEET
  }

  enum DeliveryType {
    OPEN
    FIXED_ROUTE
  }

  type LatLang {
    lat: Float!
    lng: Float!
  }

  type CurrentLocation {
    location: LatLang!
    speed: Float!
    bearing: Float!
    timestamp: Float!
  }

  type Driver {
    id: ID!
    name: String!
    phoneNumber: String!
    email: String!
    status: DriverStatus!
    currentCar: Car
    activeTrip: Trip
    companyId: ID!
  }

  type Car {
    id: ID!
    plate: String!
    model: String!
    capacity: Int!
    status: VehicleStatus!
    isOnline: Boolean!
    currentLocation: CurrentLocation
    currentDriver: Driver
    activeTrip: Trip
    companyId: ID!
  }

  type CarPage {
    items: [Car!]!
    total: Int!
    limit: Int!
    offset: Int!
  }

  type DriverCarAssignment {
    car: Car!
    driver: Driver
  }

  type TripLocation {
    id: ID!
    addres: String!
    lat: Float!
    lng: Float!
  }

  type Destination {
    id: ID!
    addres: String!
    lat: Float!
    lng: Float!
    index: Int!
    fare: Float!
    remainingDistance: Float
    isPassed: Boolean!
    passedTime: Float
  }

  type Trip {
    id: ID!
    carDriver: DriverCarAssignment!
    origin: TripLocation!
    destinations: [Destination!]!
    status: TripStatus!
    totalDistance: Float!
    createdAt: Float!
    updatedAt: Float!
  }

  type PerDestinationMetrics {
    destinationId: ID!
    numberOfBookings: Int!
    totalRevenue: Float!
  }

  type DriverMetrics {
    driverId: ID!
    """Passenger fare revenue from qualifying bookings."""
    totalRevenue: Float!
    totalTrips: Int!
    totalDistance: Float!
    """Reserved for package delivery fees; no ledger exists yet."""
    packageDeliveryRevenue: Float!
  }

  type CarMetrics {
    carId: ID!
    """Passenger fare revenue from qualifying bookings."""
    totalRevenue: Float!
    totalTrips: Int!
    totalDistance: Float!
    """Reserved for package delivery fees; no ledger exists yet."""
    packageDeliveryRevenue: Float!
  }

  type TripMetrics {
    tripId: ID!
    companyId: ID!
    totalFare: Float!
    """Reserved for package delivery fees; no ledger exists yet."""
    packageDeliveryRevenue: Float!
    totalDistance: Float!
    totalDuration: Float!
    startedAt: Float
    completedAt: Float
    tripCreatedAt: Float!
    perDestinationMetrics: [PerDestinationMetrics!]!
  }

  type CompanyInfo {
    id: ID!
    name: String!
    companyCode: String!
    address: String
  }

  type CompanyDashboard {
    company: CompanyInfo!
    totalCars: Int!
    totalDrivers: Int!
    activeBuses: Int!
    todayTrips: Int!
    ongoingTrips: Int!
  }

  enum Granularity {
    hourly
    daily
    weekly
    monthly
  }

  type TimeSeriesPoint {
    label: String!
    value: Float!
  }

  type TimeSeries {
    granularity: Granularity!
    unit: String!
    data: [TimeSeriesPoint!]!
  }

  type CompanyPeriodMetrics {
    companyId: ID!
    period: String!
    startTime: Float!
    endTime: Float!
    totalTrips: Int!
    completedTrips: Int!
    cancelledTrips: Int!
    inProgressTrips: Int!
    scheduledTrips: Int!
    """Passenger fare revenue from qualifying bookings."""
    totalRevenue: Float!
    revenueFromCompletedTrips: Float!
    """Reserved for package delivery fees; no ledger exists yet."""
    packageDeliveryRevenue: Float!
    totalDistance: Float!
    totalDuration: Float!
    averageTripDistance: Float!
    averageTripDuration: Float!
    uniqueDrivers: Int!
    uniqueCars: Int!
    tripsByStatus: TripStatusCounts
    revenueSeries: TimeSeries
    tripsSeries: TimeSeries
  }

  type TripStatusCounts {
    completed: Int!
    cancelled: Int!
    in_progress: Int!
    scheduled: Int!
  }

  """Immutable snapshot of the driver/trip/vehicle at a custody handover."""
  type PackageCarrierLeg {
    id: ID!
    driverId: ID!
    tripId: ID
    companyId: ID
    companyRefId: ID
    vehicleId: ID
    linkedAt: Float!
    fromStatus: String
    toStatus: String
  }

  type CompanyPackage {
    id: ID!
    trackingCode: String!
    deliveryType: DeliveryType!
    packageOrigin: PackageOrigin!
    status: PackageStatus!
    companyId: ID
    companyRefId: ID
    tripId: ID
    createdAt: Float!
    updatedAt: Float!
    carrierLegs: [PackageCarrierLeg!]!
  }

  """Packages handed to a driver on one trip (or an unassigned bucket)."""
  type PackageActivityGroup {
    tripId: ID
    carId: ID
    plate: String
    driverId: ID
    driverName: String
    packageCount: Int!
  }

  type CompanyPackageActivity {
    companyId: ID!
    startTime: Float!
    endTime: Float!
    totalPackages: Int!
    inProgressPackages: Int!
    packages: [CompanyPackage!]!
    groups: [PackageActivityGroup!]!
  }

  type SnapshotSeats {
    pickup: Int!
    dropoff: Int!
    pendingPayment: Int!
    availableFromHere: Int!
    totalAmountPaid: Float!
    totalAmountPending: Float!
  }

  type SnapshotLocation {
    locationId: ID!
    addres: String!
    type: String!
    order: Int!
    status: String!
    seats: SnapshotSeats!
  }

  type SnapshotCapacity {
    totalSeats: Int!
    availableSeats: Int!
    occupiedSeats: Int!
    pendingPaymentSeats: Int!
    totalAmountPaid: Float!
    totalAmountPending: Float!
  }

  type SnapshotSummary {
    totalTickets: Int!
    paidTickets: Int!
    pendingPayments: Int!
    completedDropoffs: Int!
  }

  type TripSnapshot {
    tripId: ID!
    tripStatus: String!
    lastUpdated: String!
    capacity: SnapshotCapacity!
    locations: [SnapshotLocation!]!
    summary: SnapshotSummary!
  }

  type Query {
    driver(id: ID!): Driver
    car(id: ID!): Car
    tripsByCar(carId: ID!): [Trip!]!
    tripsByDriver(driverId: ID!): [Trip!]!
    carsByCompany(companyId: ID!, limit: Int, offset: Int): CarPage!
    driversByCompany(companyId: ID!): [Driver!]!
    getCarsByCompany(companyId: ID!, limit: Int, offset: Int): CarPage!
    getDriversByCompany(companyId: ID!): [Driver!]!
    driverMetrics(driverId: ID!, startDate: String, endDate: String): DriverMetrics
    carMetrics(carId: ID!, startDate: String, endDate: String): CarMetrics
    tripMetrics(tripId: ID!): TripMetrics
    companyDashboard(companyId: ID!): CompanyDashboard
    companyMetrics(companyId: ID!, startTime: Int, endTime: Int): CompanyPeriodMetrics
    tripsByCompany(companyId: ID!): [Trip!]!
    getActiveCompanyTrips(companyId: ID!): [Trip!]!
    getTripSnapshot(tripId: ID!): TripSnapshot
    companyPackageActivity(
      companyId: ID!
      startDate: String
      endDate: String
      statuses: [PackageStatus!]
    ): CompanyPackageActivity
  }

  type Subscription {
    activeCompanyTrips(companyId: ID!): [Trip!]!
    trip(tripId: ID!): Trip
    tripSnapshot(tripId: ID!): TripSnapshot
    companyPackageUpdates(companyId: ID!): CompanyPackageActivity
  }
`;

type DriverResolver = Driver & {
  currentCar: () => Promise<Car | null>;
  activeTrip: () => Promise<Trip | null>;
};

type CarResolver = Car & {
  currentDriver: () => Promise<Driver | null>;
  activeTrip: () => Promise<Trip | null>;
};

function wrapDriver(driver: Driver): DriverResolver {
  return {
    ...driver,
    currentCar: async () => {
      const assignment = await db.assignmentRepository.getDriverCarAssignmentByDriverId(driver.id);
      return assignment ? wrapCar(assignment.car) : null;
    },
    activeTrip: async () => {
      const trip = await db.tripRepository.getActiveTripByDriverId(driver.id);
      return trip ? wrapTrip(trip) : null;
    },
  };
}

function wrapCar(car: Car): CarResolver {
  return {
    ...car,
    currentDriver: async () => {
      const assignment = await db.assignmentRepository.getDriverCarAssignmentByCarId(car.id);
      return assignment && assignment.driver ? wrapDriver(assignment.driver) : null;
    },
    activeTrip: async () => {
      const trip = await db.tripRepository.getActiveTripByCarId(car.id);
      return trip ? wrapTrip(trip) : null;
    },
  };
}

function wrapTrip(trip: Trip): Trip {
  return {
    ...trip,
    carDriver: {
      car: wrapCar(trip.carDriver.car),
      driver: trip.carDriver.driver ? wrapDriver(trip.carDriver.driver) : null,
    },
  };
}

const resolvers = {
  Destination: {
    // Backwards compatibility: map legacy isPassede field to the new isPassed field
    isPassed: (destination: Destination) => destination.isPassed ?? destination.isPassede ?? false,
  },
  Driver: {
    name: (driver: Driver) => {
      const firstName = driver.firstName || "";
      const lastName = driver.lastName || "";
      return `${firstName} ${lastName}`.trim() || "Unknown Driver";
    },
  },
  Query: {
    driver: async (_: unknown, { id }: { id: string }, ctx: Context) => {
      await assertDriverAccess(ctx.auth, id);
      const driver = await db.driverRepository.getDriverById(id);
      return driver ? wrapDriver(driver) : null;
    },
    car: async (_: unknown, { id }: { id: string }, ctx: Context) => {
      await assertCarAccess(ctx.auth, id);
      const car = await db.carRepository.getCarById(id);
      return car ? wrapCar(car) : null;
    },
    tripsByCar: async (_: unknown, { carId }: { carId: string }, ctx: Context) => {
      await assertCarAccess(ctx.auth, carId);
      return (await db.tripRepository.getTripsByCarId(carId)).map(wrapTrip);
    },
    tripsByDriver: async (_: unknown, { driverId }: { driverId: string }, ctx: Context) => {
      await assertDriverAccess(ctx.auth, driverId);
      return (await db.tripRepository.getTripsByDriverId(driverId)).map(wrapTrip);
    },
    carsByCompany: async (
      _: unknown,
      { companyId, limit, offset }: { companyId: string; limit?: number; offset?: number },
      ctx: Context
    ) => {
      assertCompanyAccess(ctx.auth, companyId);
      const result = await db.carRepository.getCarsByCompany(companyId, limit, offset);
      return {
        items: result.items.map(wrapCar),
        total: result.total,
        limit: result.limit,
        offset: result.offset,
      };
    },
    driversByCompany: async (_: unknown, { companyId }: { companyId: string }, ctx: Context) => {
      assertCompanyAccess(ctx.auth, companyId);
      return (await db.driverRepository.getDriversByCompany(companyId)).map(wrapDriver);
    },
    getCarsByCompany: async (
      _: unknown,
      { companyId, limit, offset }: { companyId: string; limit?: number; offset?: number },
      ctx: Context
    ) => {
      assertCompanyAccess(ctx.auth, companyId);
      const result = await db.carRepository.getCarsByCompany(companyId, limit, offset);
      return {
        items: result.items.map(wrapCar),
        total: result.total,
        limit: result.limit,
        offset: result.offset,
      };
    },
    getDriversByCompany: async (_: unknown, { companyId }: { companyId: string }, ctx: Context) => {
      assertCompanyAccess(ctx.auth, companyId);
      return (await db.driverRepository.getDriversByCompany(companyId)).map(wrapDriver);
    },
    driverMetrics: async (
      _: unknown,
      { driverId, startDate, endDate }: { driverId: string; startDate?: string; endDate?: string },
      ctx: Context
    ) => {
      await assertDriverAccess(ctx.auth, driverId);
      if (startDate || endDate) {
        return db.metricsRepository.getDriverMetricsByDateRange(driverId, startDate, endDate);
      }
      return db.metricsRepository.getDriverMetrics(driverId);
    },
    carMetrics: async (
      _: unknown,
      { carId, startDate, endDate }: { carId: string; startDate?: string; endDate?: string },
      ctx: Context
    ) => {
      await assertCarAccess(ctx.auth, carId);
      if (startDate || endDate) {
        return db.metricsRepository.getCarMetricsByDateRange(carId, startDate, endDate);
      }
      return db.metricsRepository.getCarMetricsByDateRange(carId);
    },
    tripMetrics: async (_: unknown, { tripId }: { tripId: string }, ctx: Context) => {
      await assertTripAccess(ctx.auth, tripId);
      return db.metricsRepository.getTripMetrics(tripId);
    },
    companyDashboard: async (_: unknown, { companyId }: { companyId: string }, ctx: Context) => {
      assertCompanyAccess(ctx.auth, companyId);
      return db.companyRepository.getCompanyDashboardStats(companyId);
    },
    companyMetrics: async (
      _: unknown,
      { companyId, startTime, endTime }: { companyId: string; startTime?: number; endTime?: number },
      ctx: Context
    ) => {
      assertCompanyAccess(ctx.auth, companyId);
      return db.companyMetricsRepository.getCompanyPeriodMetrics(companyId, startTime, endTime);
    },
    tripsByCompany: async (_: unknown, { companyId }: { companyId: string }, ctx: Context) => {
      assertCompanyAccess(ctx.auth, companyId);
      return (await db.tripRepository.getTripsByCompanyId(companyId)).map(wrapTrip);
    },
    getActiveCompanyTrips: async (_: unknown, { companyId }: { companyId: string }, ctx: Context) => {
      assertCompanyAccess(ctx.auth, companyId);
      return (await db.tripRepository.getActiveTripsByCompanyId(companyId)).map(wrapTrip);
    },
    getTripSnapshot: async (_: unknown, { tripId }: { tripId: string }, ctx: Context) => {
      await assertTripAccess(ctx.auth, tripId);
      // Try existing snapshot first
      const existing = await db.snapshotRepository.getSnapshot(tripId);
      if (existing) return existing;

      // If no snapshot yet, attempt to create an initial zeroed snapshot
      const trip = await db.tripRepository.getTripById(tripId);
      if (!trip) {
        throw new Error(`Trip ${tripId} not found, cannot create snapshot`);
      }

      return db.snapshotRepository.createInitialSnapshot(trip, trip.carDriver.car.capacity);
    },
    companyPackageActivity: async (
      _: unknown,
      {
        companyId,
        startDate,
        endDate,
        statuses,
      }: { companyId: string; startDate?: string; endDate?: string; statuses?: PackageStatus[] },
      ctx: Context
    ) => {
      assertCompanyAccess(ctx.auth, companyId);
      const requested = normalizePackageStatuses(statuses);
      return db.packageRepository.getCompanyPackageActivity(
        companyId,
        resolveDateRange(startDate, endDate),
        requested ?? ACTIVE_PACKAGE_STATUSES
      );
    },
  },
  Subscription: {
    activeCompanyTrips: {
      subscribe: async (_: unknown, { companyId }: { companyId: string }, ctx: Context) => {
        assertCompanyAccess(ctx.auth, companyId);
        // Get initial data
        const initialTrips = (await db.tripRepository.getActiveTripsByCompanyId(companyId)).map(wrapTrip);
        
        // Create async iterator that yields initial data first, then listens for updates
        const trigger = TRIGGERS.COMPANY_TRIPS_UPDATED(companyId);
        const asyncIterable = pubsub.asyncIterator<{ activeCompanyTrips: Trip[] }>([trigger]);
        
        // Create an async generator that yields initial data, then streams updates
        async function* subscriptionGenerator() {
          // Yield initial data immediately
          yield { activeCompanyTrips: initialTrips };
          
          // Then yield updates from the pubsub
          for await (const payload of asyncIterable) {
            yield payload;
          }
        }
        
        return subscriptionGenerator();
      },
      resolve: (payload: { activeCompanyTrips: Trip[] }) => {
        return payload.activeCompanyTrips.map(wrapTrip);
      },
    },
    trip: {
      subscribe: async (_: unknown, { tripId }: { tripId: string }, ctx: Context) => {
        await assertTripAccess(ctx.auth, tripId);
        // Get initial trip data
        const initialTrip = await db.tripRepository.getTripById(tripId);
        
        // Create async iterator for trip updates
        const trigger = TRIGGERS.TRIP_UPDATED(tripId);
        const asyncIterable = pubsub.asyncIterator<{ trip: Trip | null }>([trigger]);
        
        // Create an async generator that yields initial data, then streams updates
        async function* subscriptionGenerator() {
          // Yield initial data immediately
          yield { trip: initialTrip };
          
          // Then yield updates from the pubsub
          for await (const payload of asyncIterable) {
            yield payload;
          }
        }
        
        return subscriptionGenerator();
      },
      resolve: (payload: { trip: Trip | null }) => {
        return payload.trip ? wrapTrip(payload.trip) : null;
      },
    },
    tripSnapshot: {
      subscribe: async (_: unknown, { tripId }: { tripId: string }, ctx: Context) => {
        await assertTripAccess(ctx.auth, tripId);
        // Get initial snapshot data
        const initialSnapshot = await db.snapshotRepository.getSnapshot(tripId);
        
        // Create async iterator for snapshot updates
        const trigger = TRIGGERS.TRIP_SNAPSHOT_UPDATED(tripId);
        const asyncIterable = pubsub.asyncIterator<{ tripSnapshot: any }>([trigger]);
        
        // Create an async generator that yields initial data, then streams updates
        async function* subscriptionGenerator() {
          // Yield initial data immediately
          if (initialSnapshot) {
            yield { tripSnapshot: initialSnapshot };
          }
          
          // Then yield updates from the pubsub
          for await (const payload of asyncIterable) {
            yield payload;
          }
        }
        
        return subscriptionGenerator();
      },
      resolve: (payload: { tripSnapshot: any }) => {
        return payload.tripSnapshot;
      },
    },
    companyPackageUpdates: {
      subscribe: async (_: unknown, { companyId }: { companyId: string }, ctx: Context) => {
        assertCompanyAccess(ctx.auth, companyId);
        const range = resolveDateRange();
        const initial = await db.packageRepository.getCompanyPackageActivity(
          companyId,
          range,
          ACTIVE_PACKAGE_STATUSES
        );

        const trigger = TRIGGERS.COMPANY_PACKAGES_UPDATED(companyId);
        const asyncIterable = pubsub.asyncIterator<{ companyId: string }>([trigger]);

        async function* subscriptionGenerator() {
          yield { companyPackageUpdates: initial };
          for await (const _payload of asyncIterable) {
            const refreshed = await db.packageRepository.getCompanyPackageActivity(
              companyId,
              resolveDateRange(),
              ACTIVE_PACKAGE_STATUSES
            );
            yield { companyPackageUpdates: refreshed };
          }
        }

        return subscriptionGenerator();
      },
      resolve: (payload: { companyPackageUpdates: unknown }) => payload.companyPackageUpdates,
    },
  },
};

export { typeDefs, resolvers };
