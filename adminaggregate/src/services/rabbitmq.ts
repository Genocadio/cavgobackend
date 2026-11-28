import { config } from "dotenv";
import * as amqp from "amqplib";

config();

const RABBITMQ_HOST = process.env.RABBITMQ_HOST || "localhost";
const RABBITMQ_PORT = Number(process.env.RABBITMQ_PORT) || 5672;
const RABBITMQ_USERNAME = process.env.RABBITMQ_USERNAME || "admin";
const RABBITMQ_PASSWORD = process.env.RABBITMQ_PASSWORD || "admin";

const INITIAL_RECONNECT_DELAY_MS = 1_000;
const MAX_RECONNECT_DELAY_MS = 30_000;
// Exponent is capped so delay grows 1s, 2s, 4s, 8s, 16s, 30s, 30s, ...
const MAX_RECONNECT_EXPONENT = 5;

// Recent @types/amqplib exposes the connection methods on ChannelModel; keep
// them visible internally while retaining the existing exported Connection API.
type ManagedConnection = amqp.Connection & {
  close(): Promise<void>;
  createChannel(): Promise<amqp.Channel>;
};

let connection: ManagedConnection | null = null;
let channel: amqp.Channel | null = null;

// Handlers are retained so subscriptions (exchanges, queues, bindings and
// consumers) can be re-asserted on a fresh connection after a broker restart.
let messageHandlers: MessageHandlers | null = null;
let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
let reconnectAttempts = 0;
let reconnecting = false;
let intentionallyClosed = false;
// Changed by closeRabbitMQ so an in-flight retry can detect the shutdown even
// if the close completes before the retry's next await resumes.
let closeGeneration = 0;
const consumerTags = new Map<string, string>();

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

function describeError(error: unknown): { message: string; code?: string | number } {
  const err = error as { message?: unknown; code?: unknown } | null | undefined;
  const code = err?.code;
  return {
    message: err?.message != null ? String(err.message) : String(error),
    ...(typeof code === "string" || typeof code === "number" ? { code } : {}),
  };
}

function logError(event: string, error: unknown, extra: Record<string, unknown> = {}): void {
  // Never log the AMQP URL: it embeds the broker credentials.
  console.error(JSON.stringify({
    level: "ERROR",
    event,
    ...describeError(error),
    ...extra,
  }));
}

function clearConnectionState(conn?: ManagedConnection | null): void {
  // Ignore late events from a connection that has already been replaced.
  if (conn && connection && connection !== conn) {
    return;
  }
  connection = null;
  channel = null;
  consumerTags.clear();
}

async function closeChannelQuietly(ch: amqp.Channel | null): Promise<void> {
  if (!ch) {
    return;
  }
  try {
    await ch.close();
  } catch {
    // Channel is already closing/closed after a broker failure.
  }
}

async function closeConnectionQuietly(conn: ManagedConnection | null): Promise<void> {
  if (!conn) {
    return;
  }
  try {
    await conn.close();
  } catch {
    // Connection is already closing/closed after a broker failure.
  }
}

function scheduleReconnect(reason: string): void {
  if (intentionallyClosed) {
    console.log(JSON.stringify({
      level: "INFO",
      event: "RABBITMQ_RECONNECT_SUPPRESSED",
      reason,
      cause: "deliberate_close",
    }));
    return;
  }
  if (!messageHandlers) {
    console.warn(JSON.stringify({
      level: "WARN",
      event: "RABBITMQ_RECONNECT_SKIPPED",
      reason,
      cause: "no_handlers",
    }));
    return;
  }
  // A single timer per process: repeated error/close events must not stack
  // reconnects or create duplicate consumers.
  if (reconnectTimer || reconnecting) {
    return;
  }

  const attempt = reconnectAttempts + 1;
  const exponentialMs = INITIAL_RECONNECT_DELAY_MS * Math.pow(2, Math.min(attempt - 1, MAX_RECONNECT_EXPONENT));
  const delayMs = Math.min(exponentialMs, MAX_RECONNECT_DELAY_MS);
  console.warn(JSON.stringify({
    level: "WARN",
    event: "RABBITMQ_RECONNECT_SCHEDULED",
    reason,
    attempt,
    delayMs,
  }));

  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    void runReconnect(reason);
  }, delayMs);
  reconnectTimer.unref?.();
}

async function runReconnect(reason: string): Promise<void> {
  if (intentionallyClosed || reconnecting || !messageHandlers) {
    return;
  }

  reconnecting = true;
  const generationAtStart = closeGeneration;
  const attempt = reconnectAttempts + 1;
  console.warn(JSON.stringify({
    level: "WARN",
    event: "RABBITMQ_RECONNECT_ATTEMPT",
    reason,
    attempt,
  }));

  let succeeded = false;
  try {
    await connectRabbitMQ(messageHandlers);
    if (intentionallyClosed || closeGeneration !== generationAtStart) {
      // Shutdown started while the connection was opening.
      const openedConnection = connection;
      clearConnectionState();
      await closeConnectionQuietly(openedConnection);
      return;
    }
    await setupSubscriptions(messageHandlers);
    if (intentionallyClosed || closeGeneration !== generationAtStart) {
      const openedConnection = connection;
      clearConnectionState();
      await closeConnectionQuietly(openedConnection);
      return;
    }
    reconnectAttempts = 0;
    succeeded = true;
    console.log(JSON.stringify({
      level: "INFO",
      event: "RABBITMQ_RECONNECT_SUCCEEDED",
      attempt,
    }));
  } catch (error) {
    reconnectAttempts = attempt;
    logError("RABBITMQ_RECONNECT_FAILED", error, { attempt });
    // Drop a half-open connection/channel so the next attempt starts clean.
    const staleConnection = connection;
    clearConnectionState();
    await closeConnectionQuietly(staleConnection);
  } finally {
    reconnecting = false;
  }

  if (!succeeded) {
    scheduleReconnect("previous_attempt_failed");
  } else if (!intentionallyClosed && closeGeneration === generationAtStart && (!connection || !channel)) {
    // The broker may have dropped the new connection between setup and here;
    // the connection listener's schedule was suppressed while reconnecting.
    scheduleReconnect("connection_lost_after_reconnect");
  }
}

export async function connectRabbitMQ(handlers?: MessageHandlers): Promise<amqp.Connection> {
  if (handlers) {
    messageHandlers = handlers;
  }

  if (connection) {
    return connection;
  }

  const url = `amqp://${RABBITMQ_USERNAME}:${RABBITMQ_PASSWORD}@${RABBITMQ_HOST}:${RABBITMQ_PORT}`;
  try {
    const conn = await amqp.connect(url) as unknown as ManagedConnection;
    connection = conn;
    const generationAtConnect = closeGeneration;

    conn.on("error", (err) => {
      logError("RABBITMQ_CONNECTION_ERROR", err);
      if (closeGeneration !== generationAtConnect) {
        return; // stale event from a deliberately closed connection
      }
      clearConnectionState(conn);
      scheduleReconnect("connection_error");
    });

    conn.on("close", () => {
      console.log("RabbitMQ connection closed");
      if (closeGeneration !== generationAtConnect) {
        return;
      }
      clearConnectionState(conn);
      scheduleReconnect("connection_closed");
    });

    return conn;
  } catch (error) {
    logError("RABBITMQ_CONNECT_FAILED", error);
    // Covers a broker that was down at process startup: index.ts passes the
    // handlers up front so the retry loop can finish the subscription setup.
    scheduleReconnect("connect_failed");
    throw error;
  }
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

  const activeConnection = connection;
  const ch = await activeConnection.createChannel() as amqp.Channel;
  if (connection !== activeConnection) {
    await closeChannelQuietly(ch);
    throw new Error("RabbitMQ connection changed while creating channel");
  }
  const generationAtChannel = closeGeneration;

  ch.on("error", (err) => {
    logError("RABBITMQ_CHANNEL_ERROR", err);
    if (channel !== ch || closeGeneration !== generationAtChannel) {
      return;
    }
    channel = null;
    consumerTags.clear();
    scheduleReconnect("channel_error");
  });

  ch.on("close", () => {
    if (channel !== ch || closeGeneration !== generationAtChannel) {
      return;
    }
    channel = null;
    consumerTags.clear();
    scheduleReconnect("channel_closed");
  });

  channel = ch;
  return ch;
}

export async function setupSubscriptions(handlers: MessageHandlers): Promise<void> {
  // Retain the latest handlers for reconnect; setupSubscriptions is safe to
  // re-run on a fresh channel after the broker restarts.
  messageHandlers = handlers;

  try {
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

    // Named durable queues survive restarts. Anonymous exclusive queues silently
    // dropped terminal trip events while adminaggregate was redeploying, which
    // left stale active-trip projections behind.
    const locationQueue = await ch.assertQueue("adminaggregate.location.updates.queue", { durable: true });
    const vehicleEventsQueue = await ch.assertQueue("adminaggregate.vehicle.events.queue", { durable: true });
    const driverEventsQueue = await ch.assertQueue("adminaggregate.driver.events.queue", { durable: true });
    const tripEventsQueue = await ch.assertQueue("adminaggregate.trip.events.queue", { durable: true });

    // Create queues for Naviga exchanges
    const navigaTripUpdatesQueue = await ch.assertQueue("adminaggregate.naviga.trip.updates.queue", { durable: true });
    const navigaLocationUpdatesQueue = await ch.assertQueue("adminaggregate.naviga.location.updates.queue", { durable: true });

    // Create queue for Trip Service exchange
    const tripServiceEventsQueue = await ch.assertQueue("adminaggregate.tripservice.events.queue", { durable: true });

    // Create queue for Booking Service trip snapshots
    const snapshotQueue = await ch.assertQueue("adminaggregate.trip.snapshots.queue", { durable: true });

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
    function logRawMessage(msg: amqp.ConsumeMessage) {
      try {
        const meta = {
          exchange: msg.fields.exchange,
          routingKey: msg.fields.routingKey,
          deliveryTag: msg.fields.deliveryTag,
          properties: msg.properties,
        };
        console.log(JSON.stringify({
          level: "DEBUG",
          event: "RABBITMQ_MESSAGE_RECEIVED",
          meta,
          content: msg.content.toString(),
        }));
      } catch (err) {
        console.error("Failed to log raw RabbitMQ message:", err);
      }
    }

    async function consumeQueue(
      queue: string,
      handler: (message: Buffer) => Promise<void>,
      errorEvent: string
    ): Promise<void> {
      // If setup is re-run on the same channel, drop the old consumer first so
      // messages are not delivered to two handlers.
      const previousTag = consumerTags.get(queue);
      if (previousTag) {
        try {
          await ch.cancel(previousTag);
        } catch {
          // Consumer is already gone (stale channel).
        }
        consumerTags.delete(queue);
      }

      const result = await ch.consume(queue, async (msg) => {
        if (msg) {
          logRawMessage(msg);
          try {
            await handler(msg.content);
            ch.ack(msg);
          } catch (error) {
            logError(errorEvent, error, { queue });
            ch.nack(msg, false, false); // Reject and don't requeue
          }
        }
      });
      consumerTags.set(queue, result.consumerTag);
    }

    await consumeQueue(locationQueue.queue, handlers.onLocationUpdate, "RABBITMQ_LOCATION_HANDLER_FAILED");
    await consumeQueue(vehicleEventsQueue.queue, handlers.onVehicleEvent, "RABBITMQ_VEHICLE_HANDLER_FAILED");
    await consumeQueue(driverEventsQueue.queue, handlers.onDriverEvent, "RABBITMQ_DRIVER_HANDLER_FAILED");
    await consumeQueue(tripEventsQueue.queue, handlers.onTripEvent, "RABBITMQ_TRIP_HANDLER_FAILED");
    await consumeQueue(navigaTripUpdatesQueue.queue, handlers.onNavigaTripUpdate, "RABBITMQ_NAVIGA_TRIP_HANDLER_FAILED");
    await consumeQueue(navigaLocationUpdatesQueue.queue, handlers.onNavigaLocationUpdate, "RABBITMQ_NAVIGA_LOCATION_HANDLER_FAILED");
    await consumeQueue(tripServiceEventsQueue.queue, handlers.onTripServiceEvent, "RABBITMQ_TRIP_SERVICE_HANDLER_FAILED");
    await consumeQueue(snapshotQueue.queue, handlers.onTripSnapshotUpdate, "RABBITMQ_SNAPSHOT_HANDLER_FAILED");

    console.log("RabbitMQ subscriptions set up successfully");
  } catch (error) {
    logError("RABBITMQ_SUBSCRIPTIONS_SETUP_FAILED", error);
    const staleChannel = channel;
    clearConnectionState();
    await closeChannelQuietly(staleChannel);
    scheduleReconnect("subscription_setup_failed");
    throw error;
  }
}

export async function closeRabbitMQ(): Promise<void> {
  // Suppress reconnect timers and error/close listeners while shutting down.
  closeGeneration += 1;
  intentionallyClosed = true;
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
    reconnectTimer = null;
  }

  const staleChannel = channel;
  const staleConnection = connection;
  channel = null;
  connection = null;
  consumerTags.clear();

  try {
    if (staleChannel) {
      try {
        await staleChannel.close();
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
    }
  } catch (err) {
    // Ignore errors during cleanup
  }

  try {
    if (staleConnection) {
      try {
        await staleConnection.close();
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
    }
  } catch (err) {
    // Ignore errors during cleanup
  }

  // Allow a later explicit connect (e.g. in-process restart/tests) to work.
  intentionallyClosed = false;
}
