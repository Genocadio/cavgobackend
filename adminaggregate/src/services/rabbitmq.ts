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
}

export async function setupSubscriptions(handlers: MessageHandlers): Promise<void> {
  const ch = await getChannel();

  // Declare fanout exchanges (durable to match existing exchanges)
  await ch.assertExchange("vehicle.location.updates.fanout", "fanout", { durable: true });
  await ch.assertExchange("vehicle.events", "fanout", { durable: true });
  await ch.assertExchange("driver.events", "fanout", { durable: true });
  await ch.assertExchange("trips.fanout", "fanout", { durable: true });

  // Create queues for each exchange (fanout exchanges need queues)
  const locationQueue = await ch.assertQueue("", { exclusive: true });
  const vehicleEventsQueue = await ch.assertQueue("", { exclusive: true });
  const driverEventsQueue = await ch.assertQueue("", { exclusive: true });
  const tripEventsQueue = await ch.assertQueue("", { exclusive: true });

  // Bind queues to exchanges
  await ch.bindQueue(locationQueue.queue, "vehicle.location.updates.fanout", "");
  await ch.bindQueue(vehicleEventsQueue.queue, "vehicle.events", "");
  await ch.bindQueue(driverEventsQueue.queue, "driver.events", "");
  await ch.bindQueue(tripEventsQueue.queue, "trips.fanout", "");

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

