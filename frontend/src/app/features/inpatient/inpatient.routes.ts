import { Routes } from '@angular/router';

/**
 * Inpatient feature routes (inc-07): admissions list, admit form, and the per-admission detail
 * page (overview + nursing charts + MAR + consumables + dispositions).
 *
 * Gated at the shell by the Inpatient nav item's PATIENT-ALL privilege; individual writes are
 * further gated server-side (e.g. MEDICATION-ADMINISTER on MAR).
 */
export const INPATIENT_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'admissions',
  },
  {
    path: 'admissions',
    title: 'Admissions — Inpatient',
    loadComponent: () =>
      import('./admissions-list.component').then((m) => m.AdmissionsListComponent),
  },
  {
    path: 'admissions/new',
    title: 'Admit Patient — Inpatient',
    loadComponent: () =>
      import('./admit-form.component').then((m) => m.AdmitFormComponent),
  },
  {
    path: 'admissions/:uid',
    title: 'Admission — Inpatient',
    loadComponent: () =>
      import('./admission-detail.component').then((m) => m.AdmissionDetailComponent),
  },
];
