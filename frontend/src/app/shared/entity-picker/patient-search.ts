import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { PatientControllerService, PatientDto } from '../../api/generated';
import { EntityOption, EntitySearchFn } from './entity-option';

/**
 * Reusable patient search adapter for {@link EntityPickerComponent}.
 *
 * Wraps {@code PatientControllerService.search(query)} and maps each {@link PatientDto} to an
 * {@link EntityOption} whose label is the patient's full name and sublabel is the registration
 * number + phone. The picker captures the patient's {@code uid} on selection — the cashier (and
 * every other screen) no longer asks the user to type a 26-character ULID by hand.
 */
@Injectable({ providedIn: 'root' })
export class PatientSearch {
  private readonly patients = inject(PatientControllerService);

  /** A small page is enough for a typeahead dropdown. */
  private static readonly PAGE_SIZE = 10;

  /** Bind-ready search function: pass directly to {@code <app-entity-picker [searchFn]="...">}. */
  readonly searchFn: EntitySearchFn = (term: string): Observable<EntityOption[]> =>
    this.patients
      .search({ query: term, page: 0, size: PatientSearch.PAGE_SIZE })
      .pipe(map((res) => (res.content ?? []).map(PatientSearch.toOption)));

  private static toOption(p: PatientDto): EntityOption {
    const fullName = [p.firstName, p.middleName, p.lastName]
      .filter((part) => !!part && part.trim().length > 0)
      .join(' ')
      .trim();
    const subParts: string[] = [];
    if (p.no) subParts.push(p.no);
    if (p.phoneNo) subParts.push(p.phoneNo);
    return {
      uid: p.uid ?? '',
      label: fullName || (p.no ?? p.uid ?? '—'),
      sublabel: subParts.join(' · ') || undefined,
    };
  }
}
