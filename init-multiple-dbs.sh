#!/bin/bash
set -e

function create_db_if_not_exists() {
  DB_NAME=$1
  echo "Checking if database '$DB_NAME' exists..."
  DB_EXISTS=$(psql -U "$POSTGRES_USER" -tAc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'")

  if [ "$DB_EXISTS" != "1" ]; then
    echo "Creating database '$DB_NAME'..."
    createdb -U "$POSTGRES_USER" "$DB_NAME"
    echo "Database '$DB_NAME' created successfully."
  else
    echo "Database '$DB_NAME' already exists. Skipping."
  fi
}

# Create databases for all microservices
echo "Initializing databases for CAVGO microservices..."

# Create database for cavgomain (Java service)
create_db_if_not_exists "cavgomain"

# Create database for cavgotrips (Go service)
create_db_if_not_exists "cavgotrips"

# Create database for cavgobooks (Go service)
create_db_if_not_exists "cavgobooks"

create_db_if_not_exists "cavgomqt"

create_db_if_not_exists "ridehail"
echo "Database initialization completed successfully!"

