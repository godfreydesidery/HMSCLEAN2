import {
  HttpErrorResponse,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';
import { DefaultService } from '../../api/generated';
import { AuthStore } from './auth.store';

/**
 * Handles 401 responses by attempting a single token refresh per original
 * request.
 *
 * Simplification note (Increment-00): each in-flight request independently
 * attempts a refresh on 401.  Concurrent requests that 401 simultaneously
 * will make concurrent refresh calls.  A shared refresh-in-flight observable
 * (preventing parallel refresh races) will be added in a later increment once
 * the full interceptor chain is established.
 */
export const refreshTokenInterceptor: HttpInterceptorFn = (req, next) => {
  const authStore      = inject(AuthStore);
  const defaultService = inject(DefaultService);
  const router         = inject(Router);

  // Determined once for the lifetime of this request closure. Covers BOTH
  // auth endpoints: it stops the refresh call itself from entering a retry
  // loop, and it leaves a login 401 (bad credentials) to surface unretried.
  const isAuthEndpoint =
    req.url.endsWith('/auth/token/refresh') ||
    req.url.endsWith('/auth/token');

  // Per-closure flag: only retry once for this original request.
  let alreadyRetried = false;

  return next(req).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        !isAuthEndpoint &&
        !alreadyRetried
      ) {
        const refreshToken = authStore.getRefreshToken();

        if (refreshToken === null) {
          // No refresh token available — redirect immediately.
          authStore.clear();
          void router.navigate(['/login']);
        } else {
          alreadyRetried = true;

          return defaultService
            .refreshToken({ refreshRequest: { refreshToken } })
            .pipe(
              switchMap((tokenResponse) => {
                authStore.setTokens(tokenResponse);
                const newToken = authStore.getAccessToken();
                const retryReq: HttpRequest<unknown> =
                  newToken !== null
                    ? req.clone({
                        setHeaders: {
                          Authorization: `Bearer ${newToken}`,
                        },
                      })
                    : req;
                return next(retryReq);
              }),
              catchError((refreshError: unknown) => {
                authStore.clear();
                void router.navigate(['/login']);
                return throwError(() => refreshError);
              }),
            );
        }
      }

      return throwError(() => error);
    }),
  );
};
