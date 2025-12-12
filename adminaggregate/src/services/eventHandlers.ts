import type { VehicleEvent, DriverEvent, LocationUpdate, VehicleResponseDto, CompanyUserResponseDto, CurreLocation, TripEventMessage, RemoteTrip } from "../types";
import { mapVehicleResponseDtoToCar } from "../mappers/vehicleMapper";
import { mapCompanyUserResponseDtoToDriver } from "../mappers/driverMapper";
import { mapRemoteTripToLocalTrip } from "../mappers/tripMapper";
import * as carRepository from "../repositories/cars";
import * as driverRepository from "../repositories/drivers";
import * as assignmentRepository from "../repositories/assignments";
import * as carLocationRepository from "../repositories/carLocations";
import * as tripRepository from "../repositories/trips";
import * as metricsRepository from "../repositories/metrics";
import { updateTripMetrics } from "./tripMetricsService";
import { pubsub, TRIGGERS } from "./pubsub";

export async function handleVehicleEvent(message: Buffer): Promise<void> {
  const event: VehicleEvent = JSON.parse(message.toString());

  if (event.event === "CREATE" || event.event === "UPDATE") {
    const vehicleData = event.data as VehicleResponseDto;
    const car = mapVehicleResponseDtoToCar(vehicleData);

    // Upsert vehicle
    const existing = await carRepository.getCarById(car.id);
    if (existing) {
      await carRepository.updateCar(car);
    } else {
      await carRepository.createCar(car);
    }

    // Handle driver assignment
    if (vehicleData.driver) {
      // Vehicle has a driver assigned
      const driverId = String(vehicleData.driver.id);
      await assignmentRepository.ensureDriverCarAssignment(driverId, car.id);
    } else {
      // Vehicle has no driver - clear assignment for this car
      await assignmentRepository.removeDriverFromAssignment(car.id);
    }
  } else if (event.event === "DELETE") {
    const deleteData = event.data as { vehicleId: number };
    const vehicleId = String(deleteData.vehicleId);
    
    // Clear assignment first
    await assignmentRepository.removeDriverFromAssignment(vehicleId);
    // Then delete the vehicle
    await carRepository.deleteCar(vehicleId);
  } else if (event.event === "DRIVER_ASSIGNMENT") {
    const assignmentData = event.data as { vehicleId: number; driverId: number };
    const vehicleId = String(assignmentData.vehicleId);
    const driverId = String(assignmentData.driverId);

    // Ensure 1:1 assignment (this will remove old assignments)
    await assignmentRepository.ensureDriverCarAssignment(driverId, vehicleId);
  }
}

export async function handleDriverEvent(message: Buffer): Promise<void> {
  const event: DriverEvent = JSON.parse(message.toString());

  if (event.event === "CREATE" || event.event === "UPDATE") {
    const driverData = event.data as CompanyUserResponseDto;
    const driver = mapCompanyUserResponseDtoToDriver(driverData);

    // Upsert driver
    const existing = await driverRepository.getDriverById(driver.id);
    if (existing) {
      await driverRepository.updateDriver(driver);
    } else {
      await driverRepository.createDriver(driver);
    }

    // Handle vehicle assignment
    if (driverData.vehicle) {
      // Driver has a vehicle assigned
      const vehicleId = String(driverData.vehicle.id);
      await assignmentRepository.ensureDriverCarAssignment(driver.id, vehicleId);
    } else {
      // Driver has no vehicle - clear assignment for this driver
      await assignmentRepository.removeAssignmentByDriverId(driver.id);
    }
  } else if (event.event === "DELETE") {
    const deleteData = event.data as { driverId: number };
    const driverId = String(deleteData.driverId);
    
    // Clear assignment first
    await assignmentRepository.removeAssignmentByDriverId(driverId);
    // Then delete the driver
    await driverRepository.deleteDriver(driverId);
  }
}

export async function handleLocationUpdate(message: Buffer): Promise<void> {
  const update: LocationUpdate = JSON.parse(message.toString());
  const carId = String(update.car_id);
  
  // Validate required fields - skip if latitude/longitude are missing
  if (update.current_latitude == null || update.current_longitude == null) {
    console.warn(`Skipping location update for car ${carId}: missing latitude or longitude`);
    return;
  }
  
  // Create currentLocation object for the car
  const currentLocation: CurreLocation = {
    location: {
      lat: update.current_latitude,
      lng: update.current_longitude,
    },
    speed: update.current_speed ?? 0,
    bearing: update.bearing ?? 0,
    timestamp: update.timestamp,
  };
  
  // Update the car's current location in the cars table
  await carRepository.updateCarLocation(carId, currentLocation);
  
  // Also store in car_locations table for history (don't generate ID - it's auto-generated)
  // Get driverId from assignment if exists
  const assignment = await assignmentRepository.getDriverCarAssignmentByCarId(carId);
  await carLocationRepository.createCarLocation({
    carId,
    driverId: assignment?.driver?.id || null,
    latitude: update.current_latitude,
    longitude: update.current_longitude,
    speed: update.current_speed ?? 0,
    bearing: update.bearing ?? null,
    accuracy: update.accuracy ?? null,
    timestamp: update.timestamp,
  });
}

export async function handleTripEvent(message: Buffer): Promise<void> {
  const rawMessage = message.toString();
  const event: TripEventMessage = JSON.parse(rawMessage);
  const remoteTrip = event.data;
  
  // LOG: What's received from RabbitMQ
  console.log(JSON.stringify({
    level: "INFO",
    event: "TRIP_EVENT_RECEIVED",
    source: "rabbitmq",
    tripId: remoteTrip.id,
    eventType: event.event,
    received: {
      tripId: remoteTrip.id,
      vehicleId: remoteTrip.vehicle_id,
      vehicleFromNested: remoteTrip.vehicle?.id,
      status: remoteTrip.status,
      routeId: remoteTrip.route_id,
      route: remoteTrip.route ? {
        origin: remoteTrip.route.origin ? {
          id: remoteTrip.route.origin.id,
          latitude: remoteTrip.route.origin.latitude,
          longitude: remoteTrip.route.origin.longitude,
        } : null,
        destination: remoteTrip.route.destination ? {
          id: remoteTrip.route.destination.id,
          latitude: remoteTrip.route.destination.latitude,
          longitude: remoteTrip.route.destination.longitude,
        } : null,
        distanceMeters: remoteTrip.route.distance_meters,
      } : null,
      waypointsCount: remoteTrip.waypoints?.length ?? 0,
      waypoints: remoteTrip.waypoints?.map(wp => ({
        id: wp.id,
        locationId: wp.location_id,
        order: wp.order,
        hasLocation: !!wp.location,
        location: wp.location ? {
          id: wp.location.id,
          latitude: wp.location.latitude,
          longitude: wp.location.longitude,
        } : null,
      })) ?? [],
      driverPhone: remoteTrip.vehicle?.driver?.phone,
    },
  }));
  
  // Extract vehicle ID
  const vehicleId = remoteTrip.vehicle_id
    ? String(remoteTrip.vehicle_id)
    : remoteTrip.vehicle?.id
    ? String(remoteTrip.vehicle.id)
    : null;
  
  if (!vehicleId) {
    console.warn(JSON.stringify({
      level: "WARN",
      event: "TRIP_EVENT_SKIPPED",
      reason: "missing_vehicle_id",
      tripId: remoteTrip.id,
    }));
    return;
  }
  
  // Find driver by phone number if vehicle has driver info
  let driverId: string | null = null;
  if (remoteTrip.vehicle?.driver?.phone) {
    const driver = await driverRepository.getDriverByPhone(remoteTrip.vehicle.driver.phone);
    driverId = driver?.id || null;
  }
  
  // Map remote trip to local trip
  const localTrip = await mapRemoteTripToLocalTrip(remoteTrip, vehicleId, driverId);
  
  // LOG: What's processed (after mapping)
  console.log(JSON.stringify({
    level: "INFO",
    event: "TRIP_EVENT_PROCESSED",
    source: "mapper",
    tripId: localTrip.id,
    processed: {
      tripId: localTrip.id,
      vehicleId,
      driverId,
      status: localTrip.status,
      origin: {
        id: localTrip.origin.id,
        lat: localTrip.origin.lat,
        lng: localTrip.origin.lng,
        address: localTrip.origin.addres,
      },
      destinationsCount: localTrip.destinations.length,
      destinations: localTrip.destinations.map(dest => ({
        id: dest.id,
        index: dest.index,
        lat: dest.lat,
        lng: dest.lng,
        address: dest.addres,
        fare: dest.fare,
      })),
      totalDistance: localTrip.totalDistance,
      assignmentId: localTrip.carDriver.car.id,
    },
  }));
  
  // Upsert trip (check if exists, update or create)
  const existing = await tripRepository.getTripById(localTrip.id);
  const wasCompleted = existing?.status === "completed";
  const wasInProgress = existing?.status === "in_progress";
  
  if (existing) {
    await tripRepository.updateTrip(localTrip);
  } else {
    await tripRepository.createTrip(localTrip);
  }
  
  // LOG: What's saved (after database operations)
  const savedTrip = await tripRepository.getTripById(localTrip.id);
  console.log(JSON.stringify({
    level: "INFO",
    event: "TRIP_EVENT_SAVED",
    source: "repository",
    tripId: localTrip.id,
    action: existing ? "updated" : "created",
    saved: {
      tripId: savedTrip?.id,
      status: savedTrip?.status,
      origin: savedTrip?.origin ? {
        id: savedTrip.origin.id,
        lat: savedTrip.origin.lat,
        lng: savedTrip.origin.lng,
        address: savedTrip.origin.addres,
      } : null,
      destinationsCount: savedTrip?.destinations.length ?? 0,
      destinations: savedTrip?.destinations.map(dest => ({
        id: dest.id,
        index: dest.index,
        lat: dest.lat,
        lng: dest.lng,
        address: dest.addres,
        fare: dest.fare,
      })) ?? [],
      totalDistance: savedTrip?.totalDistance,
      vehicleId: savedTrip?.carDriver.car.id,
      driverId: savedTrip?.carDriver.driver?.id ?? null,
    },
  }));
  
  // Update metrics for all trip create/update/cancel events
  // Note: Revenue/fare is set to 0 as it will be sourced from bookings data (not yet integrated)
  await updateTripMetrics({
    trip: localTrip,
    vehicleId,
    driverId,
    existingTrip: existing,
    tripDistance: localTrip.totalDistance,
    tripFare: 0, // Revenue will come from bookings, not from trip route_price/price
    startedAt: remoteTrip.departure_time || undefined,
    completedAt: remoteTrip.completion_time || undefined,
  });

  // Publish subscription update for company trips
  // Get company ID from the saved trip's car
  if (savedTrip) {
    const companyId = savedTrip.carDriver.car.companyId;
    // Only publish if trip is active (scheduled or in_progress)
    if (savedTrip.status === "scheduled" || savedTrip.status === "in_progress") {
      const activeTrips = await tripRepository.getActiveTripsByCompanyId(companyId);
      pubsub.publish(TRIGGERS.COMPANY_TRIPS_UPDATED(companyId), {
        activeCompanyTrips: activeTrips,
      });
    } else {
      // Trip completed or cancelled - still publish to remove it from active list
      const activeTrips = await tripRepository.getActiveTripsByCompanyId(companyId);
      pubsub.publish(TRIGGERS.COMPANY_TRIPS_UPDATED(companyId), {
        activeCompanyTrips: activeTrips,
      });
    }
  }
}

