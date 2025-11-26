# Trip Notification Data Structure

This document describes the data structure that clients will receive when subscribed to the `tripsupdates` Firebase Cloud Messaging (FCM) topic.

## Topic
- **Topic Name**: `tripsupdates`
- **Message Type**: Data message (not notification message)
- **Collapse Keys**: Different collapse keys ensure both notification types are received even when device is offline

---

## 1. "About to Complete" Notification

Sent when a trip is `IN_PROGRESS` and remaining distance is less than 1km.

### Collapse Key
```
trip_about_to_complete_{tripId}
```
Example: `trip_about_to_complete_12345`

### Data Payload Structure

```json
{
  "trip_id": "12345",
  "car_id": "67890",
  "plate": "ABC-123",
  "message": "trip Times Square -> Central Park is about to be completed (less than 1km remaining)",
  "type": "about_to_complete"
}
```

### Field Descriptions

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `trip_id` | String | The unique trip identifier | `"12345"` |
| `car_id` | String | The vehicle ID | `"67890"` |
| `plate` | String | The vehicle license plate | `"ABC-123"` |
| `message` | String | Human-readable notification message | `"trip Times Square -> Central Park is about to be completed (less than 1km remaining)"` |
| `type` | String | Notification type identifier | `"about_to_complete"` |

### Message Format
The `message` field follows this pattern:
```
"trip {origin.customName} -> {destination.customName} is about to be completed (less than 1km remaining)"
```

- If `customName` is available, it's used
- Otherwise, `googlePlaceName` is used
- If neither is available, "Unknown" is used

---

## 2. "Completed" Notification

Sent when a trip status changes to `COMPLETED`.

### Collapse Key
```
trip_completed_{tripId}
```
Example: `trip_completed_12345`

### Data Payload Structure

```json
{
  "trip_id": "12345",
  "car_id": "67890",
  "plate": "ABC-123",
  "message": "trip Times Square -> Central Park has been completed",
  "type": "completed"
}
```

### Field Descriptions

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `trip_id` | String | The unique trip identifier | `"12345"` |
| `car_id` | String | The vehicle ID | `"67890"` |
| `plate` | String | The vehicle license plate | `"ABC-123"` |
| `message` | String | Human-readable notification message | `"trip Times Square -> Central Park has been completed"` |
| `type` | String | Notification type identifier | `"completed"` |

### Message Format
The `message` field follows this pattern:
```
"trip {origin.customName} -> {destination.customName} has been completed"
```

- If `customName` is available, it's used
- Otherwise, `googlePlaceName` is used
- If neither is available, "Unknown" is used

---

## Client-Side Implementation Examples

### Android (Kotlin/Java)

```kotlin
FirebaseMessaging.getInstance().subscribeToTopic("tripsupdates")

// In your FirebaseMessagingService
override fun onMessageReceived(remoteMessage: RemoteMessage) {
    val data = remoteMessage.data
    
    when (data["type"]) {
        "about_to_complete" -> {
            val tripId = data["trip_id"]
            val carId = data["car_id"]
            val plate = data["plate"]
            val message = data["message"]
            
            // Handle "about to complete" notification
            showNotification(message, tripId, carId, plate)
        }
        "completed" -> {
            val tripId = data["trip_id"]
            val carId = data["car_id"]
            val plate = data["plate"]
            val message = data["message"]
            
            // Handle completion notification
            showNotification(message, tripId, carId, plate)
        }
    }
}
```

### iOS (Swift)

```swift
Messaging.messaging().subscribe(toTopic: "tripsupdates")

// In your AppDelegate or NotificationService extension
func userNotificationCenter(_ center: UNUserNotificationCenter,
                          willPresent notification: UNNotification,
                          withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
    
    let userInfo = notification.request.content.userInfo
    
    if let type = userInfo["type"] as? String {
        let tripId = userInfo["trip_id"] as? String
        let carId = userInfo["car_id"] as? String
        let plate = userInfo["plate"] as? String
        let message = userInfo["message"] as? String
        
        switch type {
        case "about_to_complete":
            // Handle "about to complete" notification
            handleAboutToComplete(tripId: tripId, carId: carId, plate: plate, message: message)
        case "completed":
            // Handle completion notification
            handleCompleted(tripId: tripId, carId: carId, plate: plate, message: message)
        default:
            break
        }
    }
    
    completionHandler([.alert, .sound, .badge])
}
```

### Web (JavaScript)

```javascript
// Subscribe to topic
messaging.getToken().then((currentToken) => {
    if (currentToken) {
        // Subscribe to tripsupdates topic via your backend
        // (FCM web doesn't support direct topic subscription)
    }
});

// Handle incoming messages
messaging.onMessage((payload) => {
    const data = payload.data;
    
    if (data.type === 'about_to_complete') {
        const { trip_id, car_id, plate, message } = data;
        // Handle "about to complete" notification
        showNotification(message, { trip_id, car_id, plate });
    } else if (data.type === 'completed') {
        const { trip_id, car_id, plate, message } = data;
        // Handle completion notification
        showNotification(message, { trip_id, car_id, plate });
    }
});
```

---

## Important Notes

1. **Data Messages**: These are data messages, not notification messages. The client app must handle displaying notifications.

2. **Collapse Keys**: Different collapse keys (`trip_about_to_complete_{tripId}` and `trip_completed_{tripId}`) ensure both notifications are delivered even when the device is offline.

3. **Topic Subscription**: Clients must subscribe to the `tripsupdates` topic to receive these notifications.

4. **Message Order**: The "about to complete" notification is sent first (when distance < 1km), and the completion notification is sent when the trip status changes to `COMPLETED`.

5. **Duplicate Prevention**: The "about to complete" notification is only sent once per trip (tracked in database). If the client receives multiple trip updates with distance < 1km, only the first one will trigger a notification.

6. **All Fields are Strings**: All values in the data payload are strings, so clients should parse `trip_id` and `car_id` as integers if needed.

---

## Example Full FCM Message Structure

### About to Complete
```json
{
  "from": "123456789",
  "messageId": "0:1234567890",
  "data": {
    "trip_id": "12345",
    "car_id": "67890",
    "plate": "ABC-123",
    "message": "trip Times Square -> Central Park is about to be completed (less than 1km remaining)",
    "type": "about_to_complete"
  },
  "collapseKey": "trip_about_to_complete_12345"
}
```

### Completed
```json
{
  "from": "123456789",
  "messageId": "0:1234567890",
  "data": {
    "trip_id": "12345",
    "car_id": "67890",
    "plate": "ABC-123",
    "message": "trip Times Square -> Central Park has been completed",
    "type": "completed"
  },
  "collapseKey": "trip_completed_12345"
}
```



