import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CatalogRegistry } from './catalog-registry';

@Component({
  selector: 'app-masterdata-index',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <div class="container-fluid py-2">
      <h1 class="h4 mb-3">Master Data</h1>
      <div class="row g-3">
        @for (c of catalogs; track c.slug) {
          <div class="col-12 col-sm-6 col-lg-4 col-xl-3">
            <a class="card h-100 text-decoration-none text-body catalog-card"
               [routerLink]="['/masterdata', c.slug]">
              <div class="card-body d-flex align-items-start gap-3">
                <i class="bi {{ c.icon }} fs-3 text-primary"></i>
                <div>
                  <div class="fw-semibold">{{ c.title }}</div>
                  <div class="text-muted small">{{ c.blurb }}</div>
                </div>
              </div>
            </a>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    .catalog-card { transition: box-shadow .15s ease, transform .15s ease; }
    .catalog-card:hover { box-shadow: 0 .25rem .75rem rgba(0,0,0,.1); transform: translateY(-2px); }
  `],
})
export class MasterdataIndexComponent {
  private readonly registry = inject(CatalogRegistry);
  protected readonly catalogs = this.registry.all();
}
