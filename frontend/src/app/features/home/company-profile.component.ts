import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { CompanyProfile, DefaultService } from '../../api/generated';
import { extractProblem } from '../../core/error/problem-detail';

@Component({
  selector: 'app-company-profile',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatCardModule, MatProgressSpinnerModule],
  template: `
    <div class="profile-wrapper">

      @if (loading()) {
        <mat-spinner diameter="48" aria-label="Loading company profile"></mat-spinner>
      } @else if (error()) {
        <p class="error-msg" role="alert">{{ error() }}</p>
      } @else {
        <mat-card class="profile-card">
          <mat-card-header>
            <mat-card-title>{{ profile()?.name ?? '—' }}</mat-card-title>
            @if (profile()?.uid) {
              <mat-card-subtitle>UID: {{ profile()?.uid }}</mat-card-subtitle>
            }
          </mat-card-header>

          <mat-card-content>
            @if (profile()?.address) {
              <p><strong>Address:</strong> {{ profile()?.address }}</p>
            }
            @if (profile()?.phone) {
              <p><strong>Phone:</strong> {{ profile()?.phone }}</p>
            }
          </mat-card-content>
        </mat-card>
      }

    </div>
  `,
  styles: [`
    .profile-wrapper {
      display: flex;
      justify-content: center;
      align-items: flex-start;
      padding: 2rem 1rem;
    }
    .profile-card { width: 100%; max-width: 600px; }
    .error-msg    { color: #c62828; font-size: 0.875rem; }
    mat-card-content p { margin: 0.5rem 0; }
  `],
})
export class CompanyProfileComponent implements OnInit {
  private readonly defaultService = inject(DefaultService);

  readonly profile = signal<CompanyProfile | null>(null);
  readonly loading = signal(true);
  readonly error   = signal<string | null>(null);

  ngOnInit(): void {
    this.defaultService.getCompanyProfile().subscribe({
      next: (data) => {
        this.profile.set(data);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        const p = extractProblem(err);
        if (p.status === 403) {
          this.error.set('You lack ADMIN-ACCESS for the company profile.');
        } else if (p.status === 404) {
          this.error.set('Company profile not found.');
        } else {
          this.error.set('Failed to load company profile.');
        }
      },
    });
  }
}
