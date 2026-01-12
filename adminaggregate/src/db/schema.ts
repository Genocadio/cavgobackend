import {
  boolean,
  date,
  doublePrecision,
  integer,
  numeric,
  pgEnum,
  pgTable,
  serial,
  text,
  timestamp,
} from "drizzle-orm/pg-core";
import { sql } from "drizzle-orm";

export const statusEnum = pgEnum("status_enum", [
  "ACTIVE",
  "INACTIVE",
  "SUSPENDED",
  "PENDING_VERIFICATION",
]);
export const companyStatusEnum = pgEnum("company_status_enum", [
  "ACTIVE",
  "INACTIVE",
  "SUSPENDED",
]);
export const vehicleStatusEnum = pgEnum("vehicle_status_enum", [
  "AVAILABLE",
  "MAINTENANCE",
  "OUT_OF_SERVICE",
  "OCCUPIED",
]);
export const tripStatusEnum = pgEnum("trip_status_enum", [
  "scheduled",
  "in_progress",
  "completed",
  "cancelled",
]);
export const bookingStatusEnum = pgEnum("booking_status_enum", [
  "pending",
  "confirmed",
  "cancelled",
  "completed",
  "used",
  "expired",
]);
export const paymentTypeEnum = pgEnum("payment_type_enum", ["cash", "epayment", "card"]);

export const companies = pgTable("companies", {
  id: text("id").primaryKey(),
  companyName: text("company_name").notNull(),
  email: text("email").notNull(),
  phone: text("phone").notNull(),
  address: text("address"),
  city: text("city"),
  companyCode: text("company_code").notNull(),
  status: companyStatusEnum("status").notNull(),
  createdAt: timestamp("created_at", { withTimezone: true }),
  updatedAt: timestamp("updated_at", { withTimezone: true }),
  createdBy: text("created_by"),
  updatedBy: text("updated_by"),
});

export const drivers = pgTable("drivers", {
  id: text("id").primaryKey(),
  firstName: text("first_name").notNull(),
  lastName: text("last_name").notNull(),
  phoneNumber: text("phone_number").notNull(),
  email: text("email").notNull(),
  status: statusEnum("status").notNull(),
  companyId: text("company_id").notNull(),
  dateOfBirth: date("date_of_birth"),
  address: text("address"),
  licenseNumber: text("license_number"),
  licenseExpiry: date("license_expiry"),
  role: text("role"),
  createdAt: timestamp("created_at", { withTimezone: true }),
  updatedAt: timestamp("updated_at", { withTimezone: true }),
});

export const cars = pgTable("cars", {
  id: text("id").primaryKey(),
  plate: text("plate").notNull(),
  model: text("model").notNull(),
  make: text("make"),
  vehicleType: text("vehicle_type"),
  capacity: integer("capacity").notNull(),
  status: vehicleStatusEnum("status").notNull(),
  isOnline: boolean("is_online").notNull().default(false),
  companyId: text("company_id").notNull(),
  currentLocationLatitude: doublePrecision("current_location_latitude"),
  currentLocationLongitude: doublePrecision("current_location_longitude"),
  currentLocationSpeed: doublePrecision("current_location_speed"),
  currentLocationBearing: doublePrecision("current_location_bearing"),
  currentLocationTimestamp: timestamp("current_location_timestamp", { withTimezone: true }),
  createdAt: timestamp("created_at", { withTimezone: true }),
  updatedAt: timestamp("updated_at", { withTimezone: true }),
});

export const tripLocations = pgTable("trip_locations", {
  id: text("id").primaryKey(),
  address: text("address").notNull(),
  latitude: doublePrecision("latitude").notNull(),
  longitude: doublePrecision("longitude").notNull(),
  createdAt: timestamp("created_at", { withTimezone: true }).defaultNow(),
});

export const driverCarAssignments = pgTable("driver_car_assignments", {
  id: serial("id").primaryKey(),
  driverId: text("driver_id").references(() => drivers.id),
  carId: text("car_id").notNull().references(() => cars.id),
  assignedAt: timestamp("assigned_at", { withTimezone: true }).defaultNow(),
});

export const trips = pgTable("trips", {
  id: text("id").primaryKey(),
  driverCarAssignmentId: integer("driver_car_assignment_id")
    .notNull()
    .references(() => driverCarAssignments.id),
  originLocationId: text("origin_location_id").notNull().references(() => tripLocations.id),
  status: tripStatusEnum("status").notNull(),
  totalDistance: doublePrecision("total_distance").notNull().default(sql`0`),
  createdAt: timestamp("created_at", { withTimezone: true }).defaultNow(),
  updatedAt: timestamp("updated_at", { withTimezone: true }).defaultNow(),
});

export const tripDestinations = pgTable("trip_destinations", {
  id: text("id").primaryKey(),
  tripId: text("trip_id").notNull().references(() => trips.id),
  locationId: text("location_id").notNull().references(() => tripLocations.id),
  order: integer("order"), // Original order from incoming event (for waypoints only, null for origin/destination)
  index: integer("index").notNull(),
  fare: numeric("fare", { precision: 12, scale: 2 }).notNull(),
  remainingDistance: doublePrecision("remaining_distance"),
  isPassede: boolean("is_passede").notNull().default(false),
  passedTime: doublePrecision("passed_time"),
  createdAt: timestamp("created_at", { withTimezone: true }).defaultNow(),
});

export const bookings = pgTable("bookings", {
  id: text("id").primaryKey(),
  tripId: text("trip_id").notNull().references(() => trips.id),
  passengerName: text("passenger_name"),
  passengerPhone: text("passenger_phone"),
  pickupLocationId: text("pickup_location_id").notNull().references(() => tripLocations.id),
  dropoffLocationId: text("dropoff_location_id").notNull().references(() => tripLocations.id),
  numberOfTickets: integer("number_of_tickets").notNull(),
  totalFare: numeric("total_fare", { precision: 12, scale: 2 }).notNull(),
  paymentType: paymentTypeEnum("payment_type"),
  status: bookingStatusEnum("status").notNull(),
  createdAt: timestamp("created_at", { withTimezone: true }).defaultNow(),
  updatedAt: timestamp("updated_at", { withTimezone: true }).defaultNow(),
});

export const driverMetrics = pgTable("driver_metrics", {
  driverId: text("driver_id").primaryKey().references(() => drivers.id),
  totalRevenue: numeric("total_revenue", { precision: 14, scale: 2 })
    .notNull()
    .default(sql`0`),
  totalTrips: integer("total_trips").notNull().default(0),
  totalDistance: doublePrecision("total_distance").notNull().default(sql`0`),
  updatedAt: timestamp("updated_at", { withTimezone: true }).defaultNow(),
});

export const carMetrics = pgTable("car_metrics", {
  carId: text("car_id").primaryKey().references(() => cars.id),
  totalRevenue: numeric("total_revenue", { precision: 14, scale: 2 })
    .notNull()
    .default(sql`0`),
  totalTrips: integer("total_trips").notNull().default(0),
  totalDistance: doublePrecision("total_distance").notNull().default(sql`0`),
  updatedAt: timestamp("updated_at", { withTimezone: true }).defaultNow(),
});

export const tripMetrics = pgTable("trip_metrics", {
  tripId: text("trip_id").primaryKey().references(() => trips.id),
  companyId: text("company_id").notNull(),
  totalFare: numeric("total_fare", { precision: 14, scale: 2 }).notNull().default(sql`0`),
  totalDistance: doublePrecision("total_distance").notNull().default(sql`0`),
  totalDuration: doublePrecision("total_duration").notNull().default(sql`0`),
  startedAt: timestamp("started_at", { withTimezone: true }),
  completedAt: timestamp("completed_at", { withTimezone: true }),
  tripCreatedAt: timestamp("trip_created_at", { withTimezone: true }).notNull(),
  updatedAt: timestamp("updated_at", { withTimezone: true }).defaultNow(),
});

export const tripDestinationMetrics = pgTable("trip_destination_metrics", {
  id: serial("id").primaryKey(),
  tripId: text("trip_id").notNull().references(() => tripMetrics.tripId),
  destinationId: text("destination_id").notNull().references(() => tripDestinations.id),
  numberOfBookings: integer("number_of_bookings").notNull().default(0),
  totalRevenue: numeric("total_revenue", { precision: 14, scale: 2 })
    .notNull()
    .default(sql`0`),
  createdAt: timestamp("created_at", { withTimezone: true }).defaultNow(),
});

export const carLocations = pgTable("car_locations", {
  id: serial("id").primaryKey(),
  carId: text("car_id").notNull().references(() => cars.id),
  driverId: text("driver_id").references(() => drivers.id),
  latitude: doublePrecision("latitude").notNull(),
  longitude: doublePrecision("longitude").notNull(),
  speed: doublePrecision("speed").notNull(),
  bearing: doublePrecision("bearing"),
  accuracy: doublePrecision("accuracy"),
  timestamp: timestamp("timestamp", { withTimezone: true }).notNull(),
});

