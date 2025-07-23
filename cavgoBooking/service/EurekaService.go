package service

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"
)

type EurekaService struct {
	config     *EurekaConfig
	httpClient *http.Client
	instanceID string
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
		fmt.Printf("Eureka registration disabled\n")
		return nil
	}

	fmt.Printf("Starting Eureka registration...\n")
	fmt.Printf("Eureka config - ServerURL: %s, AppName: %s, Port: %s\n",
		e.config.ServerURL, e.config.AppName, e.config.Port)

	instance := e.buildInstance()
	registration := EurekaRegistration{Instance: instance}
	jsonData, err := json.Marshal(registration)
	if err != nil {
		return fmt.Errorf("failed to marshal registration data: %w", err)
	}
	baseURL := e.config.ServerURL
	if strings.HasSuffix(baseURL, "/eureka") {
		baseURL = strings.TrimSuffix(baseURL, "/eureka")
	}
	url := fmt.Sprintf("%s/eureka/apps/%s", baseURL, e.config.AppName)
	fmt.Printf("Attempting to register with Eureka at URL: %s\n", url)
	fmt.Printf("Instance details: App=%s, InstanceID=%s, IP=%s, Port=%s\n",
		instance.App, instance.InstanceID, instance.IPAddr, e.config.Port)
	req, err := http.NewRequest("POST", url, bytes.NewBuffer(jsonData))
	if err != nil {
		return fmt.Errorf("failed to create registration request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	fmt.Printf("Sending HTTP request to: %s\n", url)
	fmt.Printf("Request headers: %v\n", req.Header)

	resp, err := e.httpClient.Do(req)
	if err != nil {
		fmt.Printf("HTTP request failed: %v\n", err)
		return fmt.Errorf("failed to register with Eureka: %w", err)
	}
	defer resp.Body.Close()
	fmt.Printf("Eureka response status: %d\n", resp.StatusCode)
	if resp.StatusCode != http.StatusNoContent && resp.StatusCode != http.StatusOK {
		if resp.Body != nil {
			respBody, _ := io.ReadAll(resp.Body)
			fmt.Printf("Eureka response body: %s\n", string(respBody))
		}
		return fmt.Errorf("Eureka registration failed with status: %d, URL: %s", resp.StatusCode, url)
	}
	fmt.Printf("Successfully registered with Eureka. Instance ID: %s\n", e.instanceID)
	return nil
}

func (e *EurekaService) Deregister() error {
	if !e.config.Register {
		return nil
	}
	baseURL := e.config.ServerURL
	if strings.HasSuffix(baseURL, "/eureka") {
		baseURL = strings.TrimSuffix(baseURL, "/eureka")
	}
	url := fmt.Sprintf("%s/eureka/apps/%s/%s", baseURL, e.config.AppName, e.instanceID)
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
			fmt.Printf("Eureka deregistration response body: %s\n", string(respBody))
		}
		return fmt.Errorf("Eureka deregistration failed with status: %d, URL: %s", resp.StatusCode, url)
	}
	fmt.Printf("Successfully deregistered from Eureka. Instance ID: %s\n", e.instanceID)
	return nil
}

func (e *EurekaService) SendHeartbeat() error {
	if !e.config.Register {
		return nil
	}
	baseURL := e.config.ServerURL
	if strings.HasSuffix(baseURL, "/eureka") {
		baseURL = strings.TrimSuffix(baseURL, "/eureka")
	}
	url := fmt.Sprintf("%s/eureka/apps/%s/%s", baseURL, e.config.AppName, e.instanceID)
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
			fmt.Printf("Eureka heartbeat response body: %s\n", string(respBody))
		}
		return fmt.Errorf("Eureka heartbeat failed with status: %d, URL: %s", resp.StatusCode, url)
	}
	return nil
}

func (e *EurekaService) StartHeartbeat() {
	if !e.config.Register {
		return
	}
	ticker := time.NewTicker(30 * time.Second)
	go func() {
		for range ticker.C {
			if err := e.SendHeartbeat(); err != nil {
				fmt.Printf("Failed to send heartbeat: %v\n", err)
			}
		}
	}()
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
