/**
 * Auth signal store — Increment-01 token-storage policy
 *
 * ACCESS TOKEN  — held only in an in-memory signal (never written to any
 *   Web Storage).  This limits the token-at-rest attack surface: if an XSS
 *   script reads sessionStorage it cannot steal the access token because it
 *   is not there.  The trade-off is that a hard page reload loses the access
 *   token, which is recovered via the silent-refresh path below.
 *
 * REFRESH TOKEN — persisted to sessionStorage (keyed REFRESH_KEY).  This is
 *   intentional: sessionStorage is scoped to the browser tab, is cleared when
 *   the tab closes, and allows the app to silently re-authenticate after a
 *   page reload without forcing the user to log in again.  The refresh token
 *   is a rotation-based single-use credential whose exposure window is
 *   therefore limited to the tab lifetime.
 *
 * SILENT BOOT   — on construction, if a refresh token is found in
 *   sessionStorage but there is no in-memory access token, a silent
 *   DefaultService.refreshToken() call is issued.  Guards (CanActivate) are
 *   responsible for routing; the store does NOT navigate on failure here —
 *   only on failure during a proactive scheduled refresh or a 401 retry.
 *
 * PROACTIVE REFRESH — TokenRefreshScheduler arms a one-shot timer on every
 *   setTokens() call, firing REFRESH_LEAD_SECONDS (60 s) before expiry.  The
 *   timer is always replaced (cancel + re-arm) so there is never more than one
 *   pending refresh at a time.  clear() cancels it unconditionally.
 */
import { computed, inject, Injectable, signal } from '@angular/core';
import { DefaultService, type TokenResponse } from '../../api/generated';
import { TokenRefreshScheduler } from './token-refresh-scheduler';

const REFRESH_KEY = 'hmis.refreshToken';

function decodePrivileges(jwt: string): string[] {
  try {
    const parts = jwt.split('.');
    if (parts.length < 2) return [];
    // Base64url → base64 → JSON
    let base64 = (parts[1] ?? '').replace(/-/g, '+').replace(/_/g, '/');
    // Pad to a multiple of 4
    while (base64.length % 4 !== 0) base64 += '=';
    const payload: unknown = JSON.parse(atob(base64));
    if (
      payload !== null &&
      typeof payload === 'object' &&
      'privileges' in payload &&
      Array.isArray((payload as Record<string, unknown>)['privileges'])
    ) {
      return ((payload as Record<string, unknown>)['privileges'] as unknown[])
        .filter((p): p is string => typeof p === 'string');
    }
    return [];
  } catch {
    return [];
  }
}

@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly scheduler      = inject(TokenRefreshScheduler);
  private readonly defaultService = inject(DefaultService);

  /**
   * Access token is purely in-memory.  Never written to sessionStorage or
   * localStorage.  Lost on page reload — recovered by the silent-boot path.
   */
  private readonly accessTokenSig  = signal<string | null>(null);

  /**
   * Refresh token is mirrored from sessionStorage on construction and kept
   * in sync.  sessionStorage is the source of truth on reload.
   */
  private readonly refreshTokenSig = signal<string | null>(
    sessionStorage.getItem(REFRESH_KEY),
  );

  private readonly privilegesSig = signal<string[]>([]);

  // ── public computed ───────────────────────────────────────────────────────
  readonly isAuthenticated = computed(() => this.accessTokenSig() !== null);

  // ── public getters ────────────────────────────────────────────────────────
  getAccessToken(): string | null  { return this.accessTokenSig();  }
  getRefreshToken(): string | null { return this.refreshTokenSig(); }

  constructor() {
    // Break circular injection: scheduler needs to call back into this store.
    this.scheduler.setStore(this);

    // Silent boot: if a refresh token survived a page reload but no access
    // token is in memory yet, try to silently restore the session.
    const storedRefresh = sessionStorage.getItem(REFRESH_KEY);
    if (storedRefresh !== null && this.accessTokenSig() === null) {
      this.silentBoot(storedRefresh);
    }
  }

  // ── mutations ─────────────────────────────────────────────────────────────
  setTokens(t: TokenResponse): void {
    const access  = t.accessToken  ?? null;
    const refresh = t.refreshToken ?? null;

    // Access token: in-memory only — never persisted to any storage.
    this.accessTokenSig.set(access);
    this.privilegesSig.set(access ? decodePrivileges(access) : []);

    // Refresh token: persist to sessionStorage.
    if (refresh !== null) {
      sessionStorage.setItem(REFRESH_KEY, refresh);
    } else {
      sessionStorage.removeItem(REFRESH_KEY);
    }
    this.refreshTokenSig.set(refresh);

    // Arm (or re-arm) the proactive refresh timer if we have both tokens.
    if (access !== null && refresh !== null) {
      this.scheduler.schedule(t.expiresInSeconds, refresh);
    } else {
      this.scheduler.cancel();
    }
  }

  clear(): void {
    // Cancel any pending proactive refresh — must happen before signal resets
    // so the scheduler cannot fire with stale token data.
    this.scheduler.cancel();

    sessionStorage.removeItem(REFRESH_KEY);
    this.accessTokenSig.set(null);
    this.refreshTokenSig.set(null);
    this.privilegesSig.set([]);
  }

  // ── RBAC helper ───────────────────────────────────────────────────────────
  hasPrivilege(code: string): ReturnType<typeof computed<boolean>> {
    return computed(() => this.privilegesSig().includes(code));
  }

  // ── private ───────────────────────────────────────────────────────────────
  /**
   * Fires a single silent refresh attempt on app load when a refresh token
   * exists in sessionStorage but there is no in-memory access token.
   * Does NOT navigate on failure — guards handle routing to /login.
   */
  private silentBoot(refreshToken: string): void {
    this.defaultService
      .refreshToken({ refreshRequest: { refreshToken } })
      .subscribe({
        next: (tokenResponse) => {
          this.setTokens(tokenResponse);
        },
        error: () => {
          // Session is invalid or expired — wipe storage so guards redirect.
          this.clear();
        },
      });
  }
}
