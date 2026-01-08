import { config } from "dotenv";
import * as amqp from "amqplib";

config();

const RABBITMQ_HOST = process.env.RABBITMQ_HOST || "localhost";
const RABBITMQ_PORT = Number(process.env.RABBITMQ_PORT) || 5672;
const RABBITMQ_USERNAME = process.env.RABBITMQ_USERNAME || "admin";
const RABBITMQ_PASSWORD = process.env.RABBITMQ_PASSWORD || "admin";

let connection: amqp.Connection | null = null;
let channel: amqp.Channel | null = null;

export async function connectRabbitMQ(): Promise<amqp.Connection> {
  if (connection) {
    return connection;
  }

  const url = `amqp://${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}@${RABBITMQ_HOST}:${RABBITMQ_PORT}`;
  connection = await amqp.connect(url) as unknown as amqp.Connection;
  
  if (connection) {
    connection.on("error", (err) => {
      console.error("RabbitMQ connection error:", err);
      connection = null;
      channel = null;
    });

    connection.on("close", () => {
      console.log("RabbitMQ connection closed");
      connection = null;
      channel = null;
    });
  }

  return connection;
}

export async function getChannel(): Promise<amqp.Channel> {
  if (!connection) {
    await connectRabbitMQ();
  }
  
  if (channel && connection) {
    return channel;
  }

  if (!connection) {
    throw new Error("Failed to establish RabbitMQ connection");
  }

  channel = await (connection as any).createChannel() as amqp.Channel;
  return channel;
}

export interface MessageHandlers {
  onVehicleEvent: (message: Buffer) => Promise<void>;
  onDriverEvent: (message: Buffer) => Promise<void>;
  onLocationUpdate: (message: Buffer) => Promise<void>;
  onTripEvent: (message: Buffer) => Promise<void>;
  onNavigaTripUpdate: (message: Buffer) => Promise<void>;
  onNavigaLocationUpdate: (message: Buffer) => Promise<void>;
  onTripServiceEvent: (message: Buffer) => Promise<void>;
  onTripSnapshotUpdate: (message: Buffer) => Promise<void>;
}

export async function setupSubscriptions(handlers: MessageHandlers): Promise<void> {
  const ch = await getChannel();

  // Declare fanout exchanges (durable to match existing exchanges)
  await ch.assertExchange("vehicle.location.updates.fanout", "fanout", { durable: true });
  await ch.assertExchange("vehicle.events", "fanout", { durable: true });
  await ch.assertExchange("driver.events", "fanout", { durable: true });
  await ch.assertExchange("trips.fanout", "fanout", { durable: true });
  
  // Declare Naviga fanout exchanges
  await ch.assertExchange("cavgomqt.trip.updates", "fanout", { durable: true });
  await ch.assertExchange("cavgomqt.location.updates", "fanout", { durable: true });
  
  // Declare Trip Service fanout exchange
  await ch.assertExchange("tripservice.trips.updates", "fanout", { durable: true });

  // Declare Booking Service fanout exchange for trip snapshots
  const snapshotExchange = process.env.SNAPSHOT_EXCHANGE || "bookingservice.trip.snapshot";
  await ch.assertExchange(snapshotExchange, "fanout", { durable: true });

  // Create queues for each exchange (fanout exchanges need queues)
  const locationQueue = await ch.assertQueue("", { exclusive: true });
  const vehicleEventsQueue = await ch.assertQueue("", { exclusive: true });
  const driverEventsQueue = await ch.assertQueue("", { exclusive: true });
  const tripEventsQueue = await ch.assertQueue("", { exclusive: true });
  
  // Create queues for Naviga exchanges
  const navigaTripUpdatesQueue = await ch.assertQueue("", { exclusive: true });
  const navigaLocationUpdatesQueue = await ch.assertQueue("", { exclusive: true });
  
  // Create queue for Trip Service exchange
  const tripServiceEventsQueue = await ch.assertQueue("", { exclusive: true });

  // Create queue for Booking Service trip snapshots
  const snapshotQueue = await ch.assertQueue("", { exclusive: true });

  // Bind queues to exchanges
  await ch.bindQueue(locationQueue.queue, "vehicle.location.updates.fanout", "");
  await ch.bindQueue(vehicleEventsQueue.queue, "vehicle.events", "");
  await ch.bindQueue(driverEventsQueue.queue, "driver.events", "");
  await ch.bindQueue(tripEventsQueue.queue, "trips.fanout", "");
  
  // Bind Naviga queues to exchanges
  await ch.bindQueue(navigaTripUpdatesQueue.queue, "cavgomqt.trip.updates", "");
  await ch.bindQueue(navigaLocationUpdatesQueue.queue, "cavgomqt.location.updates", "");
  
  // Bind Trip Service queue to exchange
  await ch.bindQueue(tripServiceEventsQueue.queue, "tripservice.trips.updates", "");

  // Bind snapshot queue to exchange
  await ch.bindQueue(snapshotQueue.queue, snapshotExchange, "");

  // Consume messages
  await ch.consume(locationQueue.queue, async (msg) => {
    if (msg) {
      try {
        await handlers.onLocationUpdate(msg.content);
        ch.ack(msg);
      } catch (error) {
        console.error("Error handling location update:", error);
        ch.nack(msg, false, false); // Reject and don't requeue
      }
    }
  });

  await ch.consume(vehicleEventsQueue.queue, async (msg) => {
    if (msg) {
      try {
        await handlers.onVehicleEvent(msg.content);
        ch.ack(msg);
      } catch (error) {
        console.error("Error handling vehicle event:", error);
        ch.nack(msg, false, false);
      }
    }
  });

  await ch.consume(driverEventsQueue.queue, async (msg) => {
    if (msg) {
      try {
        await handlers.onDriverEvent(msg.content);
        ch.ack(msg);
      } catch (error) {
        console.error("Error handling driver event:", error);
        ch.nack(msg, false, false);
      }
    }
  });

  await ch.consume(tripEventsQueue.queue, async (msg) => {
    if (msg) {
      try {
        await handlers.onTripEvent(msg.content);
        ch.ack(msg);
      } catch (error) {
        console.error("Error handling trip event:", error);
        ch.nack(msg, false, false);
      }
    }
  });

  // Consume Naviga trip updates
  await ch.consume(navigaTripUpdatesQueue.queue, async (msg) => {
    if (msg) {
      try {
        await handlers.onNavigaTripUpdate(msg.content);
        ch.ack(msg);
      } catch (error) {
        console.error("Error handling Naviga trip update:", error);
        ch.nack(msg, false, false);
      }
    }
  });

  // Consume Naviga location updates
  await ch.consume(navigaLocationUpdatesQueue.queue, async (msg) => {
    if (msg) {
      try {
        await handlers.onNavigaLocationUpdate(msg.content);
        ch.ack(msg);
      } catch (error) {
        console.error("Error handling Naviga location update:", error);
        ch.nack(msg, false, false);
      }
    }
  });

  // Consume Trip Service events
  await ch.consume(tripServiceEventsQueue.queue, async (msg) => {
    if (msg) {
      try {
        await handlers.onTripServiceEvent(msg.content);
        ch.ack(msg);
      } catch (error) {
        console.error("Error handling Trip Service event:", error);
        ch.nack(msg, false, false);
      }
    }
  });

  // Consume trip snapshot updates
  await ch.consume(snapshotQueue.queue, async (msg) => {
    if (msg) {
      try {
        await handlers.onTripSnapshotUpdate(msg.content);
        ch.ack(msg);
      } catch (error) {
        console.error("Error handling trip snapshot update:", error);
        ch.nack(msg, false, false);
      }
    }
  });

  console.log("RabbitMQ subscriptions set up successfully");
}

export async function closeRabbitMQ(): Promise<void> {
  try {
    if (channel) {
      try {
        await channel.close();
      } catch (err: any) {
        // Channel might already be closing/closed, ignore these specific errors
        const errorMessage = err?.message || "";
        const errorName = err?.name || "";
        if (
          errorName !== "IllegalOperationError" &&
          errorMessage !== "Channel closing" &&
          err?.code !== 406
        ) {
          console.error("Error closing RabbitMQ channel:", err.message);
        }
      }
      channel = null;
    }
  } catch (err) {
    // Ignore errors during cleanup
  }

  try {
    if (connection) {
      try {
        await (connection as any).close();
      } catch (err: any) {
        // Connection might already be closing/closed, ignore these specific errors
        const errorMessage = err?.message || "";
        const errorName = err?.name || "";
        if (
          errorName !== "IllegalOperationError" &&
          errorMessage !== "Channel closing" &&
          err?.code !== 406
        ) {
          console.error("Error closing RabbitMQ connection:", err.message);
        }
      }
      connection = null;
    }
  } catch (err) {
    // Ignore errors during cleanup
  }
}

