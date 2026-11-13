import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { readFileSync } from 'fs';
import firebase from 'firebase/compat/app';
import 'firebase/compat/firestore';

const env = await initializeTestEnvironment({
  projectId: 'demo-gc',
  firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'), host: '127.0.0.1', port: 8085 },
});
const ts = (ms) => firebase.firestore.Timestamp.fromMillis(Date.now() + ms);
let failures = 0;
async function check(name, p) {
  try { await p; console.log('ok   ', name); } catch (e) { failures++; console.log('FAIL ', name, e.message); }
}
async function seed() {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.doc('devices/VICTIM').set({ deviceUid: 'phone', caregiverUids: ['cg1', 'cg2'] });
    await db.doc('devices/ATTACK').set({ deviceUid: 'mallory', caregiverUids: [] });
    await db.doc('pairing_codes/GOOD01').set({ deviceId: 'VICTIM', expiresAt: ts(600000), createdAt: ts(0) });
    await db.doc('pairing_codes/EVIL01').set({ deviceId: 'ATTACK', expiresAt: ts(600000), createdAt: ts(0) });
  });
}
const as = (uid) => env.authenticatedContext(uid).firestore();
const U = firebase.firestore.FieldValue;

await seed();
await check('attacker cannot re-point own code at her device',
  assertFails(as('mallory').doc('pairing_codes/EVIL01').update({ deviceId: 'VICTIM' })));
await check('owner may refresh own code within the cap',
  assertSucceeds(as('mallory').doc('pairing_codes/EVIL01').update({ expiresAt: ts(600000) })));
await check('owner may not stretch own code to a year',
  assertFails(as('mallory').doc('pairing_codes/EVIL01').update({ expiresAt: ts(365 * 864e5) })));
await check('phone may create a ten-minute code',
  assertSucceeds(as('phone').doc('pairing_codes/NEW001').set({ deviceId: 'VICTIM', expiresAt: ts(600000), createdAt: ts(0) })));
await check('nobody may create a code that lives a year',
  assertFails(as('mallory').doc('pairing_codes/SQUAT1').set({ deviceId: 'ATTACK', expiresAt: ts(365 * 864e5), createdAt: ts(0) })));
await check('a code may not carry extra fields',
  assertFails(as('mallory').doc('pairing_codes/SQUAT2').set({ deviceId: 'ATTACK', expiresAt: ts(600000), createdAt: ts(0), x: 1 })));
await check('phone cannot overwrite somebody else\'s code',
  assertFails(as('phone').doc('pairing_codes/EVIL01').set({ deviceId: 'VICTIM', expiresAt: ts(600000), createdAt: ts(0) })));
await check('phone may delete its own code',
  assertSucceeds(as('phone').doc('pairing_codes/GOOD01').delete()));

await seed();
await check('caregiver claims with a live code',
  assertSucceeds(as('newcg').doc('devices/VICTIM').update({ caregiverUids: U.arrayUnion('newcg'), pairingCode: 'GOOD01' })));
await check('claiming again is still allowed (idempotent)',
  assertSucceeds(as('newcg').doc('devices/VICTIM').update({ caregiverUids: U.arrayUnion('newcg'), pairingCode: 'GOOD01' })));
await check('claim with a code for another device fails',
  assertFails(as('mallory').doc('devices/VICTIM').update({ caregiverUids: U.arrayUnion('mallory'), pairingCode: 'EVIL01' })));

await seed();
await check('caregiver may remove themselves',
  assertSucceeds(as('cg1').doc('devices/VICTIM').update({ caregiverUids: U.arrayRemove('cg1') })));
await seed();
await check('caregiver may not remove somebody else',
  assertFails(as('cg1').doc('devices/VICTIM').update({ caregiverUids: U.arrayRemove('cg2') })));
await check('caregiver may not remove everybody',
  assertFails(as('cg1').doc('devices/VICTIM').update({ caregiverUids: [] })));
await check('caregiver may not swap themselves for somebody',
  assertFails(as('cg1').doc('devices/VICTIM').update({ caregiverUids: ['cg2', 'mallory'] })));
await check('a stranger may not "remove themselves"',
  assertFails(as('mallory').doc('devices/VICTIM').update({ caregiverUids: ['cg1', 'cg2'] })));
await check('phone may clear every caregiver',
  assertSucceeds(as('phone').doc('devices/VICTIM').update({ caregiverUids: [] })));

await seed();
await check('nobody writes the outbox any more',
  assertFails(as('phone').collection('devices/VICTIM/outbox').add({ status: 'pending', to: 'X', transport: 'threema', kind: 'text' })));

await env.cleanup();
console.log(failures ? `${failures} FAILED` : 'all passed');
process.exit(failures ? 1 : 0);
