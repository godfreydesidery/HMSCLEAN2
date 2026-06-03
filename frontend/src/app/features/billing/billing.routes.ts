import { Routes } from '@angular/router';
import { privilegeGuard } from '../../core/auth/privilege.guard';

export const BILLING_ROUTES: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'cashier',
  },
  {
    path: 'cashier',
    canActivate: [privilegeGuard],
    data: { privilege: 'BILL-A' },
    title: 'Cashier — Billing',
    loadComponent: () =>
      import('./cashier/cashier-workspace.component').then(
        (m) => m.CashierWorkspaceComponent,
      ),
  },
  {
    path: 'receipt/:uid',
    canActivate: [privilegeGuard],
    data: { privilege: 'BILL-A' },
    title: 'Receipt — Billing',
    loadComponent: () =>
      import('./receipt/receipt.component').then((m) => m.ReceiptComponent),
  },
  {
    path: 'reports/collections',
    canActivate: [privilegeGuard],
    data: { privilege: 'BILL-A' },
    title: 'Collections Report — Billing',
    loadComponent: () =>
      import('./reports/collections-report.component').then(
        (m) => m.CollectionsReportComponent,
      ),
  },
];
