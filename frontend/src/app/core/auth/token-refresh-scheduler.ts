/**
 * TokenRefreshScheduler
 *
 * Schedules a proactive silent token refresh before the access token expires.
 * It is a separate service (not inlined into AuthStore) so it can inject
 * DefaultService and Router without creating a circular dependency chain, and
 * so its timer lifecycle is independently testable.
 *
 * Lifecycle contract:
 *  - `schedule(expiresInSeconds, refreshToken)` — arms a one-shot setTimeout.
 *    Any previously armed timer is cancelled first (cancel-then-reschedule on
 *    every setTokens call, replacing the previous schedule).
 *  - `cancel()` — clears the pending timeout and nulls the handle; called from
 *    AuthStore.clear() and implicitly from schedule() before re-arming.
 *
 * On fire the scheduler calls DefaultService.refreshToken, then hands the
 * result back to AuthStore.setTokens (which re-schedules automatically).
 * On failure it calls AuthStore.clear() and navigates to /login.
 */
import { inject, Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { DefaultService } from '../../api/generated';
import type { AuthStore } from './auth.store';

/** Minimum lead-time in seconds before expiry at which we fire the refresh. */
const REFRESH_LEAD_SECONDS = 60;

/** Floor: never fire sooner than 5 seconds from now (handles tiny/missing TTL). */
const MIN_DELAY_SECONDS = 5;

@Injectable({ providedIn: 'root' })
export class TokenRefreshScheduler {
  private readonly defaultService = inject(DefaultService);
  private readonly router         = inject(Router);

  /** Handle returned by setTimeout; null when no timer is pending. */
  private timerId: ReturnType<typeof setTimeout> | null = null;

  /**
   * AuthStore is injected lazily via a setter to break the circular
   * injection chain (AuthStore → TokenRefreshScheduler → AuthStore).
   * AuthStore calls `setStore(this)` in its constructor.
   */
  private authStore: AuthStore | null = null;

  setStore(store: AuthStore): void {
    this.authStore = store;
  }

  /**
   * Arms the refresh timer.  Any existing timer is cancelled first.
   *
   * @param expiresInSeconds - value from TokenResponse.expiresInSeconds
   *                           (may be undefined if the server omits it)
   * @param refreshToken     - the current refresh token to use in the call
   */
  schedule(expiresInSeconds: number | undefined, refreshToken: string): void {
    this.cancel();

    // How many seconds until we should fire: TTL minus lead-time, floored.
    const rawDelay = (expiresInSeconds ?? MIN_DELAY_SECONDS) - REFRESH_LEAD_SECONDS;
    const delayMs  = Math.max(rawDelay, MIN_DELAY_SECONDS) * 1000;

    this.timerId = setTimeout(() => {
      this.timerId = null;
      this.executeRefresh(refreshToken);
    }, delayMs);
  }

  /** Cancels any pending timer.  Safe to call when no timer is active. */
  cancel(): void {
    if (this.timerId !== null) {
      clearTimeout(this.timerId);
      this.timerId = null;
    }
  }

  private executeRefresh(refreshToken: string): void {
    this.defaultService
      .refreshToken({ refreshRequest: { refreshToken } })
      .subscribe({
        next: (tokenResponse) => {
          // setTokens re-arms the scheduler via schedule() internally.
          this.authStore?.setTokens(tokenResponse);
        },
        error: () => {
          this.authStore?.clear();
          void this.router.navigate(['/login']);
        },
      });
  }
}
