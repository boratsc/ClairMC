import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const svc = require('../DOC/src/mc-bridge/services/HmacService.js')

const secret = process.argv[2] ?? 'test_secret'

const msg = {
  type: 'response',
  id: 'req_example',
  serverId: 'serwer-test',
  ts: 1738976401,
  ok: true,
  payload: { discordUserId: '1324396184411832370' },
}

const canonical = svc.canonicalStringify((() => {
  const copy = { ...msg }
  delete copy.signature
  if (copy.payload === undefined) copy.payload = {}
  return copy
})())

const sig = svc.signMessage(secret, msg)

console.log('canonical=' + canonical)
console.log('signature=' + sig)
