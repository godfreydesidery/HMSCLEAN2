import { HttpErrorResponse } from '@angular/common/http';

export interface ExtractedProblem {
  code?:   string;
  type?:   string;
  title?:  string;
  status?: number;
}

/**
 * Extracts structured RFC-7807 ProblemDetail fields from an unknown error.
 * Components switch on `code`, `type`, or `status` for dispatch — never on
 * human-readable message text.
 */
export function extractProblem(err: unknown): ExtractedProblem {
  if (!(err instanceof HttpErrorResponse)) {
    return {};
  }

  const status = err.status;

  // err.error may be null, a string (plain-text response), or an object.
  const body = err.error;
  if (body === null || body === undefined || typeof body === 'string') {
    return { status };
  }

  if (typeof body !== 'object') {
    return { status };
  }

  const raw = body as Record<string, unknown>;

  const code  = typeof raw['code']  === 'string' ? raw['code']  : undefined;
  const type  = typeof raw['type']  === 'string' ? raw['type']  : undefined;
  const title = typeof raw['title'] === 'string' ? raw['title'] : undefined;

  return { code, type, title, status };
}
