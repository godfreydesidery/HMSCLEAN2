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
import { MatChipsModule } from '@angular/material/chips';
import { environment } from '../../../environments/environment';

type HealthStatus = 'UP' | 'DOWN' | 'UNKNOWN';

@Component({
  selector: 'app-health-indicator',
  standalone: true,
  imports: [MatChipsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <mat-chip
      [class]="'health-chip health-chip--' + status().toLowerCase()"
      disableRipple
      [attr.aria-label]="'API status: ' + status()"
    >
      {{ status() }}
    </mat-chip>
  `,
  styles: [`
    .health-chip--up      { background-color: #2e7d32; color: #fff; }
    .health-chip--down    { background-color: #c62828; color: #fff; }
    .health-chip--unknown { background-color: #757575; color: #fff; }
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
