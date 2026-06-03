/**
 * Auth signal store.
 *
 * Tokens are persisted to sessionStorage (not localStorage) so that each
 * browser tab / session gets its own copy and tokens are automatically
 * cleared when the tab is closed.  On shared clinical workstations this
 * significantly reduces token-at-rest exposure compared to localStorage,
 * while still surviving a hard page-reload within the same session.
 */
import { computed, Injectable, signal } from '@angular/core';
import type { TokenResponse } from '../../api/generated';

const ACCESS_KEY  = 'hmis.accessToken';
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
  private readonly accessTokenSig  = signal<string | null>(
    sessionStorage.getItem(ACCESS_KEY),
  );
  private readonly refreshTokenSig = signal<string | null>(
    sessionStorage.getItem(REFRESH_KEY),
  );
  private readonly privilegesSig   = signal<string[]>(
    (() => {
      const stored = sessionStorage.getItem(ACCESS_KEY);
      return stored ? decodePrivileges(stored) : [];
    })(),
  );

  // ── public readonly computed ──────────────────────────────────────────
  readonly isAuthenticated = computed(() => this.accessTokenSig() !== null);

  // ── public getters (return current value, not signals) ────────────────
  getAccessToken(): string | null  { return this.accessTokenSig();  }
  getRefreshToken(): string | null { return this.refreshTokenSig(); }

  // ── mutations ─────────────────────────────────────────────────────────
  setTokens(t: TokenResponse): void {
    const access  = t.accessToken  ?? null;
    const refresh = t.refreshToken ?? null;

    if (access !== null) {
      sessionStorage.setItem(ACCESS_KEY, access);
    } else {
      sessionStorage.removeItem(ACCESS_KEY);
    }

    if (refresh !== null) {
      sessionStorage.setItem(REFRESH_KEY, refresh);
    } else {
      sessionStorage.removeItem(REFRESH_KEY);
    }

    this.accessTokenSig.set(access);
    this.refreshTokenSig.set(refresh);
    this.privilegesSig.set(access ? decodePrivileges(access) : []);
  }

  clear(): void {
    sessionStorage.removeItem(ACCESS_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
    this.accessTokenSig.set(null);
    this.refreshTokenSig.set(null);
    this.privilegesSig.set([]);
  }

  // ── RBAC helper ───────────────────────────────────────────────────────
  hasPrivilege(code: string): ReturnType<typeof computed<boolean>> {
    return computed(() => this.privilegesSig().includes(code));
  }
}
