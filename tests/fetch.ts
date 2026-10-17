import { vi } from 'vitest';

type Reply = Response | ((request: Request) => Response | Promise<Response>);

// A stubbed fetch skips the runtime's checks on request options, so each call is
// rebuilt as a real Request first: workerd rejects unsupported options (such as
// redirect: 'error') while constructing a Request, just as production fetch does.
// Replies are used in order and the last one repeats.
export function mockFetch(...replies: Reply[]) {
  const requests: Request[] = [];
  const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    const request = new Request(input, init);
    requests.push(request);
    const reply = replies.length > 1 ? replies.shift() : replies[0];
    if (!reply) throw new Error(`Unexpected fetch: ${request.url}`);
    return typeof reply === 'function' ? reply(request) : reply.clone();
  });
  return Object.assign(spy, { requests });
}
