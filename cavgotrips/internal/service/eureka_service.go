package service

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"cavgotrips/internal/config"

	"github.com/google/uuid"
)

type EurekaService struct {
	config     *config.Config
	httpClient *http.Client
	instanceID string
	registerMu sync.Mutex

	heartbeatMu      sync.Mutex
	heartbeatTicker  *time.Ticker
	heartbeatStopCh  chan struct{}
	heartbeatRunning bool

	verifyMu      sync.Mutex
	verifyTicker  *time.Ticker
	verifyStopCh  chan struct{}
	verifyRunning bool
}

type EurekaStatusError struct {
	Operation  string
	StatusCode int
	URL        string
}

func (e *EurekaStatusError) Error() string {
	return fmt.Sprintf("Eureka %s failed with status: %d, URL: %s", e.Operation, e.StatusCode, e.URL)
}

type EurekaInstance struct {
	InstanceID       string `json:"instanceId"`
	HostName         string `json:"hostName"`
	App              string `json:"app"`
	IPAddr           string `json:"ipAddr"`
	Status           string `json:"status"`
	OverriddenStatus string `json:"overriddenstatus"`
	Port             struct {
		Number  int  `json:"$"`
		Enabled bool `json:"@enabled"`
	} `json:"port"`
	SecurePort struct {
		Number  int  `json:"$"`
		Enabled bool `json:"@enabled"`
	} `json:"securePort"`
	CountryID      int `json:"countryId"`
	DataCenterInfo struct {
		Class string `json:"@class"`
		Name  string `json:"name"`
	} `json:"dataCenterInfo"`
	LeaseInfo struct {
		RenewalIntervalInSecs int `json:"renewalIntervalInSecs"`
		DurationInSecs        int `json:"durationInSecs"`
	} `json:"leaseInfo"`
	Metadata struct {
		ManagementPort string `json:"management.port"`
	} `json:"metadata"`
	HomePageURL                   string `json:"homePageUrl"`
	StatusPageURL                 string `json:"statusPageUrl"`
	HealthCheckURL                string `json:"healthCheckUrl"`
	VIPAddress                    string `json:"vipAddress"`
	SecureVIPAddress              string `json:"secureVipAddress"`
	IsCoordinatingDiscoveryServer bool   `json:"isCoordinatingDiscoveryServer"`
	LastUpdatedTimestamp          string `json:"lastUpdatedTimestamp"`
	LastDirtyTimestamp            string `json:"lastDirtyTimestamp"`
	ActionType                    string `json:"actionType"`
}

type EurekaRegistration struct {
	Instance EurekaInstance `json:"instance"`
}

func NewEurekaService(cfg *config.Config) *EurekaService {
	// Generate instance ID if not provided
	instanceID := cfg.Eureka.InstanceID
	if instanceID == "" {
		instanceID = fmt.Sprintf("%s:%s", cfg.Eureka.AppName, uuid.New().String())
	}

	return &EurekaService{
		config: cfg,
		httpClient: &http.Client{
			Timeout: 10 * time.Second,
		},
		instanceID: instanceID,
	}
}

func (e *EurekaService) Register() error {
	if !e.config.Eureka.RegisterWithEureka {
		return nil
	}

	e.registerMu.Lock()
	defer e.registerMu.Unlock()

	instance := e.buildInstance()
	registration := EurekaRegistration{Instance: instance}

	jsonData, err := json.Marshal(registration)
	if err != nil {
		return fmt.Errorf("failed to marshal registration data: %w", err)
	}

	// Ensure the URL doesn't have duplicate /eureka paths
	baseURL := e.config.Eureka.ServerURL
	if strings.HasSuffix(baseURL, "/eureka") {
		baseURL = strings.TrimSuffix(baseURL, "/eureka")
	}
	url := fmt.Sprintf("%s/eureka/apps/%s", baseURL, e.config.Eureka.AppName)

	// Debug logging
	fmt.Printf("Attempting to register with Eureka at URL: %s\n", url)
	fmt.Printf("Instance details: App=%s, InstanceID=%s, IP=%s, Port=%s\n",
		instance.App, instance.InstanceID, instance.IPAddr, e.config.Port)

	req, err := http.NewRequest("POST", url, bytes.NewBuffer(jsonData))
	if err != nil {
		return fmt.Errorf("failed to create registration request: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")

	resp, err := e.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to register with Eureka: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		// Read response body for better error information
		if resp.Body != nil {
			respBody, _ := io.ReadAll(resp.Body)
			fmt.Printf("Eureka response body: %s\n", string(respBody))
		}
		return fmt.Errorf("Eureka registration failed with status: %d, URL: %s", resp.StatusCode, url)
	}

	fmt.Printf("Successfully registered with Eureka. Instance ID: %s\n", e.instanceID)
	return nil
}

func (e *EurekaService) EnsureRegistered() {
	if !e.config.Eureka.RegisterWithEureka {
		return
	}

	for {
		err := e.Register()
		if err == nil {
			log.Printf("[Eureka] registration ensured for instance %s", e.instanceID)
			return
		}

		log.Printf("[Eureka] registration failed, retrying in 5s: %v", err)
		time.Sleep(5 * time.Second)
	}
}

func (e *EurekaService) Deregister() error {
	if !e.config.Eureka.RegisterWithEureka {
		return nil
	}

	// Ensure the URL doesn't have duplicate /eureka paths
	baseURL := e.config.Eureka.ServerURL
	if strings.HasSuffix(baseURL, "/eureka") {
		baseURL = strings.TrimSuffix(baseURL, "/eureka")
	}
	url := fmt.Sprintf("%s/eureka/apps/%s/%s", baseURL, e.config.Eureka.AppName, e.instanceID)
	req, err := http.NewRequest("DELETE", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create deregistration request: %w", err)
	}

	resp, err := e.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to deregister from Eureka: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		// Read response body for better error information
		if resp.Body != nil {
			respBody, _ := io.ReadAll(resp.Body)
			fmt.Printf("Eureka deregistration response body: %s\n", string(respBody))
		}
		return fmt.Errorf("Eureka deregistration failed with status: %d, URL: %s", resp.StatusCode, url)
	}

	fmt.Printf("Successfully deregistered from Eureka. Instance ID: %s\n", e.instanceID)
	return nil
}

func (e *EurekaService) SendHeartbeat() error {
	if !e.config.Eureka.RegisterWithEureka {
		return nil
	}

	// Ensure the URL doesn't have duplicate /eureka paths
	baseURL := e.config.Eureka.ServerURL
	if strings.HasSuffix(baseURL, "/eureka") {
		baseURL = strings.TrimSuffix(baseURL, "/eureka")
	}
	url := fmt.Sprintf("%s/eureka/apps/%s/%s", baseURL, e.config.Eureka.AppName, e.instanceID)
	req, err := http.NewRequest("PUT", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create heartbeat request: %w", err)
	}

	resp, err := e.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to send heartbeat to Eureka: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		// Read response body for better error information
		if resp.Body != nil {
			respBody, _ := io.ReadAll(resp.Body)
			fmt.Printf("Eureka heartbeat response body: %s\n", string(respBody))
		}
		return &EurekaStatusError{Operation: "heartbeat", StatusCode: resp.StatusCode, URL: url}
	}

	return nil
}

func (e *EurekaService) StartHeartbeat() {
	if !e.config.Eureka.RegisterWithEureka {
		return
	}

	e.heartbeatMu.Lock()
	if e.heartbeatRunning {
		e.heartbeatMu.Unlock()
		return
	}

	ticker := time.NewTicker(30 * time.Second) // Send heartbeat every 30 seconds
	stopCh := make(chan struct{})
	e.heartbeatTicker = ticker
	e.heartbeatStopCh = stopCh
	e.heartbeatRunning = true
	e.heartbeatMu.Unlock()

	go func() {
		for {
			select {
			case <-ticker.C:
				err := e.SendHeartbeat()
				if err == nil {
					continue
				}

				log.Printf("[Eureka] heartbeat failed, attempting re-register: %v", err)
				if regErr := e.Register(); regErr != nil {
					log.Printf("[Eureka] re-registration attempt failed: %v", regErr)
					continue
				}

				log.Printf("[Eureka] instance re-registered successfully after heartbeat failure")
			case <-stopCh:
				return
			}
		}
	}()
}

func (e *EurekaService) VerifyRegistration() {
	if !e.config.Eureka.RegisterWithEureka {
		return
	}

	url := e.instanceEndpointURL()
	req, err := http.NewRequest("GET", url, nil)
	if err != nil {
		log.Printf("[Eureka] failed to create verify request: %v", err)
		return
	}

	resp, err := e.httpClient.Do(req)
	if err != nil {
		log.Printf("[Eureka] verification request failed, attempting re-register: %v", err)
		if regErr := e.Register(); regErr != nil {
			log.Printf("[Eureka] re-registration after verify failure failed: %v", regErr)
		}
		return
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusOK {
		return
	}

	if resp.Body != nil {
		respBody, _ := io.ReadAll(resp.Body)
		log.Printf("[Eureka] verify response status=%d body=%s", resp.StatusCode, string(respBody))
	} else {
		log.Printf("[Eureka] verify response status=%d", resp.StatusCode)
	}

	if regErr := e.Register(); regErr != nil {
		log.Printf("[Eureka] re-registration after verification mismatch failed: %v", regErr)
		return
	}

	log.Printf("[Eureka] instance re-registered successfully after verification mismatch")
}

func (e *EurekaService) StartRegistrationVerifier(interval time.Duration) {
	if !e.config.Eureka.RegisterWithEureka {
		return
	}
	if interval <= 0 {
		interval = 90 * time.Second
	}

	e.verifyMu.Lock()
	if e.verifyRunning {
		e.verifyMu.Unlock()
		return
	}

	ticker := time.NewTicker(interval)
	stopCh := make(chan struct{})
	e.verifyTicker = ticker
	e.verifyStopCh = stopCh
	e.verifyRunning = true
	e.verifyMu.Unlock()

	go func() {
		for {
			select {
			case <-ticker.C:
				e.VerifyRegistration()
			case <-stopCh:
				return
			}
		}
	}()
}

func (e *EurekaService) StopRegistrationVerifier() {
	e.verifyMu.Lock()
	defer e.verifyMu.Unlock()

	if !e.verifyRunning {
		return
	}

	e.verifyTicker.Stop()
	close(e.verifyStopCh)
	e.verifyTicker = nil
	e.verifyStopCh = nil
	e.verifyRunning = false
}

func (e *EurekaService) StopHeartbeat() {
	e.heartbeatMu.Lock()
	defer e.heartbeatMu.Unlock()

	if !e.heartbeatRunning {
		return
	}

	e.heartbeatTicker.Stop()
	close(e.heartbeatStopCh)
	e.heartbeatTicker = nil
	e.heartbeatStopCh = nil
	e.heartbeatRunning = false
}

func (e *EurekaService) buildInstance() EurekaInstance {
	hostname, _ := os.Hostname()
	ipAddr := e.getIPAddress()

	instance := EurekaInstance{
		InstanceID:       e.instanceID,
		HostName:         hostname,
		App:              e.config.Eureka.AppName,
		IPAddr:           ipAddr,
		Status:           "UP",
		OverriddenStatus: "UNKNOWN",
		Port: struct {
			Number  int  `json:"$"`
			Enabled bool `json:"@enabled"`
		}{
			Number:  getPortFromString(e.config.Port),
			Enabled: true,
		},
		SecurePort: struct {
			Number  int  `json:"$"`
			Enabled bool `json:"@enabled"`
		}{
			Number:  443,
			Enabled: false,
		},
		CountryID: 1,
		DataCenterInfo: struct {
			Class string `json:"@class"`
			Name  string `json:"name"`
		}{
			Class: "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
			Name:  "MyOwn",
		},
		LeaseInfo: struct {
			RenewalIntervalInSecs int `json:"renewalIntervalInSecs"`
			DurationInSecs        int `json:"durationInSecs"`
		}{
			RenewalIntervalInSecs: 30,
			DurationInSecs:        90,
		},
		Metadata: struct {
			ManagementPort string `json:"management.port"`
		}{
			ManagementPort: e.config.Port,
		},
		HomePageURL:                   fmt.Sprintf("http://%s:%s/", ipAddr, e.config.Port),
		StatusPageURL:                 fmt.Sprintf("http://%s:%s/health", ipAddr, e.config.Port),
		HealthCheckURL:                fmt.Sprintf("http://%s:%s/health", ipAddr, e.config.Port),
		VIPAddress:                    e.config.Eureka.AppName,
		SecureVIPAddress:              e.config.Eureka.AppName,
		IsCoordinatingDiscoveryServer: false,
		LastUpdatedTimestamp:          time.Now().Format("2006-01-02T15:04:05.000-07:00"),
		LastDirtyTimestamp:            time.Now().Format("2006-01-02T15:04:05.000-07:00"),
		ActionType:                    "ADDED",
	}

	return instance
}

func (e *EurekaService) getIPAddress() string {
	if e.config.Eureka.PreferIPAddress {
		// Try to get the container's IP address
		addrs, err := net.InterfaceAddrs()
		if err != nil {
			return "localhost"
		}

		for _, addr := range addrs {
			if ipnet, ok := addr.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
				if ipnet.IP.To4() != nil {
					return ipnet.IP.String()
				}
			}
		}
	}

	hostname, _ := os.Hostname()
	return hostname
}

func getPortFromString(portStr string) int {
	var port int
	fmt.Sscanf(portStr, "%d", &port)
	if port == 0 {
		port = 8080
	}
	return port
}

func (e *EurekaService) normalizedBaseURL() string {
	baseURL := e.config.Eureka.ServerURL
	if strings.HasSuffix(baseURL, "/eureka") {
		baseURL = strings.TrimSuffix(baseURL, "/eureka")
	}
	return baseURL
}

func (e *EurekaService) instanceEndpointURL() string {
	return fmt.Sprintf("%s/eureka/apps/%s/%s", e.normalizedBaseURL(), e.config.Eureka.AppName, e.instanceID)
}
