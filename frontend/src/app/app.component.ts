import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink, RouterOutlet } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { DefaultService } from './api/generated';
import { AuthStore } from './core/auth/auth.store';
import { HealthIndicatorComponent } from './core/health/health-indicator.component';

@Component({
  selector: 'app-root',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    RouterLink,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    HealthIndicatorComponent,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent {
  protected readonly authStore     = inject(AuthStore);
  private  readonly defaultService = inject(DefaultService);
  private  readonly router         = inject(Router);

  /**
   * Logout: best-effort revoke the refresh token on the server so it cannot
   * be replayed, then clear local state unconditionally and navigate to /login.
   * The revoke call is fire-and-forget — a network failure must never prevent
   * the user from logging out of the local session.
   */
  logout(): void {
    const refreshToken = this.authStore.getRefreshToken();

    const doLocalLogout = (): void => {
      this.authStore.clear();
      void this.router.navigate(['/login']);
    };

    if (refreshToken === null) {
      doLocalLogout();
      return;
    }

    this.defaultService
      .revokeToken({ revokeRequest: { refreshToken } })
      .subscribe({
        next:     () => doLocalLogout(),
        error:    () => doLocalLogout(), // best-effort: clear regardless
        complete: () => { /* handled by next/error */ },
      });
  }
}
