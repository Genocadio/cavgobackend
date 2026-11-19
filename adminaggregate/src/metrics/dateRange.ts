/**
 * Shared date-range resolution for date-scoped metrics.
 *
 * Both car and driver metrics accept optional `YYYY-MM-DD` bounds with the same
 * semantics. Keeping it in one place prevents the two endpoints from drifting.
 */

export interface DateRange {
  start: Date;
  end: Date;
}

const YEAR_2000_MS = 946_684_800_000;

/** Parse a calendar date as local midnight (not UTC, which shifts the day). */
function parseLocalDate(dateString: string): Date {
  const [year, month, day] = dateString.split("-").map(Number);
  return new Date(year || 1970, (month || 1) - 1, day || 1);
}

/** Timestamps from some clients arrive in seconds rather than milliseconds. */
export function toMilliseconds(timestamp: number): number {
  return timestamp < YEAR_2000_MS ? timestamp * 1000 : timestamp;
}

/**
 * Resolve optional date strings to an inclusive window.
 * - no bounds: today
 * - start only: start of that day through now
 * - both: start of the first day through end of the last day
 * - end only: beginning of time through end of that day
 */
export function resolveDateRange(startDate?: string, endDate?: string): DateRange {
  if (!startDate && !endDate) {
    const start = new Date();
    start.setHours(0, 0, 0, 0);
    const end = new Date();
    end.setHours(23, 59, 59, 999);
    return { start, end };
  }

  if (startDate && !endDate) {
    return { start: parseLocalDate(startDate), end: new Date() };
  }

  if (startDate && endDate) {
    const start = parseLocalDate(startDate);
    const end = parseLocalDate(endDate);
    end.setHours(23, 59, 59, 999);
    return { start, end };
  }

  const end = parseLocalDate(endDate!);
  end.setHours(23, 59, 59, 999);
  return { start: new Date(0), end };
}

/** Resolve numeric timestamp bounds (seconds or milliseconds) used by company metrics. */
export function resolveTimestampRange(startTime?: number, endTime?: number): DateRange {
  if (startTime && endTime) {
    return {
      start: new Date(toMilliseconds(startTime)),
      end: new Date(toMilliseconds(endTime)),
    };
  }

  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const end = new Date();
  end.setHours(23, 59, 59, 999);
  return { start, end };
}
