/**
 * Single source of truth for the application's primary navigation.
 *
 * Each item maps a module to its route and the privilege code that gates it
 * (rendered through the `*appCan` directive — privilege codes only, never role
 * names). Routes whose feature module is not yet built are marked `enabled:false`
 * so the shell can show them as "coming soon" rather than 404.
 */
export interface NavItem {
  /** Display label in the sidebar. */
  readonly label: string;
  /** Router path (absolute, no leading slash needed — used with routerLink). */
  readonly route: string;
  /** Bootstrap-icons class (e.g. 'bi-people'); purely decorative. */
  readonly icon: string;
  /** Privilege code that gates visibility (via *appCan). */
  readonly privilege: string;
  /** False until the feature module exists on disk + is wired in app.routes. */
  readonly enabled: boolean;
}

export const NAV_ITEMS: readonly NavItem[] = [
  { label: 'Registration', route: '/registration', icon: 'bi-person-plus',     privilege: 'PATIENT-ALL',            enabled: false },
  { label: 'Outpatient',   route: '/outpatient',   icon: 'bi-clipboard-pulse', privilege: 'PATIENT-ALL',            enabled: false },
  { label: 'Inpatient',    route: '/inpatient',    icon: 'bi-hospital',        privilege: 'PATIENT-ALL',            enabled: true  },
  { label: 'Pharmacy',     route: '/pharmacy',     icon: 'bi-capsule',         privilege: 'PHARMACY_ORDER-ALL',     enabled: false },
  { label: 'Laboratory',   route: '/laboratory',   icon: 'bi-eyedropper',      privilege: 'PATIENT-ALL',            enabled: false },
  { label: 'Radiology',    route: '/radiology',    icon: 'bi-radioactive',     privilege: 'PATIENT-ALL',            enabled: false },
  { label: 'Procedures',   route: '/procedures',   icon: 'bi-scissors',        privilege: 'PATIENT-ALL',            enabled: false },
  { label: 'Billing',      route: '/billing/cashier', icon: 'bi-cash-coin',    privilege: 'BILL-A',                 enabled: true  },
  { label: 'Inventory',    route: '/inventory',    icon: 'bi-box-seam',        privilege: 'LOCAL_PURCHASE_ORDER-ALL', enabled: false },
  { label: 'Payroll',      route: '/payroll',      icon: 'bi-wallet2',         privilege: 'PAYROLL-ALL',            enabled: false },
  { label: 'Master Data',  route: '/masterdata',   icon: 'bi-database',        privilege: 'ADMIN-ACCESS',           enabled: false },
  { label: 'Reports',      route: '/reports',      icon: 'bi-graph-up',        privilege: 'ADMIN-ACCESS',           enabled: false },
  { label: 'Users & Roles', route: '/iam',         icon: 'bi-shield-lock',     privilege: 'USER-ALL',               enabled: false },
];
