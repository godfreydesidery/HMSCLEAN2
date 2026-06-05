export const environment = {
  production: false,
  // Empty base = same-origin relative requests. The Angular dev-server proxy
  // (proxy.conf.json) forwards /api, /actuator, /v3 to the backend on :8080, so
  // there is NO CORS in dev and the app works on any dev-server port.
  // The generated client paths already include the /api/v1 prefix — do NOT add it here.
  apiBaseUrl: '',
  actuatorBaseUrl: '',
} as const;
