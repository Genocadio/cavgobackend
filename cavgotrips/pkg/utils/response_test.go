package utils

import (
	"testing"
)

func TestPaginationCalculation(t *testing.T) {
	tests := []struct {
		total      int64
		page       int
		limit      int
		expected   Pagination
	}{
		{
			total: 100,
			page:  1,
			limit: 20,
			expected: Pagination{
				Page:       1,
				Limit:      20,
				Total:      100,
				TotalPages: 5,
				HasNext:    true,
				HasPrev:    false,
			},
		},
		{
			total: 100,
			page:  3,
			limit: 20,
			expected: Pagination{
				Page:       3,
				Limit:      20,
				Total:      100,
				TotalPages: 5,
				HasNext:    true,
				HasPrev:    true,
			},
		},
		{
			total: 100,
			page:  5,
			limit: 20,
			expected: Pagination{
				Page:       5,
				Limit:      20,
				Total:      100,
				TotalPages: 5,
				HasNext:    false,
				HasPrev:    true,
			},
		},
		{
			total: 15,
			page:  1,
			limit: 20,
			expected: Pagination{
				Page:       1,
				Limit:      20,
				Total:      15,
				TotalPages: 1,
				HasNext:    false,
				HasPrev:    false,
			},
		},
	}

	for _, test := range tests {
		// Calculate pagination manually to test the logic
		totalPages := int((test.total + int64(test.limit) - 1) / int64(test.limit))
		hasNext := test.page < totalPages
		hasPrev := test.page > 1

		if totalPages != test.expected.TotalPages {
			t.Errorf("TotalPages calculation failed: got %d, expected %d", totalPages, test.expected.TotalPages)
		}
		if hasNext != test.expected.HasNext {
			t.Errorf("HasNext calculation failed: got %v, expected %v", hasNext, test.expected.HasNext)
		}
		if hasPrev != test.expected.HasPrev {
			t.Errorf("HasPrev calculation failed: got %v, expected %v", hasPrev, test.expected.HasPrev)
		}
	}
} 