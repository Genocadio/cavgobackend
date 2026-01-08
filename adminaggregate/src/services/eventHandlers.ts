import type { VehicleEvent, DriverEvent, LocationUpdate, VehicleResponseDto, CompanyUserResponseDto, CurreLocation, TripEventMessage, RemoteTrip, NavigaTripUpdateEvent, NavigaLocationUpdateEvent, TripServiceEvent, TripSnapshot } from "../types";
import { mapVehicleResponseDtoToCar } from "../mappers/vehicleMapper";
import { mapCompanyUserResponseDtoToDriver } from "../mappers/driverMapper";
import { mapRemoteTripToLocalTrip, mapTripServiceTripToLocalTrip } from "../mappers/tripMapper";
import * as carRepository from "../repositories/cars";
import * as driverRepository from "../repositories/drivers";
import * as assignmentRepository from "../repositories/assignments";
import * as carLocationRepository from "../repositories/carLocations";
import * as tripRepository from "../repositories/trips";
import * as metricsRepository from "../repositories/metrics";
import * as snapshotRepository from "../repositories/snapshots";
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
    // Create initial snapshot with all seats available
    await snapshotRepository.createInitialSnapshot(localTrip, localTrip.carDriver.car.capacity);
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
    
    // Publish individual trip update
    pubsub.publish(TRIGGERS.TRIP_UPDATED(savedTrip.id), {
      trip: savedTrip,
    });
  }
}

export async function handleNavigaTripUpdate(message: Buffer): Promise<void> {
  const event: NavigaTripUpdateEvent = JSON.parse(message.toString());
  
  console.log(JSON.stringify({
    level: "INFO",
    event: "NAVIGA_TRIP_UPDATE_RECEIVED",
    tripId: event.trip.id,
    carId: event.trip.carId,
    status: event.trip.status,
    source: event.source,
    timestamp: event.timestamp,
    waypointProgressesCount: event.trip.waypointProgresses?.length ?? 0,
  }));

  // Get the trip by Naviga trip ID (converted to string)
  const tripId = String(event.trip.id);
  const trip = await tripRepository.getTripById(tripId);
  
  if (!trip) {
    console.warn(JSON.stringify({
      level: "WARN",
      event: "NAVIGA_TRIP_UPDATE_SKIPPED",
      reason: "trip_not_found",
      tripId: tripId,
    }));
    return;
  }

  // Update trip status if needed
  let statusChanged = false;
  if (event.trip.status === "COMPLETED" && trip.status !== "completed") {
    trip.status = "completed";
    statusChanged = true;
  } else if (event.trip.status === "ACTIVE" && trip.status === "scheduled") {
    trip.status = "in_progress";
    statusChanged = true;
  } else if (event.trip.status === "DELETED" && trip.status !== "cancelled") {
    trip.status = "cancelled";
    statusChanged = true;
  }

  // Update waypoint progresses: match by waypointId to destination id
  if (event.trip.waypointProgresses && event.trip.waypointProgresses.length > 0) {
    for (const wp of event.trip.waypointProgresses) {
      // Find destination by matching waypointId with destination.id
      const destination = trip.destinations.find(d => {
        // Remove trip prefix from destination id if present
        const destId = d.id.replace(`${tripId}-`, '');
        return destId === wp.waypointId || d.id === wp.waypointId;
      });
      
      if (destination) {
        // Update remaining distance and time
        destination.remainingDistance = wp.remainingDistance;
        destination.passedTime = wp.remainingTime;
        
        // Update isPassede based on waypoint state
        if (wp.state === "DONE" || wp.state === "ARRIVED") {
          destination.isPassede = true;
        }
        
        console.log(JSON.stringify({
          level: "DEBUG",
          event: "WAYPOINT_PROGRESS_UPDATED",
          tripId: tripId,
          waypointId: wp.waypointId,
          destinationId: destination.id,
          remainingDistance: wp.remainingDistance,
          remainingTime: wp.remainingTime,
          state: wp.state,
          isPassede: destination.isPassede,
        }));
      } else {
        console.warn(JSON.stringify({
          level: "WARN",
          event: "WAYPOINT_DESTINATION_NOT_FOUND",
          tripId: tripId,
          waypointId: wp.waypointId,
          waypointIndex: wp.waypointIndex,
          availableDestinations: trip.destinations.map(d => ({
            id: d.id,
            strippedId: d.id.replace(`${tripId}-`, ''),
          })),
        }));
      }
    }
  }

  // Update current location if available
  if (event.trip.currentLocation) {
    const currentLocation: CurreLocation = {
      location: {
        lat: event.trip.currentLocation.latitude,
        lng: event.trip.currentLocation.longitude,
      },
      speed: event.trip.currentLocation.speed,
      bearing: event.trip.currentLocation.heading ?? 0,
      timestamp: new Date(event.trip.currentLocation.timestamp).getTime(),
    };
    
    // Update car's current location
    await carRepository.updateCarLocation(event.trip.carId, currentLocation);
  }

  // Save the updated trip
  trip.updatedAt = Date.now();
  await tripRepository.updateTrip(trip);

  console.log(JSON.stringify({
    level: "INFO",
    event: "NAVIGA_TRIP_UPDATE_PROCESSED",
    tripId: tripId,
    statusChanged,
    newStatus: trip.status,
    destinationsUpdated: trip.destinations.filter(d => d.remainingDistance !== null).length,
  }));

  // Publish subscription update if status changed or trip is active
  if (statusChanged || trip.status === "scheduled" || trip.status === "in_progress") {
    const companyId = trip.carDriver.car.companyId;
    const activeTrips = await tripRepository.getActiveTripsByCompanyId(companyId);
    pubsub.publish(TRIGGERS.COMPANY_TRIPS_UPDATED(companyId), {
      activeCompanyTrips: activeTrips,
    });
  }
  
  // Publish individual trip update
  pubsub.publish(TRIGGERS.TRIP_UPDATED(tripId), {
    trip: trip,
  });
}

export async function handleNavigaLocationUpdate(message: Buffer): Promise<void> {
  const event: NavigaLocationUpdateEvent = JSON.parse(message.toString());
  
  console.log(JSON.stringify({
    level: "INFO",
    event: "NAVIGA_LOCATION_UPDATE_RECEIVED",
    carId: event.carId,
    locationsCount: event.locations.length,
    timestamp: event.timestamp,
  }));

  // Get driver assignment for the car to get driverId
  const assignment = await assignmentRepository.getDriverCarAssignmentByCarId(event.carId);
  const driverId = assignment?.driver?.id || null;

  // Process all locations in the batch
  for (const location of event.locations) {
    try {
      // Save location to car_locations table
      await carLocationRepository.createCarLocation({
        carId: event.carId,
        driverId,
        latitude: location.latitude,
        longitude: location.longitude,
        speed: location.speed,
        bearing: location.heading,
        accuracy: location.accuracy,
        timestamp: new Date(location.timestamp).getTime(),
      });
    } catch (error) {
      console.error(JSON.stringify({
        level: "ERROR",
        event: "NAVIGA_LOCATION_SAVE_FAILED",
        carId: event.carId,
        timestamp: location.timestamp,
        error: error instanceof Error ? error.message : String(error),
      }));
    }
  }

  // Update car's current location with the latest location in the batch
  if (event.locations.length > 0) {
    const latestLocation = event.locations[event.locations.length - 1];
    if (latestLocation) {
      const currentLocation: CurreLocation = {
        location: {
          lat: latestLocation.latitude,
          lng: latestLocation.longitude,
        },
        speed: latestLocation.speed,
        bearing: latestLocation.heading ?? 0,
        timestamp: new Date(latestLocation.timestamp).getTime(),
      };
      
      try {
        await carRepository.updateCarLocation(event.carId, currentLocation);
        
        console.log(JSON.stringify({
          level: "INFO",
          event: "NAVIGA_LOCATION_UPDATE_PROCESSED",
          carId: event.carId,
          locationsSaved: event.locations.length,
          latestTimestamp: latestLocation.timestamp,
        }));
      } catch (error) {
        console.error(JSON.stringify({
          level: "ERROR",
          event: "CAR_LOCATION_UPDATE_FAILED",
          carId: event.carId,
          error: error instanceof Error ? error.message : String(error),
        }));
      }
    }
  }
}

export async function handleTripServiceEvent(message: Buffer): Promise<void> {
  const event: TripServiceEvent = JSON.parse(message.toString());
  
  console.log(JSON.stringify({
    level: "INFO",
    event: "TRIP_SERVICE_EVENT_RECEIVED",
    eventType: event.event,
    tripId: event.data.id,
    vehicleId: event.data.vehicle_id,
    status: event.data.status,
    waypointsCount: event.data.waypoints?.length ?? 0,
  }));

  try {
    // Map trip service data to local trip
    const localTrip = await mapTripServiceTripToLocalTrip(event.data);
    
    // Check if trip exists
    const existingTrip = await tripRepository.getTripById(localTrip.id);
    
    // Handle different event types
    if (event.event === "created") {
      if (existingTrip) {
        // Trip already exists, update it
        await tripRepository.updateTrip(localTrip);
        console.log(JSON.stringify({
          level: "INFO",
          event: "TRIP_SERVICE_UPDATED_EXISTING",
          tripId: localTrip.id,
          reason: "created_event_for_existing_trip",
        }));
      } else {
        // Create new trip
        await tripRepository.createTrip(localTrip);
        // Create initial snapshot with all seats available
        await snapshotRepository.createInitialSnapshot(localTrip, localTrip.carDriver.car.capacity);
        console.log(JSON.stringify({
          level: "INFO",
          event: "TRIP_SERVICE_CREATED",
          tripId: localTrip.id,
        }));
      }
    } else if (event.event === "cancelled" || event.event === "completed") {
      // Update trip status
      if (existingTrip) {
        localTrip.status = event.event === "cancelled" ? "cancelled" : "completed";
        await tripRepository.updateTrip(localTrip);
        console.log(JSON.stringify({
          level: "INFO",
          event: "TRIP_SERVICE_STATUS_UPDATED",
          tripId: localTrip.id,
          newStatus: localTrip.status,
        }));
      } else {
        // Trip doesn't exist yet, create it with the final status
        localTrip.status = event.event === "cancelled" ? "cancelled" : "completed";
        await tripRepository.createTrip(localTrip);
        // Create initial snapshot with all seats available (even if trip is already in final state)
        await snapshotRepository.createInitialSnapshot(localTrip, localTrip.carDriver.car.capacity);
        console.log(JSON.stringify({
          level: "WARN",
          event: "TRIP_SERVICE_CREATED_WITH_FINAL_STATUS",
          tripId: localTrip.id,
          status: localTrip.status,
          reason: "received_status_event_before_created",
        }));
      }
    }

    // Update metrics
    const vehicleId = String(event.data.vehicle_id);
    const driverId = event.data.vehicle.driver ? String(event.data.vehicle.driver.id) : null;
    
    await updateTripMetrics({
      trip: localTrip,
      vehicleId,
      driverId,
      existingTrip,
      tripDistance: localTrip.totalDistance,
      tripFare: event.data.price,
      startedAt: event.data.departure_time,
      completedAt: event.event === "completed" ? event.data.updated_at : undefined,
    });

    // Publish GraphQL subscription update
    const savedTrip = await tripRepository.getTripById(localTrip.id);
    if (savedTrip) {
      const companyId = savedTrip.carDriver.car.companyId;
      
      // Publish update for active trips
      if (savedTrip.status === "scheduled" || savedTrip.status === "in_progress") {
        const activeTrips = await tripRepository.getActiveTripsByCompanyId(companyId);
        pubsub.publish(TRIGGERS.COMPANY_TRIPS_UPDATED(companyId), {
          activeCompanyTrips: activeTrips,
        });
      } else if (event.event === "cancelled" || event.event === "completed") {
        // Trip ended - publish to remove from active list
        const activeTrips = await tripRepository.getActiveTripsByCompanyId(companyId);
        pubsub.publish(TRIGGERS.COMPANY_TRIPS_UPDATED(companyId), {
          activeCompanyTrips: activeTrips,
        });
      }
      
      // Publish individual trip update
      pubsub.publish(TRIGGERS.TRIP_UPDATED(savedTrip.id), {
        trip: savedTrip,
      });
    }

    console.log(JSON.stringify({
      level: "INFO",
      event: "TRIP_SERVICE_EVENT_PROCESSED",
      eventType: event.event,
      tripId: localTrip.id,
      status: localTrip.status,
    }));
  } catch (error) {
    console.error(JSON.stringify({
      level: "ERROR",
      event: "TRIP_SERVICE_EVENT_FAILED",
      eventType: event.event,
      tripId: event.data.id,
      error: error instanceof Error ? error.message : String(error),
      stack: error instanceof Error ? error.stack : undefined,
    }));
  }
}

export async function handleTripSnapshotUpdate(message: Buffer): Promise<void> {
  try {
    const snapshot: TripSnapshot = JSON.parse(message.toString());
    const tripId = snapshot.tripId;

    // Check if this is the first snapshot for this trip (INITIALIZED event)
    const exists = await snapshotRepository.snapshotExists(tripId);
    const isFirstSnapshot = !exists;

    // Store or update the snapshot
    await snapshotRepository.upsertSnapshot(snapshot);

    // Publish update to subscribers
    const trigger = TRIGGERS.TRIP_SNAPSHOT_UPDATED(tripId);
    pubsub.publish(trigger, { tripSnapshot: snapshot });

    console.log(JSON.stringify({
      level: "INFO",
      event: "TRIP_SNAPSHOT_UPDATED",
      tripId,
      isFirstSnapshot,
      tripStatus: snapshot.tripStatus,
      availableSeats: snapshot.capacity.availableSeats,
      totalTickets: snapshot.summary.totalTickets,
      lastUpdated: snapshot.lastUpdated,
    }));
  } catch (error) {
    console.error(JSON.stringify({
      level: "ERROR",
      event: "TRIP_SNAPSHOT_UPDATE_FAILED",
      error: error instanceof Error ? error.message : String(error),
      stack: error instanceof Error ? error.stack : undefined,
    }));
  }
}
