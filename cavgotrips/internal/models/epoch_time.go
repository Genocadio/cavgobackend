package models

import (
	"encoding/json"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"
)

// EpochTime unmarshals JSON numbers or strings representing seconds since epoch (fractional supported).
type EpochTime time.Time

// UnmarshalJSON accepts numeric seconds (int/float) or RFC3339 strings.
func (t *EpochTime) UnmarshalJSON(b []byte) error {
	s := strings.TrimSpace(string(b))
	if s == "" || s == "null" {
		*t = EpochTime(time.Time{})
		return nil
	}

	if s[0] == '"' {
		var str string
		if err := json.Unmarshal(b, &str); err != nil {
			return err
		}
		if parsed, err := time.Parse(time.RFC3339Nano, str); err == nil {
			*t = EpochTime(parsed)
			return nil
		}
		if f, err := strconv.ParseFloat(str, 64); err == nil {
			sec, frac := math.Modf(f)
			*t = EpochTime(time.Unix(int64(sec), int64(frac*1e9)).UTC())
			return nil
		}
		return fmt.Errorf("invalid time string: %s", str)
	}

	var f float64
	if err := json.Unmarshal(b, &f); err != nil {
		var i int64
		if errInt := json.Unmarshal(b, &i); errInt != nil {
			return err
		}
		f = float64(i)
	}
	sec, frac := math.Modf(f)
	*t = EpochTime(time.Unix(int64(sec), int64(frac*1e9)).UTC())
	return nil
}

// Unix returns the Unix timestamp in seconds.
func (t EpochTime) Unix() int64 {
	return time.Time(t).Unix()
}

// Time returns the underlying time value.
func (t EpochTime) Time() time.Time {
	return time.Time(t)
}

// IsZero reports whether the time is zero.
func (t EpochTime) IsZero() bool {
	return time.Time(t).IsZero()
}
