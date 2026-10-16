interface Env { API: Fetcher }

// Forward the original request unchanged: the Worker derives the session cookie,
// same-origin check and WebAuthn RP ID from this request's URL and Origin header.
export const onRequest: PagesFunction<Env> = ({ request, env }) => env.API.fetch(request);
