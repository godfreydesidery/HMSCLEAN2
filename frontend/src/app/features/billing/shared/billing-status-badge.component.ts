import { ChangeDetectionStrategy, Component, Input } from '@angular/core';

interface BadgeConfig {
  label: string;
  cssClass: string;
}

const STATUS_MAP: Record<string, BadgeConfig> = {
  PENDING:   { label: 'Pending',   cssClass: 'badge--pending'   },
  APPROVED:  { label: 'Approved',  cssClass: 'badge--approved'  },
  PAID:      { label: 'Paid',      cssClass: 'badge--paid'      },
  UNPAID:    { label: 'Unpaid',    cssClass: 'badge--unpaid'     },
  COVERED:   { label: 'Covered',   cssClass: 'badge--covered'   },
  VERIFIED:  { label: 'Verified',  cssClass: 'badge--verified'  },
  CANCELED:  { label: 'Cancelled', cssClass: 'badge--cancelled' },
  CANCELLED: { label: 'Cancelled', cssClass: 'badge--cancelled' },
  NONE:      { label: 'None',      cssClass: 'badge--none'      },
};

@Component({
  selector: 'app-billing-status-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [],
  template: `
    <span
      [class]="'badge ' + config.cssClass"
      [attr.aria-label]="'Status: ' + config.label"
    >{{ config.label }}</span>
  `,
  styles: [`
    .badge {
      display: inline-block;
      border-radius: 12px;
      padding: 2px 10px;
      font-size: 0.75rem;
      font-weight: 500;
      line-height: 1.6;
      white-space: nowrap;
    }
    .badge--pending   { background: var(--mat-sys-secondary-container, #e8def8); color: var(--mat-sys-on-secondary-container, #1d1b20); }
    .badge--approved  { background: var(--mat-sys-primary-container, #d0bcff);   color: var(--mat-sys-on-primary-container, #21005d); }
    .badge--paid      { background: #c8e6c9; color: #1b5e20; }
    .badge--unpaid    { background: var(--mat-sys-error-container, #f9dedc);     color: var(--mat-sys-on-error-container, #410002); }
    .badge--covered   { background: var(--mat-sys-tertiary-container, #ffd8e4);  color: var(--mat-sys-on-tertiary-container, #31111d); }
    .badge--verified  { background: var(--mat-sys-primary-container, #d0bcff);   color: var(--mat-sys-on-primary-container, #21005d); }
    .badge--cancelled { background: #f5f5f5; color: #616161; }
    .badge--none      { background: #f5f5f5; color: #616161; }
  `],
})
export class BillingStatusBadgeComponent {
  @Input() status = '';

  get config(): BadgeConfig {
    return STATUS_MAP[this.status?.toUpperCase()] ?? { label: this.status || '—', cssClass: 'badge--none' };
  }
}
