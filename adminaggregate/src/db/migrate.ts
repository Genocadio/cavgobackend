import { sql } from "drizzle-orm";
import { db, pgPool } from "./client";

/**
 * Creates all database tables and enums if they don't exist.
 * This should be run before the application starts.
 */
export async function migrate(): Promise<void> {
  console.log("Running database migrations...");

  const client = await pgPool.connect();
  try {
    // Create enums
    await client.query(`
      DO $$ BEGIN
        CREATE TYPE status_enum AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED', 'PENDING_VERIFICATION');
      EXCEPTION
        WHEN duplicate_object THEN null;
      END $$;
    `);

    await client.query(`
      DO $$ BEGIN
        CREATE TYPE company_status_enum AS ENUM ('ACTIVE', 'INACTIVE', 'SUSPENDED');
      EXCEPTION
        WHEN duplicate_object THEN null;
      END $$;
    `);

    await client.query(`
      DO $$ BEGIN
        CREATE TYPE vehicle_status_enum AS ENUM ('AVAILABLE', 'MAINTENANCE', 'OUT_OF_SERVICE', 'OCCUPIED');
      EXCEPTION
        WHEN duplicate_object THEN null;
      END $$;
    `);

    await client.query(`
      DO $$ BEGIN
        CREATE TYPE trip_status_enum AS ENUM ('scheduled', 'in_progress', 'completed', 'cancelled');
      EXCEPTION
        WHEN duplicate_object THEN null;
      END $$;
    `);

    await client.query(`
      DO $$ BEGIN
        CREATE TYPE booking_status_enum AS ENUM ('pending', 'confirmed', 'cancelled', 'completed', 'used', 'expired');
      EXCEPTION
        WHEN duplicate_object THEN null;
      END $$;
    `);

    await client.query(`
      DO $$ BEGIN
        CREATE TYPE payment_type_enum AS ENUM ('cash', 'epayment', 'card');
      EXCEPTION
        WHEN duplicate_object THEN null;
      END $$;
    `);

    // Create companies table
    await client.query(`
      CREATE TABLE IF NOT EXISTS companies (
        id TEXT PRIMARY KEY,
        company_name TEXT NOT NULL,
        email TEXT NOT NULL,
        phone TEXT NOT NULL,
        address TEXT,
        city TEXT,
        company_code TEXT NOT NULL,
        status company_status_enum NOT NULL,
        created_at TIMESTAMPTZ,
        updated_at TIMESTAMPTZ,
        created_by TEXT,
        updated_by TEXT
      );
    `);

    // Create drivers table
    await client.query(`
      CREATE TABLE IF NOT EXISTS drivers (
        id TEXT PRIMARY KEY,
        first_name TEXT NOT NULL,
        last_name TEXT NOT NULL,
        phone_number TEXT NOT NULL,
        email TEXT NOT NULL,
        status status_enum NOT NULL,
        company_id TEXT NOT NULL,
        date_of_birth DATE,
        address TEXT,
        license_number TEXT,
        license_expiry DATE,
        role TEXT,
        created_at TIMESTAMPTZ,
        updated_at TIMESTAMPTZ
      );
    `);

    // Create cars table
    await client.query(`
      CREATE TABLE IF NOT EXISTS cars (
        id TEXT PRIMARY KEY,
        plate TEXT NOT NULL,
        model TEXT NOT NULL,
        make TEXT,
        vehicle_type TEXT,
        capacity INTEGER NOT NULL,
        status vehicle_status_enum NOT NULL,
        is_online BOOLEAN NOT NULL DEFAULT false,
        company_id TEXT NOT NULL,
        current_location_latitude DOUBLE PRECISION,
        current_location_longitude DOUBLE PRECISION,
        current_location_speed DOUBLE PRECISION,
        current_location_bearing DOUBLE PRECISION,
        current_location_timestamp TIMESTAMPTZ,
        created_at TIMESTAMPTZ,
        updated_at TIMESTAMPTZ
      );
    `);

    // Create trip_locations table
    await client.query(`
      CREATE TABLE IF NOT EXISTS trip_locations (
        id TEXT PRIMARY KEY,
        address TEXT NOT NULL,
        latitude DOUBLE PRECISION NOT NULL,
        longitude DOUBLE PRECISION NOT NULL,
        created_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Create driver_car_assignments table
    await client.query(`
      CREATE TABLE IF NOT EXISTS driver_car_assignments (
        id SERIAL PRIMARY KEY,
        driver_id TEXT REFERENCES drivers(id),
        car_id TEXT NOT NULL REFERENCES cars(id),
        assigned_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Add unique constraint on car_id if it doesn't exist (for 1:1 car-driver relationship)
    // Check if any unique constraint exists on car_id column
    const constraintCheck = await client.query(`
      SELECT 1 FROM pg_constraint c
      JOIN pg_class t ON c.conrelid = t.oid
      JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(c.conkey)
      WHERE t.relname = 'driver_car_assignments'
      AND a.attname = 'car_id'
      AND c.contype = 'u'
      LIMIT 1;
    `);
    
    if (constraintCheck.rows.length === 0) {
      await client.query(`
        ALTER TABLE driver_car_assignments 
        ADD CONSTRAINT driver_car_assignments_car_id_key UNIQUE (car_id);
      `);
    }

    // Create trips table
    await client.query(`
      CREATE TABLE IF NOT EXISTS trips (
        id TEXT PRIMARY KEY,
        driver_car_assignment_id INTEGER NOT NULL REFERENCES driver_car_assignments(id),
        origin_location_id TEXT NOT NULL REFERENCES trip_locations(id),
        status trip_status_enum NOT NULL,
        total_distance DOUBLE PRECISION NOT NULL DEFAULT 0,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        updated_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Create trip_destinations table
    await client.query(`
      CREATE TABLE IF NOT EXISTS trip_destinations (
        id TEXT PRIMARY KEY,
        trip_id TEXT NOT NULL REFERENCES trips(id),
        location_id TEXT NOT NULL REFERENCES trip_locations(id),
        "order" INTEGER,
        index INTEGER NOT NULL,
        fare NUMERIC(12, 2) NOT NULL,
        remaining_distance DOUBLE PRECISION,
        is_passede BOOLEAN NOT NULL DEFAULT false,
        passed_time DOUBLE PRECISION,
        created_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Create bookings table
    await client.query(`
      CREATE TABLE IF NOT EXISTS bookings (
        id TEXT PRIMARY KEY,
        trip_id TEXT NOT NULL REFERENCES trips(id),
        passenger_name TEXT,
        passenger_phone TEXT,
        pickup_location_id TEXT NOT NULL REFERENCES trip_locations(id),
        dropoff_location_id TEXT NOT NULL REFERENCES trip_locations(id),
        number_of_tickets INTEGER NOT NULL,
        total_fare NUMERIC(12, 2) NOT NULL,
        payment_type payment_type_enum,
        status booking_status_enum NOT NULL,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        updated_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Create driver_metrics table
    await client.query(`
      CREATE TABLE IF NOT EXISTS driver_metrics (
        driver_id TEXT PRIMARY KEY REFERENCES drivers(id),
        total_revenue NUMERIC(14, 2) NOT NULL DEFAULT 0,
        total_trips INTEGER NOT NULL DEFAULT 0,
        total_distance DOUBLE PRECISION NOT NULL DEFAULT 0,
        updated_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Create car_metrics table
    await client.query(`
      CREATE TABLE IF NOT EXISTS car_metrics (
        car_id TEXT PRIMARY KEY REFERENCES cars(id),
        total_revenue NUMERIC(14, 2) NOT NULL DEFAULT 0,
        total_trips INTEGER NOT NULL DEFAULT 0,
        total_distance DOUBLE PRECISION NOT NULL DEFAULT 0,
        updated_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Create trip_metrics table
    await client.query(`
      CREATE TABLE IF NOT EXISTS trip_metrics (
        trip_id TEXT PRIMARY KEY REFERENCES trips(id),
        company_id TEXT NOT NULL,
        total_fare NUMERIC(14, 2) NOT NULL DEFAULT 0,
        total_distance DOUBLE PRECISION NOT NULL DEFAULT 0,
        total_duration DOUBLE PRECISION NOT NULL DEFAULT 0,
        started_at TIMESTAMPTZ,
        completed_at TIMESTAMPTZ,
        trip_created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Create trip_destination_metrics table
    await client.query(`
      CREATE TABLE IF NOT EXISTS trip_destination_metrics (
        id SERIAL PRIMARY KEY,
        trip_id TEXT NOT NULL REFERENCES trip_metrics(trip_id),
        destination_id TEXT NOT NULL REFERENCES trip_destinations(id),
        number_of_bookings INTEGER NOT NULL DEFAULT 0,
        total_revenue NUMERIC(14, 2) NOT NULL DEFAULT 0,
        created_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Create car_locations table
    await client.query(`
      CREATE TABLE IF NOT EXISTS car_locations (
        id SERIAL PRIMARY KEY,
        car_id TEXT NOT NULL REFERENCES cars(id),
        driver_id TEXT REFERENCES drivers(id),
        latitude DOUBLE PRECISION NOT NULL,
        longitude DOUBLE PRECISION NOT NULL,
        speed DOUBLE PRECISION NOT NULL,
        bearing DOUBLE PRECISION NOT NULL,
        accuracy DOUBLE PRECISION NOT NULL,
        timestamp TIMESTAMPTZ NOT NULL
      );
    `);

    // Create trip_snapshots table to persist booking snapshots between restarts
    await client.query(`
      CREATE TABLE IF NOT EXISTS trip_snapshots (
        trip_id TEXT PRIMARY KEY,
        snapshot JSONB NOT NULL,
        updated_at TIMESTAMPTZ DEFAULT NOW()
      );
    `);

    // Alter car_locations table to make bearing and accuracy nullable (for existing tables)
    // This handles cases where the table already exists with NOT NULL constraints
    await client.query(`
      DO $$ 
      BEGIN
        -- Make bearing nullable if it's currently NOT NULL
        IF EXISTS (
          SELECT 1 FROM information_schema.columns 
          WHERE table_name = 'car_locations' 
          AND column_name = 'bearing' 
          AND is_nullable = 'NO'
        ) THEN
          ALTER TABLE car_locations ALTER COLUMN bearing DROP NOT NULL;
        END IF;
        
        -- Make accuracy nullable if it's currently NOT NULL
        IF EXISTS (
          SELECT 1 FROM information_schema.columns 
          WHERE table_name = 'car_locations' 
          AND column_name = 'accuracy' 
          AND is_nullable = 'NO'
        ) THEN
          ALTER TABLE car_locations ALTER COLUMN accuracy DROP NOT NULL;
        END IF;
        
        -- Add order column to trip_destinations if it doesn't exist
        IF NOT EXISTS (
          SELECT 1 FROM information_schema.columns 
          WHERE table_name = 'trip_destinations' 
          AND column_name = 'order'
        ) THEN
          ALTER TABLE trip_destinations ADD COLUMN "order" INTEGER;
        END IF;
      END $$;
    `);

    console.log("Database migrations completed successfully");
  } finally {
    client.release();
  }
}

