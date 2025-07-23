package handlers

import (
	"cavgotrips/internal/service"
	"cavgotrips/pkg/utils"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"

	"github.com/gorilla/mux"
)

type SSEHandler struct {
	sseService *service.SSEService
}

func NewSSEHandler(sseService *service.SSEService) *SSEHandler {
	return &SSEHandler{sseService: sseService}
}

// HandleSSE handles the SSE connection endpoint
func (h *SSEHandler) HandleSSE(w http.ResponseWriter, r *http.Request) {
	h.sseService.HandleSSE(w, r)
}

// HandleSSEWithBody handles SSE connections with trip IDs in request body
func (h *SSEHandler) HandleSSEWithBody(w http.ResponseWriter, r *http.Request) {
	// Log incoming POST subscription request
	log.Printf("[SSE] 📝 POST subscription request from %s", r.RemoteAddr)

	// Read the request body
	body, err := io.ReadAll(r.Body)
	if err != nil {
		log.Printf("[SSE] ❌ Failed to read request body from %s: %v", r.RemoteAddr, err)
		utils.JSONResponse(w, map[string]string{"error": "Failed to read request body"}, http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	log.Printf("[SSE] 📄 Received request body: %s", string(body))

	// Parse the JSON body
	var requestBody struct {
		TripIDs []int64 `json:"trip_ids"`
	}

	if err := json.Unmarshal(body, &requestBody); err != nil {
		log.Printf("[SSE] ❌ Failed to parse JSON body from %s: %v", r.RemoteAddr, err)
		utils.JSONResponse(w, map[string]string{"error": "Invalid JSON format"}, http.StatusBadRequest)
		return
	}

	log.Printf("[SSE] ✅ Successfully parsed %d trip IDs from POST request", len(requestBody.TripIDs))
	log.Printf("[SSE] 📋 Trip IDs: %v", requestBody.TripIDs)

	// Convert trip IDs to comma-separated string for the existing service
	tripIDsStr := ""
	for i, id := range requestBody.TripIDs {
		if i > 0 {
			tripIDsStr += ","
		}
		tripIDsStr += fmt.Sprintf("%d", id)
	}

	log.Printf("[SSE] 🔄 Converting trip IDs to query parameter: %s", tripIDsStr)

	// Add the trip_ids as a query parameter to the request
	q := r.URL.Query()
	q.Set("trip_ids", tripIDsStr)
	r.URL.RawQuery = q.Encode()

	log.Printf("[SSE] 📡 Forwarding to SSE service with query: %s", r.URL.RawQuery)

	// Call the existing SSE service
	h.sseService.HandleSSE(w, r)
}

// HandleSessionUpdate handles POST requests to update session subscriptions
func (h *SSEHandler) HandleSessionUpdate(w http.ResponseWriter, r *http.Request) {
	// Log incoming session update request
	log.Printf("[SSE] 📝 Session update request from %s", r.RemoteAddr)

	// Get session UUID from URL path
	vars := mux.Vars(r)
	sessionUUID := vars["uuid"]
	if sessionUUID == "" {
		log.Printf("[SSE] ❌ No session UUID in URL path")
		utils.JSONResponse(w, map[string]string{"error": "Session UUID required in URL path"}, http.StatusBadRequest)
		return
	}

	// Read the request body
	body, err := io.ReadAll(r.Body)
	if err != nil {
		log.Printf("[SSE] ❌ Failed to read request body from %s: %v", r.RemoteAddr, err)
		utils.JSONResponse(w, map[string]string{"error": "Failed to read request body"}, http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	log.Printf("[SSE] 📄 Received session update body: %s", string(body))

	// Parse the JSON body
	var requestBody struct {
		Action  string  `json:"action"` // "add" or "remove"
		TripIDs []int64 `json:"trip_ids"`
	}

	if err := json.Unmarshal(body, &requestBody); err != nil {
		log.Printf("[SSE] ❌ Failed to parse JSON body from %s: %v", r.RemoteAddr, err)
		utils.JSONResponse(w, map[string]string{"error": "Invalid JSON format"}, http.StatusBadRequest)
		return
	}

	log.Printf("[SSE] ✅ Successfully parsed session update - Session: %s, Action: %s, Trip IDs: %v",
		sessionUUID, requestBody.Action, requestBody.TripIDs)

	// Validate action
	if requestBody.Action != "add" && requestBody.Action != "remove" {
		log.Printf("[SSE] ❌ Invalid action '%s' from %s", requestBody.Action, r.RemoteAddr)
		utils.JSONResponse(w, map[string]string{"error": "Invalid action. Must be 'add' or 'remove'"}, http.StatusBadRequest)
		return
	}

	// Get current session
	session := h.sseService.SessionService.GetSession(sessionUUID)
	if session == nil {
		log.Printf("[SSE] ⚠️ Session %s not found or expired, creating new session", sessionUUID)
		// Create new session with the provided trip IDs
		newSession := h.sseService.SessionService.CreateSession(requestBody.TripIDs)
		if newSession == nil {
			log.Printf("[SSE] ❌ Failed to create new session")
			utils.JSONResponse(w, map[string]string{"error": "Failed to create session"}, http.StatusInternalServerError)
			return
		}

		log.Printf("[SSE] ✅ Created new session %s with %d trip IDs", newSession.UUID, len(requestBody.TripIDs))
		utils.JSONResponse(w, map[string]interface{}{
			"success":      true,
			"message":      fmt.Sprintf("Created new session with %d trip IDs", len(requestBody.TripIDs)),
			"session_uuid": newSession.UUID,
			"action":       requestBody.Action,
			"trip_ids":     requestBody.TripIDs,
		}, http.StatusOK)
		return
	}

	// Update session based on action
	if requestBody.Action == "add" {
		// Add new trip IDs to session
		success := h.sseService.SessionService.AddTripIDs(sessionUUID, requestBody.TripIDs)
		if success {
			log.Printf("[SSE] ✅ Successfully added %d trip IDs to session %s", len(requestBody.TripIDs), sessionUUID)
			utils.JSONResponse(w, map[string]interface{}{
				"success":      true,
				"message":      fmt.Sprintf("Successfully added %d trip IDs to session", len(requestBody.TripIDs)),
				"session_uuid": sessionUUID,
				"action":       requestBody.Action,
				"trip_ids":     requestBody.TripIDs,
			}, http.StatusOK)
		} else {
			log.Printf("[SSE] ❌ Failed to add trip IDs to session %s", sessionUUID)
			utils.JSONResponse(w, map[string]string{"error": "Failed to update session"}, http.StatusInternalServerError)
		}
	} else if requestBody.Action == "remove" {
		// Remove trip IDs from session
		success := h.sseService.SessionService.RemoveTripIDs(sessionUUID, requestBody.TripIDs)
		if success {
			log.Printf("[SSE] ✅ Successfully removed %d trip IDs from session %s", len(requestBody.TripIDs), sessionUUID)
			utils.JSONResponse(w, map[string]interface{}{
				"success":      true,
				"message":      fmt.Sprintf("Successfully removed %d trip IDs from session", len(requestBody.TripIDs)),
				"session_uuid": sessionUUID,
				"action":       requestBody.Action,
				"trip_ids":     requestBody.TripIDs,
			}, http.StatusOK)
		} else {
			log.Printf("[SSE] ❌ Failed to remove trip IDs from session %s", sessionUUID)
			utils.JSONResponse(w, map[string]string{"error": "Failed to update session"}, http.StatusInternalServerError)
		}
	}
}

// HandleSessionSubscriptionUpdate handles session subscription updates
func (h *SSEHandler) HandleSessionSubscriptionUpdate(w http.ResponseWriter, r *http.Request) {
	// Log incoming subscription update request
	log.Printf("[SSE] 📝 Session subscription update request from %s", r.RemoteAddr)

	// Read the request body
	body, err := io.ReadAll(r.Body)
	if err != nil {
		log.Printf("[SSE] ❌ Failed to read request body from %s: %v", r.RemoteAddr, err)
		utils.JSONResponse(w, map[string]string{"error": "Failed to read request body"}, http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	log.Printf("[SSE] 📄 Received session subscription update body: %s", string(body))

	// Parse the JSON body
	var requestBody struct {
		SessionUUID string  `json:"session_uuid"`
		TripIDs     []int64 `json:"trip_ids"`
	}

	if err := json.Unmarshal(body, &requestBody); err != nil {
		log.Printf("[SSE] ❌ Failed to parse JSON body from %s: %v", r.RemoteAddr, err)
		utils.JSONResponse(w, map[string]string{"error": "Invalid JSON format"}, http.StatusBadRequest)
		return
	}

	log.Printf("[SSE] ✅ Successfully parsed session subscription update - Session: %s, Trip IDs: %v",
		requestBody.SessionUUID, requestBody.TripIDs)

	// Update the session with new trip IDs
	success := h.sseService.SessionService.UpdateSession(requestBody.SessionUUID, requestBody.TripIDs)

	if success {
		log.Printf("[SSE] ✅ Successfully updated session %s with %d trip IDs",
			requestBody.SessionUUID, len(requestBody.TripIDs))
		utils.JSONResponse(w, map[string]interface{}{
			"success":      true,
			"message":      fmt.Sprintf("Successfully updated session with %d trip IDs", len(requestBody.TripIDs)),
			"session_uuid": requestBody.SessionUUID,
			"trip_ids":     requestBody.TripIDs,
		}, http.StatusOK)
	} else {
		log.Printf("[SSE] ❌ Failed to update session %s", requestBody.SessionUUID)
		utils.JSONResponse(w, map[string]string{"error": "Session not found or expired"}, http.StatusNotFound)
	}
}

// GetSSEStatus returns the status of SSE connections
func (h *SSEHandler) GetSSEStatus(w http.ResponseWriter, r *http.Request) {
	log.Printf("[SSE] 📊 Status request from %s", r.RemoteAddr)

	clientCount := h.sseService.GetConnectedClientsCount()
	clientSessions := h.sseService.GetClientSessions()
	activeSessions := h.sseService.SessionService.GetActiveSessionsCount()

	log.Printf("[SSE] 📈 Current SSE status - Connected clients: %d, Active sessions: %d", clientCount, activeSessions)
	if len(clientSessions) > 0 {
		log.Printf("[SSE] 🔍 Active client sessions: %v", clientSessions)
	}

	status := map[string]interface{}{
		"connected_clients": clientCount,
		"active_sessions":   activeSessions,
		"status":            "active",
		"client_sessions":   clientSessions,
	}

	utils.JSONResponse(w, status, http.StatusOK)
	log.Printf("[SSE] ✅ Status response sent to %s", r.RemoteAddr)
}

// GetSessionDebug returns debug information about a specific session
func (h *SSEHandler) GetSessionDebug(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	sessionUUID := vars["uuid"]

	if sessionUUID == "" {
		utils.JSONResponse(w, map[string]string{"error": "Session UUID required"}, http.StatusBadRequest)
		return
	}

	session := h.sseService.SessionService.GetSession(sessionUUID)
	if session == nil {
		utils.JSONResponse(w, map[string]string{"error": "Session not found"}, http.StatusNotFound)
		return
	}

	debug := map[string]interface{}{
		"session_uuid": session.UUID,
		"trip_ids":     session.TripIDs,
		"created_at":   session.CreatedAt,
		"expires_at":   session.ExpiresAt,
		"trip_count":   len(session.TripIDs),
	}

	utils.JSONResponse(w, debug, http.StatusOK)
}
