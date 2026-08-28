package middleware

import "net/http"

func CORSMiddleware(next http.Handler) http.Handler {
	// CORS is handled centrally by the API gateway. This service deliberately
	// does not set Access-Control-* headers to avoid conflicting with the
	// gateway's CORS (which resolves the allowed origin). Only short-circuit
	// OPTIONS preflights that reach this far.
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusOK)
			return
		}

		next.ServeHTTP(w, r)
	})
}
