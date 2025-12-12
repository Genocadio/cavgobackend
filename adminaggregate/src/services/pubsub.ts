import { EventEmitter } from "events";

/**
 * Simple pub/sub implementation for GraphQL subscriptions
 * Uses EventEmitter for in-memory pub/sub
 */
class PubSub extends EventEmitter {
  publish<T>(trigger: string, payload: T): void {
    this.emit(trigger, payload);
  }

  asyncIterator<T>(triggers: string | string[]): AsyncIterable<T> {
    const triggerArray = Array.isArray(triggers) ? triggers : [triggers];
    return this.asyncIteratorImpl<T>(triggerArray);
  }

  private asyncIteratorImpl<T>(triggers: string[]): AsyncIterable<T> {
    const eventQueue: T[] = [];
    const promiseQueue: Array<{
      resolve: (value: IteratorResult<T>) => void;
      reject: (error: Error) => void;
    }> = [];

    const pushValue = (value: T) => {
      if (promiseQueue.length > 0) {
        promiseQueue.shift()?.resolve({ value, done: false });
      } else {
        eventQueue.push(value);
      }
    };

    const pullValue = (): Promise<IteratorResult<T>> => {
      return new Promise((resolve, reject) => {
        if (eventQueue.length > 0) {
          resolve({ value: eventQueue.shift()!, done: false });
        } else {
          promiseQueue.push({ resolve, reject });
        }
      });
    };

    const cleanup = () => {
      for (const trigger of triggers) {
        this.removeListener(trigger, pushValue);
      }
      for (const { reject } of promiseQueue) {
        reject(new Error("Subscription closed"));
      }
    };

    for (const trigger of triggers) {
      this.on(trigger, pushValue);
    }

    const iterator: AsyncIterator<T> = {
      async next(): Promise<IteratorResult<T>> {
        return pullValue();
      },
      async return(): Promise<IteratorResult<T>> {
        cleanup();
        return { value: undefined as any, done: true };
      },
      async throw(error: Error): Promise<IteratorResult<T>> {
        cleanup();
        return Promise.reject(error);
      },
    };

    return {
      [Symbol.asyncIterator]() {
        return iterator;
      },
    };
  }
}

export const pubsub = new PubSub();

/**
 * Subscription triggers
 */
export const TRIGGERS = {
  COMPANY_TRIPS_UPDATED: (companyId: string) => `companyTrips:${companyId}`,
} as const;

