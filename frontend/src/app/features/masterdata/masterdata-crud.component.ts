import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
  TemplateRef,
  viewChild,
} from '@angular/core';
import { ReactiveFormsModule, FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { NgbModal } from '@ng-bootstrap/ng-bootstrap';
import { Observable } from 'rxjs';
import { CatalogConfig, FieldDef } from './catalog-config';
import { CatalogRegistry } from './catalog-registry';
import { extractProblem } from '../../core/error/problem-detail';

/**
 * Generic masterdata catalog CRUD — renders a list table + an add/edit modal entirely from a
 * {@link CatalogConfig}. One component backs all ~26 admin catalogs; each catalog supplies a
 * config (fields + bound API closures). Keeps every catalog consistent (Bootstrap, ng-bootstrap
 * modal) without 26 hand-written components.
 */
@Component({
  selector: 'app-masterdata-crud',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RouterLink],
  template: `
    <div class="container-fluid py-2">
      <nav class="mb-3">
        <a routerLink="/masterdata" class="text-decoration-none"><i class="bi bi-arrow-left"></i> Master Data</a>
      </nav>

      <div class="d-flex justify-content-between align-items-center mb-3">
        <h1 class="h4 mb-0">{{ config().title }}</h1>
        <button class="btn btn-primary" (click)="openForm(null)">
          <i class="bi bi-plus-lg me-1"></i> Add
        </button>
      </div>

      @if (loading()) {
        <div class="text-center py-5"><div class="spinner-border text-primary" role="status"></div></div>
      } @else if (error()) {
        <div class="alert alert-danger">{{ error() }}</div>
      } @else if (rows().length === 0) {
        <div class="text-muted text-center py-5">No entries yet.</div>
      } @else {
        <div class="card">
          <div class="table-responsive">
            <table class="table table-hover align-middle mb-0">
              <thead class="table-light">
                <tr>
                  @for (f of listFields(); track f.key) { <th>{{ f.label }}</th> }
                  <th class="text-end">Action</th>
                </tr>
              </thead>
              <tbody>
                @for (row of rows(); track row['uid']) {
                  <tr>
                    @for (f of listFields(); track f.key) {
                      <td>
                        @if (f.type === 'checkbox') {
                          @if (row[f.key]) { <span class="badge text-bg-success">Yes</span> }
                          @else { <span class="badge text-bg-secondary">No</span> }
                        } @else {
                          {{ display(row, f) }}
                        }
                      </td>
                    }
                    <td class="text-end">
                      <button class="btn btn-sm btn-outline-secondary" (click)="openForm(row)">Edit</button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      }
    </div>

    <!-- Add/Edit modal -->
    <ng-template #formModal let-modal>
      <div class="modal-header">
        <h5 class="modal-title">{{ editing() ? 'Edit' : 'Add' }} — {{ config().title }}</h5>
        <button type="button" class="btn-close" (click)="modal.dismiss()"></button>
      </div>
      <div class="modal-body">
        <form [formGroup]="form">
          @for (f of config().fields; track f.key) {
            <div class="mb-3">
              @if (f.type === 'checkbox') {
                <div class="form-check">
                  <input type="checkbox" class="form-check-input" [id]="f.key" [formControlName]="f.key" />
                  <label class="form-check-label" [for]="f.key">{{ f.label }}</label>
                </div>
              } @else {
                <label class="form-label" [for]="f.key">{{ f.label }}{{ f.required ? ' *' : '' }}</label>
                @switch (f.type) {
                  @case ('textarea') {
                    <textarea class="form-control" [id]="f.key" [formControlName]="f.key" rows="2"
                              [class.is-invalid]="invalid(f.key)"></textarea>
                  }
                  @case ('number') {
                    <input type="number" step="0.01" class="form-control" [id]="f.key" [formControlName]="f.key"
                           [class.is-invalid]="invalid(f.key)" />
                  }
                  @case ('select') {
                    <select class="form-select" [id]="f.key" [formControlName]="f.key"
                            [class.is-invalid]="invalid(f.key)">
                      <option value="">— select —</option>
                      @for (o of optionsFor(f.key); track o.value) { <option [value]="o.value">{{ o.label }}</option> }
                    </select>
                  }
                  @default {
                    <input type="text" class="form-control" [id]="f.key" [formControlName]="f.key"
                           [class.is-invalid]="invalid(f.key)" />
                  }
                }
                @if (invalid(f.key)) { <div class="invalid-feedback">{{ f.label }} is required.</div> }
              }
            </div>
          }
          @if (formError()) { <div class="alert alert-danger py-2" role="alert">{{ formError() }}</div> }
        </form>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" (click)="modal.dismiss()">Cancel</button>
        <button class="btn btn-primary" (click)="save(modal)" [disabled]="saving() || form.invalid">
          @if (saving()) { <span class="spinner-border spinner-border-sm me-2"></span> Saving… }
          @else { Save }
        </button>
      </div>
    </ng-template>
  `,
})
export class MasterdataCrudComponent implements OnInit {
  private readonly fb       = inject(FormBuilder);
  private readonly modal    = inject(NgbModal);
  private readonly route    = inject(ActivatedRoute);
  private readonly router   = inject(Router);
  private readonly registry = inject(CatalogRegistry);

  /** Resolved from the route :slug param. */
  readonly config = signal<CatalogConfig>(null as unknown as CatalogConfig);

  protected readonly formModal = viewChild.required<TemplateRef<unknown>>('formModal');

  protected readonly rows    = signal<Record<string, unknown>[]>([]);
  protected readonly loading = signal(true);
  protected readonly error   = signal<string | null>(null);
  protected readonly editing = signal<Record<string, unknown> | null>(null);
  protected readonly saving  = signal(false);
  protected readonly formError = signal<string | null>(null);

  private readonly selectOptions = new Map<string, { value: string; label: string }[]>();
  form: FormGroup = this.fb.group({});

  ngOnInit(): void {
    const slug = this.route.snapshot.paramMap.get('slug') ?? '';
    const cfg = this.registry.get(slug);
    if (!cfg) {
      void this.router.navigate(['/masterdata']);
      return;
    }
    this.config.set(cfg);
    this.init();
  }

  /** Preload select options + load the list. */
  private init(): void {
    // Preload any select option sources.
    for (const f of this.config().fields) {
      if (f.type === 'select' && f.optionsFrom) {
        f.optionsFrom().subscribe((opts) => this.selectOptions.set(f.key, opts));
      } else if (f.type === 'select' && f.options) {
        this.selectOptions.set(f.key, [...f.options]);
      }
    }
    this.load();
  }

  protected listFields(): FieldDef[] {
    return this.config().fields.filter((f) => f.inList !== false);
  }

  protected display(row: Record<string, unknown>, f: FieldDef): string {
    const v = row[f.key];
    if (v === null || v === undefined || v === '') return '—';
    if (f.type === 'select') {
      const opt = this.selectOptions.get(f.key)?.find((o) => o.value === v);
      return opt?.label ?? String(v);
    }
    return String(v);
  }

  protected optionsFor(key: string): { value: string; label: string }[] {
    return this.selectOptions.get(key) ?? [];
  }

  protected invalid(key: string): boolean {
    const c = this.form.get(key);
    return !!c && c.invalid && c.touched;
  }

  private load(): void {
    this.loading.set(true);
    this.error.set(null);
    (this.config().list() as Observable<Record<string, unknown>[]>).subscribe({
      next: (data) => { this.rows.set(data ?? []); this.loading.set(false); },
      error: (err: unknown) => {
        this.loading.set(false);
        const p = extractProblem(err);
        this.error.set(p.status === 403 ? 'You do not have permission to view this catalog.' : 'Failed to load entries.');
      },
    });
  }

  protected openForm(row: Record<string, unknown> | null): void {
    this.editing.set(row);
    this.formError.set(null);
    const group: Record<string, unknown> = {};
    for (const f of this.config().fields) {
      const initial = row ? (row[f.key] ?? (f.type === 'checkbox' ? false : '')) : (f.type === 'checkbox' ? true : '');
      group[f.key] = f.required ? [initial, Validators.required] : [initial];
    }
    this.form = this.fb.group(group);
    this.modal.open(this.formModal(), { size: 'lg' });
  }

  protected save(modalRef: { close: () => void }): void {
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    this.saving.set(true);
    this.formError.set(null);
    const body = this.form.getRawValue();
    const editing = this.editing();
    const op = editing
      ? this.config().update(editing['uid'] as string, body)
      : this.config().create(body);
    op.subscribe({
      next: () => { this.saving.set(false); modalRef.close(); this.load(); },
      error: (err: unknown) => {
        this.saving.set(false);
        const p = extractProblem(err);
        this.formError.set(p.title ?? (p.status === 409 ? 'A duplicate entry already exists.' : 'Failed to save.'));
      },
    });
  }
}
