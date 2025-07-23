package handlers

import (
	"cavgotrips/internal/models"
	"cavgotrips/internal/service"
	"cavgotrips/pkg/utils"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"github.com/gorilla/mux"
)

type RouteHandler struct {
	service *service.RouteService
}

func NewRouteHandler(service *service.RouteService) *RouteHandler {
	return &RouteHandler{service: service}
}

func (h *RouteHandler) CreateRoute(w http.ResponseWriter, r *http.Request) {
	var route models.Route
	if err := json.NewDecoder(r.Body).Decode(&route); err != nil {
		utils.ErrorResponse(w, "Invalid request body", http.StatusBadRequest)
		return
	}

	if err := h.service.CreateRoute(&route); err != nil {
		var validationErr *models.ValidationError
		var conflictErr *models.ConflictError

		if errors.As(err, &validationErr) {
			utils.ErrorResponse(w, err.Error(), http.StatusBadRequest)
			return
		} else if errors.As(err, &conflictErr) {
			utils.ErrorResponse(w, err.Error(), http.StatusConflict)
			return
		}

		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	// Get the complete route with relationships
	completeRoute, err := h.service.GetRouteByID(route.ID)
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, completeRoute, http.StatusCreated)
}

func (h *RouteHandler) GetRoute(w http.ResponseWriter, r *http.Request) {
	vars := mux.Vars(r)
	id, err := strconv.ParseInt(vars["id"], 10, 64)
	if err != nil {
		utils.ErrorResponse(w, "Invalid route ID", http.StatusBadRequest)
		return
	}

	route, err := h.service.GetRouteByID(id)
	if err != nil {
		utils.ErrorResponse(w, "Route not found", http.StatusNotFound)
		return
	}

	utils.JSONResponse(w, route, http.StatusOK)
}

func (h *RouteHandler) GetRoutes(w http.ResponseWriter, r *http.Request) {
	routes, err := h.service.GetAllRoutes()
	if err != nil {
		utils.ErrorResponse(w, err.Error(), http.StatusInternalServerError)
		return
	}

	utils.JSONResponse(w, routes, http.StatusOK)
}
