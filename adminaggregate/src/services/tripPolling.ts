import * as syncService from "./syncService";

let pollingInterval: NodeJS.Timeout | null = null;

export function startTripPolling(): () => void {
  console.log("[TRIP POLLING] Starting trip polling service (every 30 minutes)...");
  
  // Start polling immediately
  void syncService.syncTrips().catch((error) => {
    console.error("[TRIP POLLING] Error during initial trip sync:", error);
  });
  
  // Set up interval for every 30 minutes
  pollingInterval = setInterval(() => {
    console.log("[TRIP POLLING] Polling for trips...");
    void syncService.syncTrips().catch((error) => {
      console.error("[TRIP POLLING] Error during trip sync:", error);
      // Don't stop polling on error, just log it
    });
  }, 30 * 60 * 1000); // 30 minutes in milliseconds
  
  // Return cleanup function
  return () => {
    if (pollingInterval) {
      console.log("[TRIP POLLING] Stopping trip polling service...");
      clearInterval(pollingInterval);
      pollingInterval = null;
    }
  };
}






