import { Pool, Client } from 'pg';
import dotenv from 'dotenv';
import path from 'path';
import { logger } from '../utils/logger';

// Load environment variables from .env file in project root
// Use process.cwd() to get the project root directory
const envPath = path.resolve(process.cwd(), '.env');
dotenv.config({ path: envPath });

if (!process.env.DATABASE_URL) {
  const error = new Error('DATABASE_URL environment variable is not set. Please check your .env file.');
  logger.error(error.message);
  throw error;
}

logger.debug('Database configuration loaded', {
  databaseUrl: process.env.DATABASE_URL ? 'set' : 'not set',
  nodeEnv: process.env.NODE_ENV || 'development',
});

// Parse DATABASE_URL to extract database name
function parseDatabaseUrl(url: string): { connectionString: string; database: string; adminUrl: string } {
  try {
    const urlObj = new URL(url);
    const database = urlObj.pathname.slice(1); // Remove leading '/'
    const adminUrl = url.replace(`/${database}`, '/postgres');
    return { connectionString: url, database, adminUrl };
  } catch (error) {
    const errorMessage = `Invalid DATABASE_URL format: ${url}`;
    logger.error(errorMessage, { error });
    throw new Error(errorMessage);
  }
}

const dbConfig = parseDatabaseUrl(process.env.DATABASE_URL);

// Function to create database if it doesn't exist
async function ensureDatabaseExists(): Promise<void> {
  const adminClient = new Client({
    connectionString: dbConfig.adminUrl,
  });

  try {
    await adminClient.connect();
    
    // Check if database exists
    logger.debug('Checking if database exists', { database: dbConfig.database });
    const result = await adminClient.query(
      'SELECT 1 FROM pg_database WHERE datname = $1',
      [dbConfig.database]
    );

    if (result.rows.length === 0) {
      // Database doesn't exist, create it
      logger.info(`Creating database "${dbConfig.database}"`);
      try {
        await adminClient.query(`CREATE DATABASE "${dbConfig.database}"`);
        logger.info(`Database "${dbConfig.database}" created successfully`);
      } catch (error) {
        logger.error(`Failed to create database "${dbConfig.database}"`, { error });
        throw error;
      }
    } else {
      logger.debug(`Database "${dbConfig.database}" already exists`);
    }
  } catch (error: any) {
    // If error is that database already exists, that's fine
    if (error.code === '42P04') {
      console.log(`Database "${dbConfig.database}" already exists`);
    } else {
      logger.error('Error ensuring database exists', { error });
      throw error;
    }
  } finally {
    try {
      await adminClient.end();
    } catch (error) {
      logger.error('Error closing admin client connection', { error });
    }
  }
}

// Create the connection pool with error handling and logging
export const pool = new Pool({
  connectionString: dbConfig.connectionString,
});

// Log connection events
pool.on('connect', () => {
  logger.debug('New database connection established');
});

pool.on('error', (error) => {
  logger.error('Unexpected error on idle client', { error });
});

pool.on('acquire', (client) => {
  logger.silly('Client checked out from the pool');
});

pool.on('remove', () => {
  logger.debug('Client removed from pool');
});

// Initialize database schema
export async function initializeDatabase() {
  const startTime = Date.now();
  logger.info('Initializing database...');
  
  try {
    await ensureDatabaseExists();
    
    logger.debug('Starting database schema initialization');
    const client = await pool.connect();
    try {
      // Create tables if they don't exist
      logger.debug('Creating database tables if they do not exist');
      await pool.query(`
        CREATE TABLE IF NOT EXISTS vehicles (
          id VARCHAR(255) PRIMARY KEY,
          company_id VARCHAR(255) NOT NULL,
          company_code VARCHAR(50) NOT NULL,
          plate VARCHAR(100) NOT NULL,
          model VARCHAR(100) NOT NULL,
          make VARCHAR(100) NOT NULL,
          capacity INTEGER NOT NULL,
          connection_status VARCHAR(20) NOT NULL,
          operational_status VARCHAR(20) NOT NULL,
          last_updated TIMESTAMP NOT NULL,
          current_latitude FLOAT,
          current_longitude FLOAT,
          location_timestamp TIMESTAMP,
          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
      `);
      
      // Add location columns if they don't exist (for existing databases)
      await client.query(`
        DO $$ 
        BEGIN
          IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_name = 'vehicles' AND column_name = 'current_latitude'
          ) THEN
            ALTER TABLE vehicles ADD COLUMN current_latitude FLOAT;
          END IF;
          
          IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_name = 'vehicles' AND column_name = 'current_longitude'
          ) THEN
            ALTER TABLE vehicles ADD COLUMN current_longitude FLOAT;
          END IF;
          
          IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_name = 'vehicles' AND column_name = 'location_timestamp'
          ) THEN
            ALTER TABLE vehicles ADD COLUMN location_timestamp TIMESTAMP;
          END IF;
        END $$;
      `);

      // Create workers table
      await client.query(`
        CREATE TABLE IF NOT EXISTS workers (
          id VARCHAR(255) PRIMARY KEY,
          name VARCHAR(255) NOT NULL,
          phone VARCHAR(50) NOT NULL,
          email VARCHAR(255) NOT NULL,
          license_number VARCHAR(100),
          status VARCHAR(50) NOT NULL,
          role VARCHAR(50) NOT NULL,
          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
      `);

      // Create vehicle_driver_links table
      await client.query(`
        CREATE TABLE IF NOT EXISTS vehicle_driver_links (
          vehicle_id VARCHAR(255) NOT NULL,
          driver_id VARCHAR(255) NOT NULL,
          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          PRIMARY KEY (vehicle_id, driver_id),
          UNIQUE (vehicle_id),
          FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE,
          FOREIGN KEY (driver_id) REFERENCES workers(id) ON DELETE CASCADE
        )
      `);

      // Create indexes for better query performance
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_vehicles_company_id ON vehicles(company_id)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_workers_role ON workers(role)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_vehicle_driver_links_vehicle_id ON vehicle_driver_links(vehicle_id)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_vehicle_driver_links_driver_id ON vehicle_driver_links(driver_id)
      `);

      // Create trips table
      await client.query(`
        CREATE TABLE IF NOT EXISTS trips (
          id VARCHAR(255) PRIMARY KEY,
          company_id VARCHAR(255) NOT NULL,
          vehicle_id VARCHAR(255) NOT NULL,
          driver_id VARCHAR(255),
          route_id VARCHAR(255),
          status VARCHAR(50) NOT NULL,
          departure_time TIMESTAMP,
          start_time TIMESTAMP,
          end_time TIMESTAMP,
          cancelled_time TIMESTAMP,
          distance FLOAT,
          seats INTEGER,
          price FLOAT,
          origin_custom_name VARCHAR(255),
          destination_custom_name VARCHAR(255),
          origin_latitude FLOAT,
          origin_longitude FLOAT,
          destination_latitude FLOAT,
          destination_longitude FLOAT,
          current_latitude FLOAT,
          current_longitude FLOAT,
          updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE,
          FOREIGN KEY (driver_id) REFERENCES workers(id) ON DELETE SET NULL
        )
      `);
      
      // Alter existing table to make driver_id nullable if it's not already
      await client.query(`
        DO $$ 
        BEGIN
          IF EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_name = 'trips' 
            AND column_name = 'driver_id' 
            AND is_nullable = 'NO'
          ) THEN
            ALTER TABLE trips ALTER COLUMN driver_id DROP NOT NULL;
            -- Update foreign key constraint to allow SET NULL
            ALTER TABLE trips DROP CONSTRAINT IF EXISTS trips_driver_id_fkey;
            ALTER TABLE trips ADD CONSTRAINT trips_driver_id_fkey 
              FOREIGN KEY (driver_id) REFERENCES workers(id) ON DELETE SET NULL;
          END IF;
        END $$;
      `);

      // Create company_trip_sync table to track latest synced trips per company
      await client.query(`
        CREATE TABLE IF NOT EXISTS company_trip_sync (
          company_id VARCHAR(255) PRIMARY KEY,
          latest_incomplete_trip_id VARCHAR(255),
          latest_complete_trip_id VARCHAR(255),
          last_sync_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )
      `);

      // Create indexes for trips table
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_trips_company_id ON trips(company_id)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_trips_vehicle_id ON trips(vehicle_id)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_trips_driver_id ON trips(driver_id)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_trips_status ON trips(status)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_trips_departure_time ON trips(departure_time)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_trips_updated_at ON trips(updated_at)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_trips_status_departure ON trips(status, departure_time DESC)
      `);

      // Create bookings table
      await client.query(`
        CREATE TABLE IF NOT EXISTS bookings (
          id VARCHAR(255) PRIMARY KEY,
          trip_id VARCHAR(255) NOT NULL,
          user_phone VARCHAR(50) NOT NULL,
          user_name VARCHAR(255) NOT NULL,
          pickup_location_id VARCHAR(255) NOT NULL,
          dropoff_location_id VARCHAR(255) NOT NULL,
          pickup_location_name VARCHAR(255),
          dropoff_location_name VARCHAR(255),
          number_of_tickets INTEGER NOT NULL,
          total_amount FLOAT NOT NULL,
          status VARCHAR(50) NOT NULL,
          booking_reference VARCHAR(255),
          payment_method VARCHAR(50),
          payment_status VARCHAR(50),
          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (trip_id) REFERENCES trips(id) ON DELETE CASCADE
        )
      `);

      // Add last_booking_fetch column to trips table if it doesn't exist
      await client.query(`
        DO $$ 
        BEGIN
          IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns 
            WHERE table_name = 'trips' AND column_name = 'last_booking_fetch'
          ) THEN
            ALTER TABLE trips ADD COLUMN last_booking_fetch TIMESTAMP;
          END IF;
        END $$;
      `);

      // Create indexes for bookings table
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_bookings_trip_id ON bookings(trip_id)
      `);
      await client.query(`
        CREATE INDEX IF NOT EXISTS idx_bookings_status ON bookings(status)
      `);

      const duration = Date.now() - startTime;
      logger.info('Database initialization completed successfully', { durationMs: duration });
    } catch (error: any) {
      const duration = Date.now() - startTime;
      logger.error('Error initializing database', { 
        error: error.message, 
        durationMs: duration,
        stack: error.stack 
      });
      throw error;
    } finally {
      client.release();
    }
  } catch (error: any) {
    const duration = Date.now() - startTime;
    logger.error('Fatal error during database initialization', { 
      error: error.message,
      durationMs: duration,
      stack: error.stack 
    });
    throw error;
  }
}

