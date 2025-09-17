package repository

import (
	"cavgotrips/internal/models"
	"fmt"
	"regexp"
	"strconv"
	"strings"

	"gorm.io/gorm"
)

type locationRepository struct {
	db *gorm.DB
}

func NewLocationRepository(db *gorm.DB) LocationRepository {
	return &locationRepository{db: db}
}

func (r *locationRepository) Create(location *models.Location) error {
	return r.db.Create(location).Error
}

func (r *locationRepository) GetAll() ([]models.Location, error) {
	var locations []models.Location
	err := r.db.Find(&locations).Error
	return locations, err
}

func (r *locationRepository) GetAllPaginated(limit, offset int) ([]models.Location, int64, error) {
	var locations []models.Location
	var total int64

	// Get total count
	err := r.db.Model(&models.Location{}).Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Get paginated results
	err = r.db.Limit(limit).Offset(offset).Find(&locations).Error
	return locations, total, err
}

func (r *locationRepository) Search(searchTerm string) ([]models.Location, error) {
	var locations []models.Location

	// Check if searchTerm is numeric (for location code search)
	if isNumeric(searchTerm) {
		// Search by location code
		err := r.db.Where("code LIKE ?", "%"+searchTerm+"%").Find(&locations).Error
		return locations, err
	}

	// Search by custom name or google place name (case-insensitive)
	// Complex ordering: field priority (custom_name first) + match position priority
	searchPattern := "%" + searchTerm + "%"
	lowerSearchTerm := strings.ToLower(searchTerm)
	startPattern := lowerSearchTerm + "%"
	wordStartPattern := "% " + lowerSearchTerm + "%"

	orderClause := fmt.Sprintf(`
		CASE 
			-- Custom name matches (priority 0-2)
			WHEN LOWER(custom_name) LIKE '%s' THEN 0
			WHEN LOWER(custom_name) LIKE '%s' OR LOWER(custom_name) LIKE '%s' THEN 1
			WHEN LOWER(custom_name) LIKE '%s' THEN 2
			-- Google place name matches (priority 10-12)
			WHEN LOWER(google_place_name) LIKE '%s' THEN 10
			WHEN LOWER(google_place_name) LIKE '%s' OR LOWER(google_place_name) LIKE '%s' THEN 11
			WHEN LOWER(google_place_name) LIKE '%s' THEN 12
			ELSE 99
		END, custom_name, google_place_name`,
		startPattern,                   // custom_name starts with
		wordStartPattern, startPattern, // custom_name word starts with (both patterns)
		strings.ToLower(searchPattern), // custom_name contains
		startPattern,                   // google_place_name starts with
		wordStartPattern, startPattern, // google_place_name word starts with (both patterns)
		strings.ToLower(searchPattern)) // google_place_name contains

	err := r.db.Where("LOWER(custom_name) LIKE LOWER(?) OR LOWER(google_place_name) LIKE LOWER(?)",
		searchPattern, searchPattern).
		Order(orderClause).
		Find(&locations).Error
	return locations, err
}

func (r *locationRepository) SearchPaginated(searchTerm string, limit, offset int) ([]models.Location, int64, error) {
	var locations []models.Location
	var total int64

	// Check if searchTerm is numeric (for location code search)
	if isNumeric(searchTerm) {
		// Get total count for location code search
		err := r.db.Model(&models.Location{}).Where("code LIKE ?", "%"+searchTerm+"%").Count(&total).Error
		if err != nil {
			return nil, 0, err
		}

		// Get paginated results for location code search
		err = r.db.Where("code LIKE ?", "%"+searchTerm+"%").Limit(limit).Offset(offset).Find(&locations).Error
		return locations, total, err
	}

	// Get total count for text search
	err := r.db.Model(&models.Location{}).Where("LOWER(custom_name) LIKE LOWER(?) OR LOWER(google_place_name) LIKE LOWER(?)",
		"%"+searchTerm+"%", "%"+searchTerm+"%").Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	// Get paginated results for text search
	// Complex ordering: field priority (custom_name first) + match position priority
	searchPattern := "%" + searchTerm + "%"
	lowerSearchTerm := strings.ToLower(searchTerm)
	startPattern := lowerSearchTerm + "%"
	wordStartPattern := "% " + lowerSearchTerm + "%"

	orderClause := fmt.Sprintf(`
		CASE 
			-- Custom name matches (priority 0-2)
			WHEN LOWER(custom_name) LIKE '%s' THEN 0
			WHEN LOWER(custom_name) LIKE '%s' OR LOWER(custom_name) LIKE '%s' THEN 1
			WHEN LOWER(custom_name) LIKE '%s' THEN 2
			-- Google place name matches (priority 10-12)
			WHEN LOWER(google_place_name) LIKE '%s' THEN 10
			WHEN LOWER(google_place_name) LIKE '%s' OR LOWER(google_place_name) LIKE '%s' THEN 11
			WHEN LOWER(google_place_name) LIKE '%s' THEN 12
			ELSE 99
		END, custom_name, google_place_name`,
		startPattern,                   // custom_name starts with
		wordStartPattern, startPattern, // custom_name word starts with (both patterns)
		strings.ToLower(searchPattern), // custom_name contains
		startPattern,                   // google_place_name starts with
		wordStartPattern, startPattern, // google_place_name word starts with (both patterns)
		strings.ToLower(searchPattern)) // google_place_name contains

	err = r.db.Where("LOWER(custom_name) LIKE LOWER(?) OR LOWER(google_place_name) LIKE LOWER(?)",
		searchPattern, searchPattern).
		Order(orderClause).
		Limit(limit).Offset(offset).Find(&locations).Error
	return locations, total, err
}

// isNumeric checks if a string contains only numeric characters
func isNumeric(s string) bool {
	matched, _ := regexp.MatchString(`^[0-9]+$`, s)
	return matched
}

func (r *locationRepository) ExistsByCustomName(customName string) (bool, error) {
	var count int64
	err := r.db.Model(&models.Location{}).
		Where("custom_name = ?", customName).
		Count(&count).Error
	return count > 0, err
}

func (r *locationRepository) ExistsByCode(code string) (bool, error) {
	var count int64
	err := r.db.Model(&models.Location{}).
		Where("code =?", code).
		Count(&count).Error
	return count > 0, err
}

func (r *locationRepository) ExistsByPlaceID(placeID string) (bool, error) {
	var count int64
	err := r.db.Model(&models.Location{}).
		Where("place_id = ?", placeID).
		Count(&count).Error
	return count > 0, err
}

func (r *locationRepository) ExistsByLatLng(lat, lng float64) (bool, error) {
	var count int64
	err := r.db.Model(&models.Location{}).
		Where("latitude = ? AND longitude = ?", lat, lng).
		Count(&count).Error
	return count > 0, err
}

func (r *locationRepository) GetByID(id int64) (*models.Location, error) {
	var location models.Location
	err := r.db.First(&location, id).Error
	if err != nil {
		return nil, err
	}
	return &location, nil
}

func (r *locationRepository) ValidateExists(id int64) error {
	var count int64
	err := r.db.Model(&models.Location{}).
		Where("id = ?", id).
		Count(&count).Error
	if err != nil {
		return err
	}
	if count == 0 {
		return gorm.ErrRecordNotFound
	}
	return nil
}

func (r *locationRepository) Update(location *models.Location) error {
	return r.db.Save(location).Error
}

func (r *locationRepository) Delete(id int64) error {
	return r.db.Delete(&models.Location{}, id).Error
}

// Rwanda province and district mapping
var provinceMap = map[string]int{
	"kigali": 1,
	"north":  2,
	"east":   3,
	"south":  4,
	"west":   5,
}

var districtMap = map[string]map[string]int{
	"kigali": {
		"gasabo":     1,
		"kicukiro":   2,
		"nyarugenge": 3,
	},
	"north": {
		"burera":  1,
		"gakenke": 2,
		"musanze": 3,
		"rulindo": 4,
		"gicumbi": 5,
	},
	"east": {
		"bugesera":  1,
		"gatsibo":   2,
		"kayonza":   3,
		"kirehe":    4,
		"ngoma":     5,
		"nyagatare": 6,
		"rwamagana": 7,
	},
	"south": {
		"gisagara":  1,
		"huye":      2,
		"kamonyi":   3,
		"muhanga":   4,
		"nyamagabe": 5,
		"nyanza":    6,
		"nyaruguru": 7,
		"ruhango":   8,
	},
	"west": {
		"karongi":    1,
		"ngororero":  2,
		"nyabihu":    3,
		"nyamasheke": 4,
		"rubavu":     5,
		"rusizi":     6,
		"rutsiro":    7,
	},
}

func (r *locationRepository) GenerateLocationCode(province, district string) (string, error) {
	// Convert to lowercase for case-insensitive matching
	provinceLower := strings.ToLower(province)
	districtLower := strings.ToLower(district)

	// Get province code
	provinceCode, exists := provinceMap[provinceLower]
	if !exists {
		return "", fmt.Errorf("invalid province: %s", province)
	}

	// Get district code
	districts, exists := districtMap[provinceLower]
	if !exists {
		return "", fmt.Errorf("invalid province: %s", province)
	}

	districtCode, exists := districts[districtLower]
	if !exists {
		return "", fmt.Errorf("invalid district: %s for province: %s", district, province)
	}

	// Find the next available location code
	prefix := fmt.Sprintf("%d%d", provinceCode, districtCode)

	var maxCode int = 0
	var locations []models.Location

	err := r.db.Where("code LIKE ?", prefix+"%").Find(&locations).Error
	if err != nil {
		return "", err
	}

	// Find the highest existing code for this province-district combination
	for _, location := range locations {
		if location.Code != nil {
			if len(*location.Code) >= 5 {
				// Extract only the last 3 digits (location number within district)
				locationNumberStr := (*location.Code)[2:]
				if locationNumber, err := strconv.Atoi(locationNumberStr); err == nil {
					if locationNumber > maxCode {
						maxCode = locationNumber
					}
				}
			}
		}
	}

	// Generate next location number (increment the last 3 digits)
	nextLocationNumber := maxCode + 1

	// Ensure the location number is within the valid range (000-999)
	if nextLocationNumber > 999 {
		return "", fmt.Errorf("maximum location count reached for district %s in province %s", district, province)
	}

	// Combine province-district prefix with location number
	code := fmt.Sprintf("%s%03d", prefix, nextLocationNumber)

	return code, nil
}
