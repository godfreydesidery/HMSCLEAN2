import { inject } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  CanActivateChildFn,
  CanActivateFn,
  Router,
  RouterStateSnapshot,
} from '@angular/router';
import { AuthStore } from './auth.store';

/**
 * Simple authentication gate: redirects to /login when no access token is
 * present.
 */
export const authGuard: CanActivateFn = (): boolean | ReturnType<Router['parseUrl']> => {
  const authStore = inject(AuthStore);
  const router    = inject(Router);

  return authStore.isAuthenticated() ? true : router.parseUrl('/login');
};

function checkPrivilege(
  route: ActivatedRouteSnapshot,
): boolean | ReturnType<Router['parseUrl']> {
  const authStore = inject(AuthStore);
  const router    = inject(Router);

  if (!authStore.isAuthenticated()) {
    return router.parseUrl('/login');
  }

  const privilege = route.data['privilege'] as string | undefined;
  if (privilege !== undefined && !authStore.hasPrivilege(privilege)()) {
    return router.parseUrl('/forbidden');
  }

  return true;
}

/**
 * Privilege gate for `canActivate`: checks `route.data['privilege']`.
 * Redirects to /login if unauthenticated, /forbidden if privilege absent.
 */
export const privilegeGuard: CanActivateFn = (
  route: ActivatedRouteSnapshot,
  _state: RouterStateSnapshot,
): boolean | ReturnType<Router['parseUrl']> => checkPrivilege(route);

/**
 * Privilege gate for `canActivateChild`: delegates to the same logic.
 */
export const privilegeChildGuard: CanActivateChildFn = (
  childRoute: ActivatedRouteSnapshot,
  _state: RouterStateSnapshot,
): boolean | ReturnType<Router['parseUrl']> => checkPrivilege(childRoute);
