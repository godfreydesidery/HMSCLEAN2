import { Routes } from '@angular/router';
import { privilegeGuard } from './core/auth/privilege.guard';

// ─────────────────────────────────────────────────────────────────────────────
// FUTURE LAZY-LOADED BOUNDED-CONTEXT FEATURE ROUTES (Increment-01 onwards)
// Each entry below is a placeholder — do NOT uncomment until the corresponding
// feature module exists on disk and its barrel export is confirmed.
//
// { path: 'registration',  canActivate: [privilegeGuard], loadChildren: () => import('./features/registration/registration.routes').then(m => m.REGISTRATION_ROUTES)  },
// { path: 'outpatient',    canActivate: [privilegeGuard], loadChildren: () => import('./features/outpatient/outpatient.routes').then(m => m.OUTPATIENT_ROUTES)         },
// { path: 'inpatient',     canActivate: [privilegeGuard], loadChildren: () => import('./features/inpatient/inpatient.routes').then(m => m.INPATIENT_ROUTES)            },
// { path: 'pharmacy',      canActivate: [privilegeGuard], loadChildren: () => import('./features/pharmacy/pharmacy.routes').then(m => m.PHARMACY_ROUTES)               },
// { path: 'laboratory',    canActivate: [privilegeGuard], loadChildren: () => import('./features/laboratory/laboratory.routes').then(m => m.LABORATORY_ROUTES)         },
// { path: 'radiology',     canActivate: [privilegeGuard], loadChildren: () => import('./features/radiology/radiology.routes').then(m => m.RADIOLOGY_ROUTES)            },
// { path: 'procedures',    canActivate: [privilegeGuard], loadChildren: () => import('./features/procedures/procedures.routes').then(m => m.PROCEDURES_ROUTES)         },
// { path: 'billing',       canActivate: [privilegeGuard], loadChildren: () => import('./features/billing/billing.routes').then(m => m.BILLING_ROUTES)                  },
// { path: 'inventory',     canActivate: [privilegeGuard], loadChildren: () => import('./features/inventory/inventory.routes').then(m => m.INVENTORY_ROUTES)            },
// { path: 'payroll',       canActivate: [privilegeGuard], loadChildren: () => import('./features/payroll/payroll.routes').then(m => m.PAYROLL_ROUTES)                  },
// { path: 'masterdata',    canActivate: [privilegeGuard], loadChildren: () => import('./features/masterdata/masterdata.routes').then(m => m.MASTERDATA_ROUTES)         },
// { path: 'reports',       canActivate: [privilegeGuard], loadChildren: () => import('./features/reports/reports.routes').then(m => m.REPORTS_ROUTES)                  },
// { path: 'iam',           canActivate: [privilegeGuard], loadChildren: () => import('./features/iam/iam.routes').then(m => m.IAM_ROUTES)                              },
// ─────────────────────────────────────────────────────────────────────────────

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () =>
      import('./features/auth/login.component').then((m) => m.LoginComponent),
  },
  {
    path: 'home',
    canActivate: [privilegeGuard],
    data: { privilege: 'ADMIN-ACCESS' },
    loadComponent: () =>
      import('./features/home/company-profile.component').then(
        (m) => m.CompanyProfileComponent,
      ),
  },
  {
    path: 'forbidden',
    loadComponent: () =>
      import('./features/forbidden/forbidden.component').then(
        (m) => m.ForbiddenComponent,
      ),
  },
  {
    path: 'registration',
    canActivate: [privilegeGuard],
    data: { privilege: 'PATIENT-ALL' },
    loadChildren: () =>
      import('./features/registration/registration.routes').then((m) => m.REGISTRATION_ROUTES),
  },
  {
    path: 'billing',
    canActivate: [privilegeGuard],
    data: { privilege: 'BILL-A' },
    loadChildren: () =>
      import('./features/billing/billing.routes').then((m) => m.BILLING_ROUTES),
  },
  {
    path: 'inpatient',
    canActivate: [privilegeGuard],
    data: { privilege: 'PATIENT-ALL' },
    loadChildren: () =>
      import('./features/inpatient/inpatient.routes').then((m) => m.INPATIENT_ROUTES),
  },
  { path: '', pathMatch: 'full', redirectTo: 'home' },
  { path: '**', redirectTo: 'home' },
];
