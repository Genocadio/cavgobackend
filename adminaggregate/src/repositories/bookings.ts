import { eq } from "drizzle-orm";
import type { InferModel } from "drizzle-orm";
import { db } from "../db/client";
import { bookings } from "../db/schema";
import type { Booking } from "../types";

type BookingRow = InferModel<typeof bookings>;

const mapBooking = (row: BookingRow): Booking => ({
  id: row.id,
  tripId: row.tripId,
  passengerName: row.passengerName,
  passengerPhone: row.passengerPhone,
  pickupLocationId: row.pickupLocationId,
  dropoffLocationId: row.dropoffLocationId,
  numberOftickets: row.numberOfTickets,
  totalFare: Number(row.totalFare),
  paymentType: row.paymentType ?? null,
  status: row.status,
  createdAt: row.createdAt ? row.createdAt.getTime() : 0,
  updatedAt: row.updatedAt ? row.updatedAt.getTime() : 0,
});

export async function createBooking(booking: Booking): Promise<Booking> {
  await db.insert(bookings).values({
    id: booking.id,
    tripId: booking.tripId,
    passengerName: booking.passengerName,
    passengerPhone: booking.passengerPhone,
    pickupLocationId: booking.pickupLocationId,
    dropoffLocationId: booking.dropoffLocationId,
    numberOfTickets: booking.numberOftickets,
    totalFare: booking.totalFare.toString(),
    paymentType: booking.paymentType,
    status: booking.status,
    createdAt: new Date(booking.createdAt),
    updatedAt: new Date(booking.updatedAt),
  });

  return booking;
}

export async function updateBooking(booking: Booking): Promise<Booking> {
  await db
    .update(bookings)
    .set({
      passengerName: booking.passengerName,
      passengerPhone: booking.passengerPhone,
      pickupLocationId: booking.pickupLocationId,
      dropoffLocationId: booking.dropoffLocationId,
      numberOfTickets: booking.numberOftickets,
      totalFare: booking.totalFare.toString(),
      paymentType: booking.paymentType,
      status: booking.status,
      updatedAt: new Date(booking.updatedAt),
    })
    .where(eq(bookings.id, booking.id));

  return booking;
}

export async function deleteBooking(id: string): Promise<void> {
  await db.delete(bookings).where(eq(bookings.id, id));
}

export async function getBookingById(id: string): Promise<Booking | null> {
  const [booking] = await db.select().from(bookings).where(eq(bookings.id, id));
  return booking ? mapBooking(booking) : null;
}

