import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';
// Material date adapter is still needed by the (not-yet-migrated) billing dialogs.
// New features use Bootstrap / ng-bootstrap; billing stays on Material until migrated.
import { provideNativeDateAdapter } from '@angular/material/core';

import { routes } from './app.routes';
import { provideApi } from './api/generated';
import { environment } from '../environments/environment';
import { authInterceptor } from './core/auth/auth.interceptor';
import { refreshTokenInterceptor } from './core/auth/refresh.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    // Works for both Angular Material (legacy billing) and ng-bootstrap (new features).
    provideAnimations(),
    provideNativeDateAdapter(),
    provideHttpClient(withInterceptors([authInterceptor, refreshTokenInterceptor])),
    provideApi(environment.apiBaseUrl),
  ],
};
