/**
 * Pure money-formatting utility for the billing bounded context.
 * Returns a string like "TZS 5,000.00".
 * Returns "—" when amount or currency is absent.
 */
export function formatMoney(
  amount: number | null | undefined,
  currency: string | null | undefined,
): string {
  if (amount === null || amount === undefined) {
    return '—';
  }
  const ccy = currency ?? 'TZS';
  const formatted = new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
  return `${ccy} ${formatted}`;
}
