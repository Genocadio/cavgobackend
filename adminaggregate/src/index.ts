import { ApolloServer } from "@apollo/server";
import { HeaderMap } from "@apollo/server";
import { ApolloServerPluginLandingPageLocalDefault } from "@apollo/server/plugin/landingPage/default";
import express from "express";
import cors from "cors";
import { createServer } from "http";
import { WebSocketServer } from "ws";
import { makeServer } from "graphql-ws";
import { makeExecutableSchema } from "@graphql-tools/schema";
import * as db from "./db";
import { typeDefs, resolvers } from "./graphql";
import * as syncService from "./services/syncService";
import * as rabbitmq from "./services/rabbitmq";
import * as eventHandlers from "./services/eventHandlers";
import * as tripPolling from "./services/tripPolling";
import { migrate } from "./db/migrate";

/**
 * Entry point for the admin aggregator service.
 * Starts the Apollo standalone server with Playground enabled.
 * Also syncs initial data and sets up RabbitMQ subscriptions.
 */
export async function main(): Promise<void> {
  const PORT = Number(process.env.PORT ?? 4000);

  // 0. Run database migrations
  try {
    await migrate();
  } catch (error) {
    console.error("Failed to run database migrations:", error);
    throw error; // Don't continue if migrations fail
  }

  // 1. Sync initial data from main API
  try {
    await syncService.syncAllData();
  } catch (error) {
    console.error("\n⚠️  Failed to sync initial data from main API:");
    if (error instanceof Error) {
      console.error(`   Error: ${error.message}`);
      if (error.message.includes('Cannot connect to main API')) {
        console.error(`   The application will continue running, but data sync will be unavailable until the main API is accessible.`);
        console.error(`   RabbitMQ event handlers will still process real-time updates.`);
      }
    } else {
      console.error("   Unknown error:", error);
    }
    console.error("   Continuing with application startup...\n");
    // Continue anyway - we can sync later
  }

  // 1.5. Sync trips from main API
  let stopTripPolling: (() => void) | null = null;
  try {
    await syncService.syncTrips();
    // Start trip polling service
    stopTripPolling = tripPolling.startTripPolling();
  } catch (error) {
    console.error("\n⚠️  Failed to sync initial trips from main API:");
    if (error instanceof Error) {
      console.error(`   Error: ${error.message}`);
    } else {
      console.error("   Unknown error:", error);
    }
    console.error("   Continuing with application startup...\n");
    // Start polling anyway - it will retry
    stopTripPolling = tripPolling.startTripPolling();
  }

  // 2. Setup RabbitMQ subscriptions
  try {
    await rabbitmq.connectRabbitMQ();
    await rabbitmq.setupSubscriptions({
      onVehicleEvent: eventHandlers.handleVehicleEvent,
      onDriverEvent: eventHandlers.handleDriverEvent,
      onLocationUpdate: eventHandlers.handleLocationUpdate,
      onTripEvent: eventHandlers.handleTripEvent,
      onNavigaTripUpdate: eventHandlers.handleNavigaTripUpdate,
      onNavigaLocationUpdate: eventHandlers.handleNavigaLocationUpdate,
      onTripServiceEvent: eventHandlers.handleTripServiceEvent,
      onTripSnapshotUpdate: eventHandlers.handleTripSnapshotUpdate,
    });
  } catch (error) {
    console.error("Failed to setup RabbitMQ subscriptions:", error);
    // Continue anyway - API will still work
  }

  // 3. Create executable schema for subscriptions
  const schema = makeExecutableSchema({ typeDefs, resolvers });

  // 4. Create Express app and HTTP server
  const app = express();
  const httpServer = createServer(app);

  // 5. Create WebSocket server for subscriptions
  const wsServer = new WebSocketServer({
    server: httpServer,
    path: "/graphql",
  });

  // 6. Set up WebSocket server for GraphQL subscriptions using graphql-ws
  const wsServerImpl = makeServer({
    schema,
  });

  // Handle WebSocket connections
  wsServer.on("connection", (socket, request) => {
    // Check CORS for WebSocket connections
    const origin = request.headers.origin;
    if (origin) {
      const allowedOrigins = [
        "https://admin.gocavgo.com",
        "http://localhost:3000",
        "http://localhost:3001",
        "http://127.0.0.1:3000",
        "http://127.0.0.1:3001",
      ];
      if (!allowedOrigins.includes(origin) && !origin.startsWith("http://localhost:") && !origin.startsWith("http://127.0.0.1:")) {
        socket.close(1008, "Not allowed by CORS");
        return;
      }
    }

    const closed = wsServerImpl.opened(
      {
        protocol: socket.protocol || "graphql-transport-ws",
        send: (data: string) => {
          if (socket.readyState === 1) {
            socket.send(data);
          }
        },
        close: (code: number, reason: string) => {
          socket.close(code, reason);
        },
        onMessage: (cb: (message: string) => void) => {
          socket.on("message", (data) => {
            if (typeof data === "string") {
              cb(data);
            } else {
              cb(data.toString());
            }
          });
        },
      },
      { socket, request }
    );

    socket.on("close", () => {
      closed();
    });

    socket.on("error", () => {
      closed();
    });
  });

  // 7. Start Apollo Server
  const apollo = new ApolloServer({
    schema,
    introspection: true,
    plugins: [
      ApolloServerPluginLandingPageLocalDefault({ embed: true }),
    ],
  });

  await apollo.start();

  // 7.5. Set up CORS middleware
  app.use(
    cors({
      origin: (origin, callback) => {
        // Allow requests with no origin (like mobile apps, Postman, or curl)
        if (!origin) {
          return callback(null, true);
        }
        // Allow https://admin.gocavgo.com
        if (origin === "https://admin.gocavgo.com") {
          return callback(null, true);
        }
        // Allow localhost for development
        if (origin.startsWith("http://localhost:") || origin.startsWith("http://127.0.0.1:")) {
          return callback(null, true);
        }
        // Reject other origins
        callback(new Error("Not allowed by CORS"));
      },
      credentials: true,
      methods: ["GET", "POST", "OPTIONS"],
      allowedHeaders: ["Content-Type", "Authorization"],
    })
  );

  // 8. Set up Express middleware for GraphQL (handles both GET and POST)
  app.use("/graphql", express.json(), async (req, res, next) => {
    try {
      // Convert Express request to Apollo Server format
      const url = new URL(req.url || "/graphql", `http://${req.headers.host}`);
      
      // Create HeaderMap from Express headers
      const headerMap = new HeaderMap();
      Object.entries(req.headers).forEach(([key, value]) => {
        if (typeof value === "string") {
          headerMap.set(key.toLowerCase(), value);
        } else if (Array.isArray(value)) {
          headerMap.set(key.toLowerCase(), value.join(", "));
        }
      });

      const result = await apollo.executeHTTPGraphQLRequest({
        httpGraphQLRequest: {
          method: req.method || "GET",
          headers: headerMap,
          search: url.search,
          body: req.method === "POST" ? (req.body || {}) : undefined,
        },
        context: async () => ({}),
      });

      // Set response status
      res.status(result.status || 200);

      // Handle response body
      if (result.body.kind === "complete") {
        // Check if it's HTML (for landing page) or JSON
        const contentType = result.body.string.trim().startsWith("<") 
          ? "text/html" 
          : "application/json";
        res.setHeader("Content-Type", contentType);
        res.send(result.body.string);
      } else {
        // Stream response for subscriptions
        res.setHeader("Content-Type", "multipart/mixed; boundary=graphql");
        for await (const chunk of result.body.asyncIterator) {
          res.write(chunk);
        }
        res.end();
      }
    } catch (error) {
      console.error("GraphQL request error:", error);
      if (!res.headersSent) {
        res.status(500).json({ error: "Internal server error" });
      }
    }
  });

  // 9. Start HTTP server
  httpServer.listen(PORT, () => {
    console.log(`Admin aggregator listening on http://localhost:${PORT}/graphql`);
    console.log(`WebSocket server ready at ws://localhost:${PORT}/graphql`);
  });

  // Graceful shutdown
  process.on("SIGINT", async () => {
    console.log("Shutting down...");
    try {
      if (stopTripPolling) {
        stopTripPolling();
      }
      await rabbitmq.closeRabbitMQ();
    } catch (err) {
      // Ignore errors during shutdown
    }
    process.exit(0);
  });

  process.on("SIGTERM", async () => {
    console.log("Shutting down...");
    try {
      if (stopTripPolling) {
        stopTripPolling();
      }
      await rabbitmq.closeRabbitMQ();
    } catch (err) {
      // Ignore errors during shutdown
    }
    process.exit(0);
  });
}

if (require.main === module) {
  void main().catch((err) => {
    console.error("Failed to start admin aggregator", err);
    process.exit(1);
  });
}

/**
 * Database helpers and repositories can be imported from `db`, e.g.
 * `db.driverRepository.createDriver(...)` or `db.tripRepository.createTrip(...)`.
 * GraphQL queries include `carsByCompany(companyId)` and `driversByCompany(companyId)`
 * so multi-company clients can scope their dashboards using the company ID shown on each entity.
 */
export { db };

