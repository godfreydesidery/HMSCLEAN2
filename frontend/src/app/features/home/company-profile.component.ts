import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { CompanyProfileControllerService, CompanyProfileDto } from '../../api/generated';
import { extractProblem } from '../../core/error/problem-detail';

@Component({
  selector: 'app-company-profile',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container py-2">
      <div class="row justify-content-center">
        <div class="col-12 col-lg-8">
          @if (loading()) {
            <div class="d-flex justify-content-center py-5">
              <div class="spinner-border text-primary" role="status"
                   aria-label="Loading company profile">
                <span class="visually-hidden">Loading…</span>
              </div>
            </div>
          } @else if (error()) {
            <div class="alert alert-danger" role="alert">{{ error() }}</div>
          } @else {
            <div class="card shadow-sm">
              <div class="card-header bg-white">
                <h1 class="h4 mb-0">{{ profile()?.name ?? '—' }}</h1>
              </div>
              <div class="card-body">
                <dl class="row mb-0">
                  @if (profile()?.address) {
                    <dt class="col-sm-3">Address</dt>
                    <dd class="col-sm-9">{{ profile()?.address }}</dd>
                  }
                  @if (profile()?.phone) {
                    <dt class="col-sm-3">Phone</dt>
                    <dd class="col-sm-9">{{ profile()?.phone }}</dd>
                  }
                  @if (profile()?.email) {
                    <dt class="col-sm-3">Email</dt>
                    <dd class="col-sm-9">{{ profile()?.email }}</dd>
                  }
                </dl>
              </div>
            </div>
          }
        </div>
      </div>
    </div>
  `,
})
export class CompanyProfileComponent implements OnInit {
  private readonly companyProfileService = inject(CompanyProfileControllerService);

  readonly profile = signal<CompanyProfileDto | null>(null);
  readonly loading = signal(true);
  readonly error   = signal<string | null>(null);

  ngOnInit(): void {
    this.companyProfileService.current1().subscribe({
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
