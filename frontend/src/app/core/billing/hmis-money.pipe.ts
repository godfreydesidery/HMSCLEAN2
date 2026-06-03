import { Pipe, PipeTransform } from '@angular/core';
import { MoneyDto } from '../../api/generated';
import { formatMoney } from './format-money';

/**
 * Standalone pipe that formats a MoneyDto to a display string.
 * e.g. { amount: 5000, currency: 'TZS' } → "TZS 5,000.00"
 * null/undefined → "—"
 *
 * Usage: {{ bill.amount | hmisMoney }}
 */
@Pipe({
  name: 'hmisMoney',
  standalone: true,
  pure: true,
})
export class HmisMoneyPipe implements PipeTransform {
  transform(value: MoneyDto | null | undefined): string {
    if (!value) {
      return '—';
    }
    return formatMoney(value.amount, value.currency);
  }
}
