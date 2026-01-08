export type Status = "ACTIVE" | "INACTIVE" | "SUSPENDED" | "PENDING_VERIFICATION";
export type CompanyStatus = "ACTIVE" | "INACTIVE" | "SUSPENDED";
export type VehicleStatus = "AVAILABLE" | "MAINTENANCE" | "OUT_OF_SERVICE" | "OCCUPIED";

export interface Company {
  id: string;
  companyName: string;
  email: string;
  phone: string;
  address: string | null;
  city: string | null;
  companyCode: string;
  status: CompanyStatus;
  createdAt: string | null;
  updatedAt: string | null;
  createdBy: string | null;
  updatedBy: string | null;
}

export interface Driver {
  id: string;
  firstName: string;
  lastName: string;
  phoneNumber: string;
  email: string;
  status: Status;
  companyId: string;
  dateOfBirth: string | null;
  address: string | null;
  licenseNumber: string | null;
  licenseExpiry: string | null;
  role: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface Car {
  id: string;
  plate: string; // licensePlate - same field, using plate as the canonical name
  make: string;
  model: string;
  vehicleType: string | null;
  capacity: number;
  status: VehicleStatus;
  isOnline: boolean;
  currentLocation: CurreLocation | null;
  companyId: string;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface LatLang {
  lat: number;
  lng: number;
}

export interface CurreLocation {
    location: LatLang;
    speed: number;
    bearing: number;
    timestamp: number;
}

export interface TripLocation extends LatLang {
    id: string;
    addres: string;
}

export interface  Destination extends TripLocation {
    index: number;
    fare: number;
    remainingDistance: number | null;
    isPassede: boolean;
    passedTime: number | null;
}

export type TripStatus = "scheduled" | "in_progress" | "completed" | "cancelled";

export interface Trip {
    id: string;
    carDriver: DriverCarAssignment;
    origin: TripLocation;
    destinations: Destination[];
    status: TripStatus;
    totalDistance: number;
    createdAt: number;
    updatedAt: number;
}

export interface DriverCarAssignment {
    car: Car;
    driver?: Driver | null;
}

export type BookingStatus = "pending" | "confirmed" | "cancelled" | "completed" | "used" | "expired";
export type PaymentType = "cash" | "epayment" | "card";

export interface Booking {
    id: string;
    tripId: string;
    passengerName: string | null;
    passengerPhone: string | null;
    pickupLocationId: string;
    dropoffLocationId: string;
    numberOftickets: number;
    totalFare: number;
    paymentType: PaymentType | null;
    status: BookingStatus;
    createdAt: number;
    updatedAt: number;
}

export interface PerDestinationMetrics{
    destinationId: string;
    numberOfBookings: number;
    totalRevenue: number;
}

export interface TripMetrics{
    tripId: string;
    companyId: string;
    totalFare: number;
    startedAt: number | null;
    completedAt: number | null;
    perDestinationMetrics: PerDestinationMetrics[];
    totalDistance: number;
    totalDuration: number;
    tripCreatedAt: number;
}

export interface DriverMetrics{
    driverId: string;
    totalRevenue: number;
    totalTrips: number;
    totalDistance: number;
}

export interface CarMetrics{
    carId: string;
    totalRevenue: number;
    totalTrips: number;
    totalDistance: number;
}

export type MetricsPeriod = "today" | "custom";
export type Granularity = "hourly" | "daily" | "weekly" | "monthly";

export interface TimeSeriesPoint {
  label: string;        // e.g. "14:00", "2025-02-03", "Week 12", "March"
  value: number;        // generic numeric value (trips or revenue)
}

export interface TimeSeries {
  granularity: Granularity;
  unit: "hours" | "days" | "weeks" | "months";
  data: TimeSeriesPoint[];
}

export interface DestinationMetrics {
  destinationId: string;
  numberOfTrips: number;
  totalRevenue: number;
}

export interface CompanyPeriodMetrics {
  companyId: string;
  period: MetricsPeriod;
  startTime: number;
  endTime: number;
  // Trips
  totalTrips: number;
  completedTrips: number;
  cancelledTrips: number;
  inProgressTrips: number;
  scheduledTrips: number;
  // Revenue
  totalRevenue: number;
  revenueFromCompletedTrips: number;
  // Travel metrics
  totalDistance: number;
  totalDuration: number;
  averageTripDistance: number;
  averageTripDuration: number;
  // Driver/car stats
  uniqueDrivers: number;
  uniqueCars: number;
  destinations?: DestinationMetrics[];
  tripsByStatus?: Record<"completed" | "cancelled" | "in_progress" | "scheduled", number>;
  // Granularity-aware time series
  revenueSeries?: TimeSeries;
  tripsSeries?: TimeSeries;
}

// Remote DTOs
export interface CompanyResponseDto {
  id: number;
  companyName: string;
  email: string;
  phone: string;
  address: string;
  city: string;
  companyCode: string;
  status: CompanyStatus;
  createdAt: string;
  updatedAt: string;
  createdBy: string;
  updatedBy: string;
}

export interface VehicleResponseDto {
  id: number;
  companyId: number;
  companyName: string;
  make: string;
  model: string;
  capacity: number;
  licensePlate: string;
  vehicleType: string;
  status: VehicleStatus;
  createdAt: string;
  updatedAt: string;
  driver: CompanyUserResponseDto | null;
  initialPassword?: string;
}

export interface CompanyUserResponseDto {
  id: number;
  companyId: number;
  companyName: string;
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  status: Status;
  dateOfBirth: string;
  address: string;
  role: string;
  licenseNumber: string;
  licenseExpiry: string;
  createdAt: string;
  updatedAt: string;
  vehicle: VehicleResponseDto | null;
}

// Event types
export type VehicleEventType = "CREATE" | "UPDATE" | "DELETE" | "DRIVER_ASSIGNMENT";
export type DriverEventType = "CREATE" | "UPDATE" | "DELETE";

export interface VehicleEvent {
  event: VehicleEventType;
  data: VehicleResponseDto | { vehicleId: number; driverId?: number } | { vehicleId: number };
}

export interface DriverEvent {
  event: DriverEventType;
  data: CompanyUserResponseDto | { driverId: number };
}

export interface LocationUpdate {
  status: string;
  car_id: string;
  timestamp: number;
  current_latitude: number | null;
  current_longitude: number | null;
  current_speed: number | null;
  accuracy: number | null;
  bearing: number | null;
}

// Remote Trip Types (from trips.fanout exchange)
export interface RemoteLocation {
  id: number;
  latitude: number;
  longitude: number;
  price: number;
  code: string | null;
  google_place_name: string | null;
  custom_name: string | null;
  place_id: string | null;
  created_at: string | null;
  updated_at: string | null;
}

export interface RemoteDriver {
  name: string | null;
  phone: string | null;
}

export interface RemoteVehicle {
  id: number | null;
  company_id: number | null;
  company_name: string | null;
  capacity: number | null;
  license_plate: string | null;
  driver: RemoteDriver | null;
}

export interface RemoteRoute {
  id: number | null;
  name: string | null;
  distance_meters: number | null;
  estimated_duration_seconds: number | null;
  google_route_id: string | null;
  origin_id: string | null;
  destination_id: string | null;
  route_price: number | null;
  city_route: boolean | null;
  created_at: string | null;
  updated_at: string | null;
  origin: RemoteLocation | null;
  destination: RemoteLocation | null;
  waypoints: any[] | null;
}

export interface RemoteWaypoint {
  id: number | null;
  trip_id: number | null;
  location_id: number | null;
  order: number | null;
  price: number | null;
  is_passed: boolean | null;
  is_next: boolean | null;
  passed_timestamp: number | null;
  remaining_time: number | null;
  remaining_distance: number | null;
  is_custom: boolean | null;
  created_at: string | null;
  updated_at: string | null;
  location: RemoteLocation | null;
}

export interface RemoteTrip {
  id: number;
  route_id: number | null;
  vehicle_id: number | null;
  vehicle: RemoteVehicle | null;
  status: string | null;
  departure_time: number | null;
  completion_time: number | null;
  connection_mode: string | null;
  notes: string | null;
  seats: number | null;
  remaining_time_to_destination: number | null;
  remaining_distance_to_destination: number | null;
  is_reversed: boolean | null;
  current_speed: number | null;
  current_latitude: number | null;
  current_longitude: number | null;
  has_custom_waypoints: boolean | null;
  created_at: string | null;
  updated_at: string | null;
  route: RemoteRoute | null;
  waypoints: RemoteWaypoint[] | null;
}

export interface TripEventMessage {
  event: string;
  data: RemoteTrip;
}

// API Trip Types (from /internal/trips endpoint)
export interface TripApiDriver {
  id: number;
  name: string | null;
  phone: string | null;
}

export interface TripApiVehicle {
  id: number;
  company_id: number;
  company_name: string;
  capacity: number;
  license_plate: string;
  driver: TripApiDriver | null;
}

export interface TripApiLocation {
  id: number;
  latitude: number;
  longitude: number;
  code: string | null;
  google_place_name: string | null;
  custom_name: string | null;
  province: string | null;
  district: string | null;
  place_id: string | null;
  created_at: string | null;
  updated_at: string | null;
}

export interface TripApiRoute {
  id: number | null;
  name: string | null;
  distance_meters: number | null;
  estimated_duration_seconds: number | null;
  google_route_id: string | null;
  origin_id: number | null;
  destination_id: number | null;
  route_price: number | null;
  city_route: boolean | null;
  created_at: string | null;
  updated_at: string | null;
  origin: TripApiLocation | null;
  destination: TripApiLocation | null;
  waypoints: any[] | null;
}

export interface TripApiWaypoint {
  id: number;
  trip_id: number;
  location_id: number;
  order: number;
  price: number | null;
  is_pass_through: boolean | null;
  is_passed: boolean | null;
  is_next: boolean | null;
  passed_timestamp: number | null;
  remaining_time: number | null;
  remaining_distance: number | null;
  is_custom: boolean | null;
  created_at: string | null;
  updated_at: string | null;
  location: TripApiLocation | null;
}

export interface TripApiItem {
  id: number;
  route_id: number | null;
  vehicle_id: number;
  vehicle: TripApiVehicle | null;
  status: string | null;
  departure_time: number | null;
  completion_time: number | null;
  connection_mode: string | null;
  notes: string | null;
  seats: number | null;
  price: number | null;
  remaining_time_to_destination: number | null;
  remaining_distance_to_destination: number | null;
  is_reversed: boolean | null;
  current_speed: number | null;
  current_latitude: number | null;
  current_longitude: number | null;
  has_custom_waypoints: boolean | null;
  created_at: string | null;
  updated_at: string | null;
  route: TripApiRoute | null;
  waypoints: TripApiWaypoint[] | null;
}

export interface TripApiResponse {
  trips: TripApiItem[];
  total: number;
  limit: number;
  offset: number;
  page: number;
  total_pages: number;
}

// Naviga RabbitMQ Event Types
export interface NavigaWaypointProgressDto {
  waypointIndex: number;
  waypointId: string | null;
  waypointName: string | null;
  latitude: number;
  longitude: number;
  state: "APPROACHING" | "ARRIVED" | "DONE";
  arrivedAt: string | null; // ISO 8601
  remainingDistance: number;
  remainingTime: number;
}

export interface NavigaCurrentLocationDto {
  carId: string;
  latitude: number;
  longitude: number;
  speed: number;
  heading: number | null;
  timestamp: string; // ISO 8601
}

export interface NavigaTripDto {
  id: number;
  carId: string;
  status: "CREATED" | "ACTIVE" | "COMPLETED" | "DELETED";
  createdAt: string; // ISO 8601
  completedAt: string | null; // ISO 8601
  waypointProgresses: NavigaWaypointProgressDto[] | null;
  currentLocation: NavigaCurrentLocationDto | null;
}

export interface NavigaTripUpdateEvent {
  eventType: "updates";
  trip: NavigaTripDto;
  timestamp: string; // ISO 8601
  source: "naviga-trip-create" | "naviga-gps-batch" | "naviga-trip-delete";
}

export interface NavigaLocationDto {
  carId: string;
  latitude: number;
  longitude: number;
  speed: number;
  heading: number | null;
  accuracy: number | null;
  timestamp: string; // ISO 8601
}

export interface NavigaLocationUpdateEvent {
  eventType: "updates";
  carId: string;
  locations: NavigaLocationDto[];
  timestamp: string; // ISO 8601
  source: "location-batch";
}

// Trip Service RabbitMQ Event Types
export interface TripServiceDriverSnapshot {
  id: number;
  name: string;
  phone: string;
}

export interface TripServiceVehicle {
  id: number;
  company_id: number;
  company_name: string;
  capacity: number;
  license_plate: string;
  driver: TripServiceDriverSnapshot | null;
}

export interface TripServiceRoute {
  id: number;
  origin: string;
  destination: string;
  route_price: number;
  distance: number;
  duration: number;
  city_route: boolean;
  waypoints: any | null;
}

export interface TripServiceWaypoint {
  id: number;
  trip_id: number;
  location_id: number;
  location_name: string;
  order: number;
  price: number | null;
  is_custom: boolean;
  is_pass_through: boolean;
  remaining_distance: number;
  remaining_time: number;
  is_passed: boolean;
  passed_timestamp: number | null;
}

export interface TripServiceTrip {
  id: number;
  route_id: number;
  vehicle_id: number;
  vehicle: TripServiceVehicle;
  status: "SCHEDULED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
  departure_time: number;
  connection_mode: string;
  price: number;
  notes: string | null;
  seats: number;
  is_reversed: boolean;
  has_custom_waypoints: boolean;
  created_at: number;
  updated_at: number;
  route: TripServiceRoute;
  waypoints: TripServiceWaypoint[];
}

export interface TripServiceEvent {
  event: "created" | "cancelled" | "completed";
  data: TripServiceTrip;
}

// Booking Service Trip Snapshots (from bookingservice.trip.snapshot fanout)
export interface SnapshotSeats {
  pickup: number;
  dropoff: number;
  pendingPayment: number;
  availableFromHere: number;
}

export interface SnapshotLocation {
  locationId: string;
  type: "ORIGIN" | "WAYPOINT" | "DESTINATION";
  order: number;
  status: "UPCOMING" | "CURRENT" | "PASSED";
  seats: SnapshotSeats;
}

export interface SnapshotCapacity {
  totalSeats: number;
  availableSeats: number;
  occupiedSeats: number;
  pendingPaymentSeats: number;
}

export interface SnapshotSummary {
  totalTickets: number;
  paidTickets: number;
  pendingPayments: number;
  completedDropoffs: number;
}

export interface TripSnapshot {
  tripId: string;
  tripStatus: "SCHEDULED" | "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
  lastUpdated: string;
  capacity: SnapshotCapacity;
  locations: SnapshotLocation[];
  summary: SnapshotSummary;
}

export type TripSnapshotEventType = "INITIALIZED" | "BOOKING_CREATED" | "PAYMENT_CONFIRMED" | "BOOKING_EXPIRED";

export interface TripSnapshotPublish {
  tripId: string;
  tripStatus: string;
  lastUpdated: string;
  capacity: SnapshotCapacity;
  locations: SnapshotLocation[];
  summary: SnapshotSummary;
}