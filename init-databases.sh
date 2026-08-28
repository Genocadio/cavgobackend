#!/bin/bash
set -e

echo "Creating application databases..."

DATABASES=(
  "cavgomain"
  "ridehail"
  "cavgotrips"
  "cavgobooks"
  "cavgomqt"
  "navigation"
  "adminaggregate"
  "ikuriye"
)

for DB in "${DATABASES[@]}"; do
  echo "Checking database: $DB"

  if psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
      "SELECT 1 FROM pg_database WHERE datname='$DB'" | grep -q 1; then
    echo "Database $DB already exists"
  else
    echo "Creating database $DB..."
    psql -v ON_ERROR_STOP=1 \
      -U "$POSTGRES_USER" \
      -d "$POSTGRES_DB" \
      -c "CREATE DATABASE \"$DB\";"
    echo "Database $DB created successfully"
  fi
done

echo "All databases created successfully!"
