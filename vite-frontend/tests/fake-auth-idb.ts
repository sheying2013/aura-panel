/** A deliberately small IDB test double; never substitutes for real multi-process browser tests. */
export function createAuthIdb(options: { manual?: boolean; autoGrants?: number; openError?: boolean; openThrows?: boolean; beforeOpenError?: () => void; openErrorAfter?: number; abortAfterAction?: boolean } = {}) {
  type Request = { onsuccess: (() => void) | null; onerror: (() => void) | null; onblocked?: (() => void) | null; result?: unknown };
  type Entry = { grant: () => void; aborted: boolean };
  const requests: Entry[] = [];
  let committed: unknown;
  let opens = 0;
  const factory = {
    __seed: (value: unknown) => { committed = value; },
    open: () => {
      if (options.openThrows) throw new Error('IDB unavailable');
      const shouldFail = options.openError || (options.openErrorAfter !== undefined && opens >= options.openErrorAfter);
      opens += 1;
      const request: Request = { onsuccess: null, onerror: null };
      queueMicrotask(() => {
        if (shouldFail) { options.beforeOpenError?.(); request.onerror?.(); return; }
        request.result = {
          objectStoreNames: { contains: () => true }, close: () => {},
          transaction: () => {
            let aborted = false;
            let staged = committed;
            let dirty = false;
            let entry: Entry | undefined;
            const tx = {
              onabort: null as null | (() => void), onerror: null as null | (() => void),
              oncomplete: null as null | (() => void),
              abort: () => {
                if (aborted) return;
                aborted = true;
                if (entry) entry.aborted = true;
                queueMicrotask(() => tx.onabort?.());
              },
              objectStore: () => ({
                put: (value: unknown, key: string) => {
                  const result: Request = { onsuccess: null, onerror: null };
                  if (key === 'committed-auth') { staged = value; dirty = true; return result; }
                  entry = {
                    aborted: false,
                    grant: () => {
                      // Intentionally allow an adversarial callback after abort.
                      result.onsuccess?.();
                      if (!aborted) setTimeout(() => {
                        if (aborted) return;
                        if (options.abortAfterAction) { tx.abort(); return; }
                        if (dirty) committed = staged;
                        tx.oncomplete?.();
                      }, 0);
                    },
                  };
                  requests.push(entry);
                  if (!options.manual || requests.length <= (options.autoGrants ?? 0)) queueMicrotask(() => entry?.grant());
                  return result;
                },
                get: (key: string) => {
                  const result: Request = { onsuccess: null, onerror: null };
                  queueMicrotask(() => {
                    if (aborted) return;
                    result.result = key === 'committed-auth' ? committed : undefined;
                    result.onsuccess?.();
                  });
                  return result;
                },
              }),
            };
            return tx;
          },
        };
        request.onsuccess?.();
      });
      return request;
    },
  } as unknown as IDBFactory;
  return { factory, requests, seed: (value: unknown) => { committed = value; }, read: () => committed };
}

export async function waitForIdbRequest(requests: readonly unknown[], count = 1): Promise<void> {
  for (let i = 0; i < 100 && requests.length < count; i++) await new Promise(resolve => setTimeout(resolve, 1));
  if (requests.length < count) throw new Error('IDB request not observed');
}
