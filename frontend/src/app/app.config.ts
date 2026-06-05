import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimations } from '@angular/platform-browser/animations';

import { routes } from './app.routes';
import { provideApi } from './api/generated';
import { environment } from '../environments/environment';
import { authInterceptor } from './core/auth/auth.interceptor';
import { refreshTokenInterceptor } from './core/auth/refresh.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    // ng-bootstrap relies on Angular animations (collapse, modal/offcanvas transitions).
    provideAnimations(),
    provideHttpClient(withInterceptors([authInterceptor, refreshTokenInterceptor])),
    provideApi(environment.apiBaseUrl),
  ],
};
