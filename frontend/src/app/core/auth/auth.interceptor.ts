import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { environment } from '../../../environments/environment';
import { AuthStore } from './auth.store';

/**
 * Attaches the Bearer token to every request destined for the HMIS API,
 * excluding the token-issue and token-refresh endpoints (they must reach
 * the server unauthenticated).  Actuator URLs are never touched.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authStore = inject(AuthStore);

  const isApiRequest     = req.url.startsWith(environment.apiBaseUrl);
  const isTokenEndpoint  =
    req.url.endsWith('/auth/token') ||
    req.url.endsWith('/auth/token/refresh');

  const token = authStore.getAccessToken();

  if (isApiRequest && !isTokenEndpoint && token !== null) {
    const authedReq = req.clone({
      setHeaders: { Authorization: `Bearer ${token}` },
    });
    return next(authedReq);
  }

  return next(req);
};
