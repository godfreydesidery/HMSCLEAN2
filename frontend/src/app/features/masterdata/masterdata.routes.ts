import { Routes } from '@angular/router';

/**
 * Master Data feature routes — a catalog index (grid of cards) + one generic CRUD screen per
 * catalog slug. All ~14 standard catalogs share the same MasterdataCrudComponent driven by config.
 */
export const MASTERDATA_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'Master Data',
    loadComponent: () =>
      import('./masterdata-index.component').then((m) => m.MasterdataIndexComponent),
  },
  {
    path: ':slug',
    title: 'Master Data',
    loadComponent: () =>
      import('./masterdata-crud.component').then((m) => m.MasterdataCrudComponent),
  },
];
