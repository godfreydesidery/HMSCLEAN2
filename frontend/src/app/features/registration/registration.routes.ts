import { Routes } from '@angular/router';

/**
 * Registration / Reception feature routes — patient list (search) + register + edit.
 * Wires to PatientController (full backend): search, register, getByUid, update.
 */
export const REGISTRATION_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'patients',
  },
  {
    path: 'patients',
    title: 'Patients — Registration',
    loadComponent: () =>
      import('./patient-list.component').then((m) => m.PatientListComponent),
  },
  {
    path: 'patients/new',
    title: 'Register Patient',
    loadComponent: () =>
      import('./patient-form.component').then((m) => m.PatientFormComponent),
  },
  {
    path: 'patients/:uid',
    title: 'Patient — Registration',
    loadComponent: () =>
      import('./patient-detail.component').then((m) => m.PatientDetailComponent),
  },
  {
    path: 'patients/:uid/edit',
    title: 'Edit Patient',
    loadComponent: () =>
      import('./patient-form.component').then((m) => m.PatientFormComponent),
  },
];
