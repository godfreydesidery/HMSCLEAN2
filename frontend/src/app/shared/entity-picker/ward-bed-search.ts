import { inject, Injectable } from '@angular/core';
import { combineLatest, map, Observable, of, shareReplay, switchMap } from 'rxjs';
import {
  WardBedControllerService,
  WardBedDto,
  WardControllerService,
  WardDto,
} from '../../api/generated';
import { EntityOption, EntitySearchFn } from './entity-option';

/**
 * Ward-bed search adapter for {@link EntityPickerComponent} — used by the admit form to pick a
 * bed WITHOUT typing a uid. Only EMPTY (available) beds are offered, since admission claims a free
 * bed. The bed-list endpoint has no server-side query, so this fetches beds + wards once (cached)
 * and filters client-side by the typed term against the bed number and ward name.
 *
 * Label = bed number; sublabel = ward name + status.
 */
@Injectable({ providedIn: 'root' })
export class WardBedSearch {
  private readonly beds  = inject(WardBedControllerService);
  private readonly wards = inject(WardControllerService);

  /** Cache the bed + ward lists for the session (refreshed on full reload). */
  private readonly data$: Observable<{ beds: WardBedDto[]; wardNames: Map<string, string> }> =
    combineLatest([this.beds.list22(), this.wards.list()]).pipe(
      map(([beds, wards]) => ({
        beds: beds ?? [],
        wardNames: new Map((wards ?? []).map((w: WardDto) => [w.uid ?? '', w.name ?? w.code ?? ''])),
      })),
      shareReplay({ bufferSize: 1, refCount: false }),
    );

  /** Bind-ready search function: only EMPTY beds, filtered by bed-no / ward-name substring. */
  readonly searchFn: EntitySearchFn = (term: string): Observable<EntityOption[]> =>
    this.data$.pipe(
      switchMap(({ beds, wardNames }) => {
        const t = term.trim().toLowerCase();
        const options = beds
          .filter((b) => (b.status ?? '').toUpperCase() === 'EMPTY' && b.active !== false)
          .filter((b) => {
            const wardName = wardNames.get(b.wardUid ?? '') ?? '';
            return (
              !t ||
              (b.no ?? '').toLowerCase().includes(t) ||
              wardName.toLowerCase().includes(t)
            );
          })
          .map((b) => this.toOption(b, wardNames))
          .slice(0, 15);
        return of(options);
      }),
    );

  private toOption(b: WardBedDto, wardNames: Map<string, string>): EntityOption {
    const wardName = wardNames.get(b.wardUid ?? '') ?? '';
    const sub = [wardName, b.status].filter((s) => !!s).join(' · ');
    return {
      uid: b.uid ?? '',
      label: b.no ? `Bed ${b.no}` : (b.uid ?? '—'),
      sublabel: sub || undefined,
    };
  }
}
