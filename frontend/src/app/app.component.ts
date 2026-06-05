import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthControllerService } from './api/generated';
import { AuthStore } from './core/auth/auth.store';
import { HealthIndicatorComponent } from './core/health/health-indicator.component';
import { CanDirective } from './core/auth/can.directive';
import { NAV_ITEMS } from './core/nav/nav-items';

/**
 * Application shell — Bootstrap 5 layout (top navbar + collapsible left sidebar).
 *
 * The sidebar lists every module from {@link NAV_ITEMS}; each link is gated by the
 * `*appCan` privilege directive (privilege codes only — never role names). Modules
 * whose feature is not yet built render as disabled "coming soon" items rather than
 * dead links. The shell itself carries no business logic beyond logout.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    HealthIndicatorComponent,
    CanDirective,
  ],
  templateUrl: './app.component.html',
  styleUrl: './app.component.scss',
})
export class AppComponent {
  protected readonly authStore   = inject(AuthStore);
  private  readonly authService = inject(AuthControllerService);
  private  readonly router      = inject(Router);

  protected readonly navItems = NAV_ITEMS;

  /** Sidebar open/closed state (collapsed on small screens; toggled by the hamburger). */
  protected readonly sidebarOpen = signal(true);

  protected toggleSidebar(): void {
    this.sidebarOpen.update((v) => !v);
  }

  /**
   * Logout: best-effort revoke the refresh token on the server so it cannot be
   * replayed, then clear local state unconditionally and navigate to /login. The
   * revoke call is fire-and-forget — a network failure must never block local logout.
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

    this.authService
      .revoke({ revokeRequest: { refreshToken } })
      .subscribe({
        next:  () => doLocalLogout(),
        error: () => doLocalLogout(), // best-effort: clear regardless
      });
  }
}
