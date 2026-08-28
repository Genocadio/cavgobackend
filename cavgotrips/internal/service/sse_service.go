package service

import (
	"cavgotrips/internal/models"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/mux"
)

// SSEClient represents a connected SSE client
type SSEClient struct {
	ID       string
	Response http.ResponseWriter
	Flusher  http.Flusher
	Channel  chan []byte
	Close    chan bool
	// Session-based subscription
	SessionUUID string
}

// SSEService manages SSE connections and broadcasts events
type SSEService struct {
	clients        map[string]*SSEClient
	broadcast      chan models.TripEventMessage
	register       chan *SSEClient
	unregister     chan *SSEClient
	mutex          sync.RWMutex
	SessionService *SessionService
}

// NewSSEService creates a new SSE service
func NewSSEService(sessionService *SessionService) *SSEService {
	service := &SSEService{
		clients:        make(map[string]*SSEClient),
		broadcast:      make(chan models.TripEventMessage, 100),
		register:       make(chan *SSEClient),
		unregister:     make(chan *SSEClient),
		SessionService: sessionService,
	}

	// Start the service goroutine
	go service.run()

	return service
}

// run handles the main SSE service loop
func (s *SSEService) run() {
	for {
		select {
		case client := <-s.register:
			s.mutex.Lock()
			s.clients[client.ID] = client
			s.mutex.Unlock()

			// Enhanced logging for client registration
			session := s.SessionService.GetSession(client.SessionUUID)
			sessionInfo := "no session"
			if session != nil {
				sessionInfo = fmt.Sprintf("session: %s, trips: %d", client.SessionUUID, len(session.TripIDs))
			}
			log.Printf("[SSE] ✅ Client %s connected with %s. Total clients: %d",
				client.ID, sessionInfo, len(s.clients))

		case client := <-s.unregister:
			s.mutex.Lock()
			if _, ok := s.clients[client.ID]; ok {
				delete(s.clients, client.ID)
				close(client.Channel)
				close(client.Close)
			}
			s.mutex.Unlock()
			log.Printf("[SSE] ❌ Client %s disconnected. Total clients: %d", client.ID, len(s.clients))

		case event := <-s.broadcast:
			log.Printf("[SSE] 📡 Broadcasting event: %s for trip ID: %d", event.Event, event.Data.ID)
			s.broadcastEvent(event)
		}
	}
}

// shouldSendToClient checks if an event should be sent to a specific client based on session
func (s *SSEService) shouldSendToClient(client *SSEClient, event models.TripEventMessage) bool {
	if client.SessionUUID == "" {
		return false // No session, don't send
	}

	// Get session data
	session := s.SessionService.GetSession(client.SessionUUID)
	if session == nil {
		log.Printf("[SSE] ⏰ Session %s expired or not found", client.SessionUUID)
		return false
	}

	trip := event.Data

	// Check if trip ID is in session's trip IDs
	for _, tripID := range session.TripIDs {
		if trip.ID == tripID {
			return true
		}
	}

	return false
}

// broadcastEvent sends an event to clients based on their filters
func (s *SSEService) broadcastEvent(event models.TripEventMessage) {
	eventJSON, err := json.Marshal(event)
	if err != nil {
		log.Printf("[SSE] ❌ Failed to marshal event: %v", err)
		return
	}

	s.mutex.RLock()
	clients := make([]*SSEClient, 0, len(s.clients))
	for _, client := range s.clients {
		clients = append(clients, client)
	}
	s.mutex.RUnlock()

	// Track delivery statistics
	totalClients := len(clients)
	deliveredTo := 0
	filteredOut := 0
	failedDelivery := 0

	for _, client := range clients {
		// Check if this client should receive this event
		if s.shouldSendToClient(client, event) {
			select {
			case client.Channel <- eventJSON:
				// Event sent successfully
				deliveredTo++
				log.Printf("[SSE] ✅ Event '%s' for trip %d delivered to client %s",
					event.Event, event.Data.ID, client.ID)
			default:
				// Channel is full or client is disconnected
				failedDelivery++
				log.Printf("[SSE] ❌ Failed to send event '%s' for trip %d to client %s (channel full/disconnected)",
					event.Event, event.Data.ID, client.ID)
			}
		} else {
			filteredOut++
			log.Printf("[SSE] 🔍 Event '%s' for trip %d filtered out for client %s (session: %s)",
				event.Event, event.Data.ID, client.ID, client.SessionUUID)
		}
	}

	// Log summary statistics
	log.Printf("[SSE] 📊 Event '%s' for trip %d - Total clients: %d, Delivered: %d, Filtered: %d, Failed: %d",
		event.Event, event.Data.ID, totalClients, deliveredTo, filteredOut, failedDelivery)
}

// HandleSSE handles new SSE connections with optional query parameters for filtering
func (s *SSEService) HandleSSE(w http.ResponseWriter, r *http.Request) {
	// Log incoming connection request
	log.Printf("[SSE] 🔗 New SSE connection request from %s", r.RemoteAddr)

	// Set SSE headers
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	// CORS headers are added by the API gateway (see middleware/cors.go note).

	// Get flusher
	flusher, ok := w.(http.Flusher)
	if !ok {
		log.Printf("[SSE] ❌ Streaming unsupported for client %s", r.RemoteAddr)
		http.Error(w, "Streaming unsupported", http.StatusInternalServerError)
		return
	}

	// Get session UUID from URL path
	sessionUUID := ""
	if vars := mux.Vars(r); vars != nil {
		sessionUUID = vars["uuid"]
	}

	if sessionUUID == "" {
		log.Printf("[SSE] ❌ No session UUID provided")
		http.Error(w, "Session UUID required", http.StatusBadRequest)
		return
	}

	// Validate session exists
	session := s.SessionService.GetSession(sessionUUID)
	if session == nil {
		log.Printf("[SSE] ⚠️ Session %s not found or expired, creating new session", sessionUUID)
		// Create a new session with empty trip IDs for SSE connection
		session = s.SessionService.CreateSession([]int64{})
		if session == nil {
			log.Printf("[SSE] ❌ Failed to create new session")
			http.Error(w, "Failed to create session", http.StatusInternalServerError)
			return
		}
		sessionUUID = session.UUID // Use the new session UUID
		log.Printf("[SSE] ✅ Created new session %s for SSE connection", sessionUUID)
	}

	log.Printf("[SSE] ✅ Session %s validated with %d trip IDs", sessionUUID, len(session.TripIDs))

	// Create client
	clientID := fmt.Sprintf("client_%d", time.Now().UnixNano())
	client := &SSEClient{
		ID:          clientID,
		Response:    w,
		Flusher:     flusher,
		Channel:     make(chan []byte, 10),
		Close:       make(chan bool),
		SessionUUID: sessionUUID,
	}

	// Register client
	log.Printf("[SSE] 📝 Registering client %s with session %s", clientID, sessionUUID)
	s.register <- client

	// Send initial connection message with session info
	initialEvent := fmt.Sprintf("data: %s\n\n", fmt.Sprintf(`{"type":"connected","message":"SSE connection established","client_id":"%s","session_uuid":"%s","trip_count":%d}`, clientID, sessionUUID, len(session.TripIDs)))
	w.Write([]byte(initialEvent))
	flusher.Flush()
	log.Printf("[SSE] ✅ Initial connection message sent to client %s with client_id", clientID)

	// Handle client messages
	go func() {
		messageCount := 0
		for {
			select {
			case message := <-client.Channel:
				// Format SSE message
				sseMessage := fmt.Sprintf("data: %s\n\n", string(message))
				w.Write([]byte(sseMessage))
				flusher.Flush()
				messageCount++
				if messageCount%10 == 0 { // Log every 10th message to avoid spam
					log.Printf("[SSE] 📤 Sent %d messages to client %s", messageCount, clientID)
				}

			case <-client.Close:
				log.Printf("[SSE] 🔒 Client %s closed connection (manual close)", clientID)
				return

			case <-r.Context().Done():
				log.Printf("[SSE] 🔒 Client %s disconnected (context done)", clientID)
				s.unregister <- client
				return
			}
		}
	}()

	// Keep connection alive with periodic heartbeats
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	heartbeatCount := 0

	for {
		select {
		case <-ticker.C:
			heartbeat := fmt.Sprintf("data: %s\n\n", `{"type":"heartbeat","timestamp":"`+time.Now().Format(time.RFC3339)+`"}`)
			w.Write([]byte(heartbeat))
			flusher.Flush()
			heartbeatCount++
			if heartbeatCount%10 == 0 { // Log every 10th heartbeat
				log.Printf("[SSE] 💓 Sent %d heartbeats to client %s", heartbeatCount*30, clientID)
			}

		case <-r.Context().Done():
			log.Printf("[SSE] 🔒 Client %s disconnected (context done during heartbeat)", clientID)
			s.unregister <- client
			return
		}
	}
}

// Helper function to parse comma-separated trip IDs
func parseTripIDs(tripIDsStr string) []int64 {
	var ids []int64
	parts := strings.Split(tripIDsStr, ",")

	log.Printf("[SSE] 🔍 Parsing trip IDs string: '%s' into %d parts", tripIDsStr, len(parts))

	for i, part := range parts {
		part = strings.TrimSpace(part)
		if part != "" {
			if id, err := strconv.ParseInt(part, 10, 64); err == nil {
				ids = append(ids, id)
				log.Printf("[SSE] ✅ Parsed trip ID %d from part %d: '%s'", id, i+1, part)
			} else {
				log.Printf("[SSE] ⚠️  Failed to parse trip ID from part %d: '%s' - error: %v", i+1, part, err)
			}
		} else {
			log.Printf("[SSE] ⚠️  Empty part %d in trip IDs string", i+1)
		}
	}

	log.Printf("[SSE] 📊 Successfully parsed %d valid trip IDs from '%s'", len(ids), tripIDsStr)
	return ids
}

// BroadcastTripEvent broadcasts a trip event to all sessions tracking this trip
func (s *SSEService) BroadcastTripEvent(event models.TripEventMessage) {
	// Log the event being queued for broadcast
	log.Printf("[SSE] 📡 Queuing event '%s' for trip %d to broadcast channel", event.Event, event.Data.ID)

	// Get current client count for logging
	s.mutex.RLock()
	clientCount := len(s.clients)
	s.mutex.RUnlock()

	log.Printf("[SSE] 📊 Broadcasting to %d connected clients", clientCount)

	select {
	case s.broadcast <- event:
		log.Printf("[SSE] ✅ Event '%s' for trip %d successfully queued for broadcast", event.Event, event.Data.ID)
	default:
		log.Printf("[SSE] ❌ Broadcast channel full - event '%s' for trip %d dropped", event.Event, event.Data.ID)
	}
}

// BroadcastTripEventToSessions broadcasts a trip event to all sessions tracking this trip
func (s *SSEService) BroadcastTripEventToSessions(event models.TripEventMessage) {
	tripID := event.Data.ID

	// Get all sessions tracking this trip ID
	sessions := s.SessionService.GetSessionsByTripID(tripID)

	log.Printf("[SSE] 📡 Broadcasting event '%s' for trip %d to %d sessions", event.Event, tripID, len(sessions))

	// Get all connected clients
	s.mutex.RLock()
	clients := make([]*SSEClient, 0, len(s.clients))
	for _, client := range s.clients {
		clients = append(clients, client)
	}
	s.mutex.RUnlock()

	// Track delivery statistics
	totalClients := len(clients)
	deliveredTo := 0
	filteredOut := 0
	failedDelivery := 0

	// Create a set of session UUIDs that track this trip
	sessionUUIDs := make(map[string]bool)
	for _, session := range sessions {
		sessionUUIDs[session.UUID] = true
		log.Printf("[SSE] 📋 Session %s tracks trip %d", session.UUID, tripID)
	}

	eventJSON, err := json.Marshal(event)
	if err != nil {
		log.Printf("[SSE] ❌ Failed to marshal event: %v", err)
		return
	}

	for _, client := range clients {
		// Check if this client's session tracks this trip
		if sessionUUIDs[client.SessionUUID] {
			select {
			case client.Channel <- eventJSON:
				// Event sent successfully
				deliveredTo++
				log.Printf("[SSE] ✅ Event '%s' for trip %d delivered to client %s (session: %s)",
					event.Event, event.Data.ID, client.ID, client.SessionUUID)
			default:
				// Channel is full or client is disconnected
				failedDelivery++
				log.Printf("[SSE] ❌ Failed to send event '%s' for trip %d to client %s (channel full/disconnected)",
					event.Event, event.Data.ID, client.ID)
			}
		} else {
			filteredOut++
			log.Printf("[SSE] 🔍 Event '%s' for trip %d filtered out for client %s (session: %s) - session not tracking this trip",
				event.Event, event.Data.ID, client.ID, client.SessionUUID)
			log.Printf("[SSE] 🔍 Debug: Client session UUID '%s' not found in tracking sessions: %v",
				client.SessionUUID, sessionUUIDs)
		}
	}

	// Log summary statistics
	log.Printf("[SSE] 📊 Event '%s' for trip %d - Total clients: %d, Delivered: %d, Filtered: %d, Failed: %d",
		event.Event, event.Data.ID, totalClients, deliveredTo, filteredOut, failedDelivery)
}

// GetConnectedClientsCount returns the number of connected clients
func (s *SSEService) GetConnectedClientsCount() int {
	s.mutex.RLock()
	defer s.mutex.RUnlock()
	return len(s.clients)
}

// GetClientSessions returns information about client sessions for debugging
func (s *SSEService) GetClientSessions() map[string]interface{} {
	s.mutex.RLock()
	defer s.mutex.RUnlock()

	clientInfo := make(map[string]interface{})
	for clientID, client := range s.clients {
		session := s.SessionService.GetSession(client.SessionUUID)
		if session != nil {
			clientInfo[clientID] = map[string]interface{}{
				"session_uuid": client.SessionUUID,
				"trip_count":   len(session.TripIDs),
				"expires_at":   session.ExpiresAt,
			}
		} else {
			clientInfo[clientID] = map[string]interface{}{
				"session_uuid": client.SessionUUID,
				"status":       "expired",
			}
		}
	}
	return clientInfo
}
