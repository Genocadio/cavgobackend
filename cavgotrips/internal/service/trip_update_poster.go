package service

import (
	"bytes"
	"cavgotrips/internal/models"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"time"
)

type TripUpdatePoster struct {
	baseURL    string
	httpClient *http.Client
}

func NewTripUpdatePoster(baseURL string) *TripUpdatePoster {
	if baseURL == "" {
		return nil
	}
	
	return &TripUpdatePoster{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
	}
}

// PostTripUpdate posts a single trip update immediately
func (p *TripUpdatePoster) PostTripUpdate(companyID int64, trip *models.Trip) error {
	if p == nil || p.baseURL == "" {
		return nil // Feature disabled
	}
	
	url := fmt.Sprintf("%s/%d/trips", p.baseURL, companyID)
	
	// Create request body
	body, err := json.Marshal(trip)
	if err != nil {
		log.Printf("[TripUpdatePoster] Failed to marshal trip %d: %v", trip.ID, err)
		return err
	}
	
	req, err := http.NewRequest("POST", url, bytes.NewBuffer(body))
	if err != nil {
		log.Printf("[TripUpdatePoster] Failed to create request for trip %d: %v", trip.ID, err)
		return err
	}
	
	req.Header.Set("Content-Type", "application/json")
	
	resp, err := p.httpClient.Do(req)
	if err != nil {
		log.Printf("[TripUpdatePoster] Failed to POST trip %d to %s: %v", trip.ID, url, err)
		return err
	}
	defer resp.Body.Close()
	
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		log.Printf("[TripUpdatePoster] Successfully posted trip %d update to %s", trip.ID, url)
	} else {
		log.Printf("[TripUpdatePoster] Received non-2xx status %d when posting trip %d to %s", resp.StatusCode, trip.ID, url)
	}
	
	return nil
}

// PostBatchUpdates posts multiple trip updates in a batch
func (p *TripUpdatePoster) PostBatchUpdates(companyID int64, trips []models.Trip) error {
	if p == nil || p.baseURL == "" {
		return nil // Feature disabled
	}
	
	if len(trips) == 0 {
		return nil
	}
	
	url := fmt.Sprintf("%s/%d/trips", p.baseURL, companyID)
	
	// Create request body with array of trips
	body, err := json.Marshal(trips)
	if err != nil {
		log.Printf("[TripUpdatePoster] Failed to marshal batch trips: %v", err)
		return err
	}
	
	req, err := http.NewRequest("POST", url, bytes.NewBuffer(body))
	if err != nil {
		log.Printf("[TripUpdatePoster] Failed to create batch request: %v", err)
		return err
	}
	
	req.Header.Set("Content-Type", "application/json")
	
	resp, err := p.httpClient.Do(req)
	if err != nil {
		log.Printf("[TripUpdatePoster] Failed to POST batch updates to %s: %v", url, err)
		return err
	}
	defer resp.Body.Close()
	
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		log.Printf("[TripUpdatePoster] Successfully posted %d trip updates to %s", len(trips), url)
	} else {
		log.Printf("[TripUpdatePoster] Received non-2xx status %d when posting batch updates to %s", resp.StatusCode, url)
	}
	
	return nil
}



