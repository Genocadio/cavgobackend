import { config } from "dotenv";
import { Pool } from "pg";
import { drizzle } from "drizzle-orm/node-postgres";

config();

const { DATABASE_URL } = process.env;
if (!DATABASE_URL) {
  throw new Error("DATABASE_URL must be set in the environment before initializing the database client");
}

export const pgPool = new Pool({ connectionString: DATABASE_URL });
export const db = drizzle(pgPool);





