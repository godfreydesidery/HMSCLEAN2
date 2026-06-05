import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/** Maps an admission status (db-value) to a Bootstrap badge class + label. */
const STATUS_MAP: Record<string, { label: string; cls: string }> = {
  'PENDING':    { label: 'Pending',     cls: 'text-bg-warning' },
  'IN-PROCESS': { label: 'In Process',  cls: 'text-bg-success' },
  'STOPPED':    { label: 'Stopped',     cls: 'text-bg-secondary' },
  'HELD':       { label: 'Held',        cls: 'text-bg-info' },
  'SIGNED-OUT': { label: 'Signed Out',  cls: 'text-bg-dark' },
};

@Component({
  selector: 'app-admission-status-badge',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<span class="badge rounded-pill {{ config().cls }}">{{ config().label }}</span>`,
})
export class AdmissionStatusBadgeComponent {
  readonly status = input<string>('');

  protected readonly config = computed(() =>
    STATUS_MAP[this.status().toUpperCase()] ?? { label: this.status() || '—', cls: 'text-bg-light' },
  );
}
