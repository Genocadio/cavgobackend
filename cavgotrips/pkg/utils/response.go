package utils

import (
	"encoding/json"
	"net/http"
)

type ErrorMessage struct {
	Error string `json:"error"`
}

type PaginatedResponse struct {
	Data       interface{} `json:"data"`
	Pagination Pagination  `json:"pagination"`
}

type Pagination struct {
	Page       int   `json:"page"`
	Limit      int   `json:"limit"`
	Total      int64 `json:"total"`
	TotalPages int   `json:"total_pages"`
	HasNext    bool  `json:"has_next"`
	HasPrev    bool  `json:"has_prev"`
}

func JSONResponse(w http.ResponseWriter, data interface{}, statusCode int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	json.NewEncoder(w).Encode(data)
}

func ErrorResponse(w http.ResponseWriter, message string, statusCode int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	json.NewEncoder(w).Encode(ErrorMessage{Error: message})
}

func PaginatedJSONResponse(w http.ResponseWriter, data interface{}, total int64, page, limit int, statusCode int) {
	// Calculate pagination metadata
	totalPages := int((total + int64(limit) - 1) / int64(limit))
	hasNext := page < totalPages
	hasPrev := page > 1

	pagination := Pagination{
		Page:       page,
		Limit:      limit,
		Total:      total,
		TotalPages: totalPages,
		HasNext:    hasNext,
		HasPrev:    hasPrev,
	}

	response := PaginatedResponse{
		Data:       data,
		Pagination: pagination,
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	json.NewEncoder(w).Encode(response)
}
