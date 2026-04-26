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

	"github.com/google/uuid"
)

type EurekaService struct {
	config     *EurekaConfig
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

type EurekaConfig struct {
	ServerURL string
	AppName   string
	Port      string
	PreferIP  bool
	Register  bool
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

func NewEurekaService() *EurekaService {
	cfg := loadEurekaConfig()
	instanceID := fmt.Sprintf("%s:%s", cfg.AppName, uuid.NewString())
	return &EurekaService{
		config:     cfg,
		httpClient: &http.Client{Timeout: 10 * time.Second},
		instanceID: instanceID,
	}
}

func (e *EurekaService) Register() error {
	if !e.config.Register {
		return nil
	}

	e.registerMu.Lock()
	defer e.registerMu.Unlock()

	log.Printf("[Eureka] starting registration: server=%s app=%s port=%s",
		e.config.ServerURL, e.config.AppName, e.config.Port)

	instance := e.buildInstance()
	registration := EurekaRegistration{Instance: instance}
	jsonData, err := json.Marshal(registration)
	if err != nil {
		return fmt.Errorf("failed to marshal registration data: %w", err)
	}
	url := fmt.Sprintf("%s/eureka/apps/%s", e.normalizedBaseURL(), e.config.AppName)
	log.Printf("[Eureka] register URL=%s instanceID=%s ip=%s", url, instance.InstanceID, instance.IPAddr)
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
		if resp.Body != nil {
			respBody, _ := io.ReadAll(resp.Body)
			log.Printf("[Eureka] register response status=%d body=%s", resp.StatusCode, string(respBody))
		}
		return &EurekaStatusError{Operation: "registration", StatusCode: resp.StatusCode, URL: url}
	}

	log.Printf("[Eureka] registered successfully: instanceID=%s", e.instanceID)
	return nil
}

func (e *EurekaService) EnsureRegistered() {
	if !e.config.Register {
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
	if !e.config.Register {
		return nil
	}
	url := e.instanceEndpointURL()
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
		if resp.Body != nil {
			respBody, _ := io.ReadAll(resp.Body)
			log.Printf("[Eureka] deregister response status=%d body=%s", resp.StatusCode, string(respBody))
		}
		return &EurekaStatusError{Operation: "deregistration", StatusCode: resp.StatusCode, URL: url}
	}
	log.Printf("[Eureka] deregistered successfully: instanceID=%s", e.instanceID)
	return nil
}

func (e *EurekaService) SendHeartbeat() error {
	if !e.config.Register {
		return nil
	}
	url := e.instanceEndpointURL()
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
		if resp.Body != nil {
			respBody, _ := io.ReadAll(resp.Body)
			log.Printf("[Eureka] heartbeat response status=%d body=%s", resp.StatusCode, string(respBody))
		}
		return &EurekaStatusError{Operation: "heartbeat", StatusCode: resp.StatusCode, URL: url}
	}
	return nil
}

func (e *EurekaService) StartHeartbeat() {
	if !e.config.Register {
		return
	}

	e.heartbeatMu.Lock()
	if e.heartbeatRunning {
		e.heartbeatMu.Unlock()
		return
	}

	ticker := time.NewTicker(30 * time.Second)
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
	if !e.config.Register {
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
	if !e.config.Register {
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
	portNum := getPortFromString(e.config.Port)
	instance := EurekaInstance{
		InstanceID:       e.instanceID,
		HostName:         hostname,
		App:              e.config.AppName,
		IPAddr:           ipAddr,
		Status:           "UP",
		OverriddenStatus: "UNKNOWN",
		Port: struct {
			Number  int  `json:"$"`
			Enabled bool `json:"@enabled"`
		}{
			Number:  portNum,
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
		VIPAddress:                    e.config.AppName,
		SecureVIPAddress:              e.config.AppName,
		IsCoordinatingDiscoveryServer: false,
		LastUpdatedTimestamp:          time.Now().Format("2006-01-02T15:04:05.000-07:00"),
		LastDirtyTimestamp:            time.Now().Format("2006-01-02T15:04:05.000-07:00"),
		ActionType:                    "ADDED",
	}
	return instance
}

func (e *EurekaService) getIPAddress() string {
	// Check if we're in Docker environment
	if os.Getenv("ENVIRONMENT") == "docker" {
		hostname, _ := os.Hostname()
		fmt.Printf("Running in Docker, using hostname: %s\n", hostname)
		return hostname
	}

	if e.config.PreferIP {
		addrs, err := net.InterfaceAddrs()
		if err != nil {
			fmt.Printf("Warning: Failed to get interface addresses: %v\n", err)
			return "localhost"
		}
		for _, addr := range addrs {
			if ipnet, ok := addr.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
				if ipnet.IP.To4() != nil {
					ip := ipnet.IP.String()
					fmt.Printf("Found IP address: %s\n", ip)
					return ip
				}
			}
		}
		fmt.Printf("No suitable IP address found, using hostname\n")
	}
	hostname, _ := os.Hostname()
	fmt.Printf("Using hostname as IP: %s\n", hostname)
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
	baseURL := e.config.ServerURL
	if strings.HasSuffix(baseURL, "/eureka") {
		baseURL = strings.TrimSuffix(baseURL, "/eureka")
	}
	return baseURL
}

func (e *EurekaService) instanceEndpointURL() string {
	return fmt.Sprintf("%s/eureka/apps/%s/%s", e.normalizedBaseURL(), e.config.AppName, e.instanceID)
}

func loadEurekaConfig() *EurekaConfig {
	return &EurekaConfig{
		ServerURL: getEnv("EUREKA_SERVER_URL", "http://localhost:8761"),
		AppName:   strings.ToLower(getEnv("EUREKA_APP_NAME", "CAVGOBOOKING")),
		Port:      getEnv("PORT", "8030"),
		PreferIP:  getEnv("EUREKA_PREFER_IP", "true") == "true",
		Register:  getEnv("EUREKA_REGISTER", "true") == "true",
	}
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}
