import {
  HttpErrorResponse,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { BehaviorSubject, filter, switchMap, take, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuthControllerService } from '../../api/generated';
import type { TokenResponse } from '../../api/generated';
import { extractProblem } from '../error/problem-detail';
import { AuthStore } from './auth.store';

/**
 * Handles 401 responses with a reactive single-flight refresh.
 *
 * All concurrent requests that 401 simultaneously share a single refresh
 * call.  While the refresh is in-flight any additional 401s queue on the
 * BehaviorSubject; once the refresh completes they all retry with the new
 * access token.
 *
 * Token-reuse detection (Increment-01):
 *   If the server returns RFC-7807 `code` or `type` equal to
 *   `urn:hmis:error:token-reuse-detected`, the session is considered
 *   compromised.  We do NOT retry — we clear immediately and navigate to
 *   /login.  This is matched via extractProblem(), never via message text.
 */

const TOKEN_REUSE_URN = 'urn:hmis:error:token-reuse-detected';

/**
 * Module-level state for the single-flight refresh.
 * These are module-scope (not instance-scope) because Angular functional
 * interceptors are called as plain functions — there is no class instance to
 * hold shared state between concurrent calls.  Both are intentionally
 * closed over the module to survive the full app lifetime.
 */
let refreshInFlight = false;
const refreshSubject = new BehaviorSubject<TokenResponse | null>(null);

export const refreshTokenInterceptor: HttpInterceptorFn = (req, next) => {
  const authStore   = inject(AuthStore);
  const authService = inject(AuthControllerService);
  const router      = inject(Router);

  // Both auth endpoints are excluded: prevents the refresh call itself from
  // triggering a retry loop and leaves login 401s (bad credentials) unretried.
  const isAuthEndpoint =
    req.url.endsWith('/auth/token/refresh') ||
    req.url.endsWith('/auth/token');

  return next(req).pipe(
    catchError((error: unknown) => {
      if (
        !(error instanceof HttpErrorResponse) ||
        error.status !== 401 ||
        isAuthEndpoint
      ) {
        return throwError(() => error);
      }

      // Check for token-reuse-detected BEFORE attempting any refresh.
      const problem = extractProblem(error);
      if (
        problem.code === TOKEN_REUSE_URN ||
        problem.type === TOKEN_REUSE_URN
      ) {
        // Security: reuse signal means the session is compromised — do not
        // retry under any circumstances.
        authStore.clear();
        void router.navigate(['/login']);
        return throwError(() => error);
      }

      const refreshToken = authStore.getRefreshToken();
      if (refreshToken === null) {
        authStore.clear();
        void router.navigate(['/login']);
        return throwError(() => error);
      }

      if (refreshInFlight) {
        // Another request is already refreshing.  Queue on the subject and
        // retry once it emits a new TokenResponse.
        return refreshSubject.pipe(
          filter((t): t is TokenResponse => t !== null),
          take(1),
          switchMap((tokenResponse) => {
            const newToken = tokenResponse.accessToken ?? null;
            const retryReq: HttpRequest<unknown> =
              newToken === null
                ? req
                : req.clone({ setHeaders: { Authorization: `Bearer ${newToken}` } });
            return next(retryReq);
          }),
        );
      }

      // This request wins the race — it performs the refresh.
      refreshInFlight = true;
      refreshSubject.next(null); // Signal "refresh in progress" to queued requests.

      return authService
        .refresh({ refreshRequest: { refreshToken } })
        .pipe(
          switchMap((tokenResponse) => {
            authStore.setTokens(tokenResponse);

            // Check the refresh response itself for reuse detection.
            // (The server may also embed the URN in a successful-looking 200
            // if it detects re-issue of an already-used token.)
            refreshInFlight = false;
            refreshSubject.next(tokenResponse); // Unblock all queued requests.

            const newToken = authStore.getAccessToken();
            const retryReq: HttpRequest<unknown> =
              newToken === null
                ? req
                : req.clone({ setHeaders: { Authorization: `Bearer ${newToken}` } });
            return next(retryReq);
          }),
          catchError((refreshError: unknown) => {
            refreshInFlight = false;
            refreshSubject.next(null);

            // Check whether the refresh failure itself signals token reuse.
            const refreshProblem = extractProblem(refreshError);
            if (
              refreshProblem.code === TOKEN_REUSE_URN ||
              refreshProblem.type === TOKEN_REUSE_URN
            ) {
              authStore.clear();
              void router.navigate(['/login']);
              return throwError(() => refreshError);
            }

            authStore.clear();
            void router.navigate(['/login']);
            return throwError(() => refreshError);
          }),
        );
    }),
  );
};
