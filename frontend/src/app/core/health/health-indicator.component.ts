import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpClient } from '@angular/common/http';
import { catchError, of, switchMap, timer } from 'rxjs';
import { environment } from '../../../environments/environment';

type HealthStatus = 'UP' | 'DOWN' | 'UNKNOWN';

@Component({
  selector: 'app-health-indicator',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="badge rounded-pill"
      [class.text-bg-success]="status() === 'UP'"
      [class.text-bg-danger]="status() === 'DOWN'"
      [class.text-bg-secondary]="status() === 'UNKNOWN'"
      [attr.aria-label]="'API status: ' + status()"
    >
      <span class="status-dot"></span>{{ status() }}
    </span>
  `,
  styles: [`
    .status-dot {
      display: inline-block;
      width: 0.5rem;
      height: 0.5rem;
      border-radius: 50%;
      background: currentColor;
      margin-right: 0.35rem;
      vertical-align: middle;
      opacity: 0.85;
    }
  `],
})
export class HealthIndicatorComponent implements OnInit {
  private readonly http       = inject(HttpClient);
  private readonly destroyRef = inject(DestroyRef);

  readonly status = signal<HealthStatus>('UNKNOWN');

  ngOnInit(): void {
    timer(0, 30_000)
      .pipe(
        switchMap(() =>
          this.http
            .get<{ status: string }>(
              `${environment.actuatorBaseUrl}/actuator/health`,
            )
            .pipe(
              // Swallow errors per-tick so the timer stream keeps running.
              catchError(() => of({ status: 'DOWN' })),
            ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res) => {
        this.status.set(res.status === 'UP' ? 'UP' : 'DOWN');
      });
  }
}
