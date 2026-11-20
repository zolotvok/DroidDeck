const assert = require('assert');
const vm = require('vm');
const source = require('fs').readFileSync(process.argv[2], 'utf8');
const calls = [], requests = [], errors = [], timers = new Set();
let changed;
const apps = { RunGame(...args) { calls.push({receiver: this, args}); return 'native'; } };
const openvr = {
  RegisterForVRModeChange(callback) { changed = callback; callback(false); return {unregister() {}}; },
  StartVR(...args) { return args; },
};
const overview = new Map([
  ['611670', {vr_only: true}],
  ['620980', {vr_supported: true}],
  ['730', {vr_only: false, vr_supported: false}],
  ['18446744069414584320', {BSupportsVR() { return true; }}],
]);
const context = {
  SteamClient: {Apps: apps, OpenVR: openvr},
  appStore: {GetAppOverviewByGameID: id => overview.get(id)},
  droiddeckLaunchSteamVr: id => requests.push(id),
  setTimeout(callback, ms) { assert.strictEqual(ms, 600000); timers.add(callback); return callback; },
  clearTimeout: timer => timers.delete(timer),
  console: {error: message => errors.push(message)},
};
context.window = context;
vm.createContext(context);
assert.strictEqual(vm.runInContext(source, context), 'installed');
assert.strictEqual(vm.runInContext(source, context), 'present');

assert.strictEqual(apps.RunGame(730, '', -1, 100), 'native');
assert.deepStrictEqual(requests, []);
assert.strictEqual(calls[0].receiver, apps);

const receiver = {};
apps.RunGame.call(receiver, '611670', 'custom args', 2, 100);
apps.RunGame('611670', 'duplicate', -1, 100);
assert.strictEqual(apps.RunGame(620980, '', -1, 100), 'native');
assert.deepStrictEqual(requests, ['611670']);
assert.strictEqual(calls.length, 2);
assert.deepStrictEqual(calls[1].args, [620980, '', -1, 100]);
changed(false);
assert.strictEqual(calls.length, 2);
changed(true);
assert.strictEqual(calls.length, 3);
assert.strictEqual(calls[2].receiver, receiver);
assert.deepStrictEqual(calls[2].args, ['611670', 'custom args', 2, 100]);
assert.strictEqual(timers.size, 0);
changed(true);
assert.strictEqual(calls.length, 3);

changed(false);
assert.strictEqual(apps.RunGame('18446744069414584320', '', -1, 100), 'native');
assert.deepStrictEqual(calls[3].args, ['18446744069414584320', '', -1, 100]);
assert.deepStrictEqual(requests, ['611670']);
assert.strictEqual(timers.size, 0);
changed(true);

apps.RunGame('250820', '', -1, 100);
openvr.StartVR(true);
assert.deepStrictEqual(requests.slice(-2), ['250820', '250820']);
assert.strictEqual(calls.length, 4);

changed(false);
apps.RunGame('611670', '', -1, 100);
for (const timeout of [...timers]) { timers.delete(timeout); timeout(); }
assert.strictEqual(context.__droiddeckSteamVr.pending.size, 0);
assert.strictEqual(errors.length, 1);
changed(true);
assert.strictEqual(calls.length, 4);
apps.RunGame('611670', '', -1, 100);
assert.strictEqual(calls.length, 5);
assert.strictEqual(context.__droiddeckSteamVr.lastError, null);

changed(false);
context.droiddeckLaunchSteamVr = () => { throw new Error('bridge disconnected'); };
assert.throws(() => apps.RunGame('611670', '', -1, 100), /bridge disconnected/);
assert.strictEqual(context.__droiddeckSteamVr.pending.size, 0);
assert.strictEqual(timers.size, 0);
delete context.droiddeckLaunchSteamVr;
assert.strictEqual(apps.RunGame('611670', '', -1, 100), 'native');
assert.strictEqual(apps.RunGame('250820', '', -1, 100), 'native');
assert.deepStrictEqual(openvr.StartVR(true), [true]);
assert.strictEqual(calls.length, 7);

// SteamVR died while a VR-only launch waited: the helper's failure drops the queue and its timer
context.droiddeckLaunchSteamVr = id => requests.push(id);
changed(false);
apps.RunGame('611670', '', -1, 100);
assert.strictEqual(context.__droiddeckSteamVr.pending.size, 1);
assert.strictEqual(timers.size, 1);
assert.deepStrictEqual(Array.from(context.__droiddeckSteamVr.fail('SteamVR did not start')), ['611670']);
assert.strictEqual(context.__droiddeckSteamVr.pending.size, 0);
assert.strictEqual(timers.size, 0);
assert.strictEqual(context.__droiddeckSteamVr.lastError, 'SteamVR did not start');
assert.strictEqual(errors[errors.length - 1], 'DroidDeck: SteamVR did not start');
changed(true);
assert.strictEqual(calls.length, 7);
assert.strictEqual(context.__droiddeckSteamVr.fail('again').length, 0);

// a game queued in the same round as the failure stays queued for the retry
changed(false);
apps.RunGame('611670', '', -1, 100);
apps.RunGame('1234567', '', -1, 100);
overview.set('1234567', {vr_only: true});
apps.RunGame('1234567', '', -1, 100);
assert.deepStrictEqual(Array.from(context.__droiddeckSteamVr.fail('SteamVR did not start', ['1234567'])), ['611670']);
assert.deepStrictEqual(Array.from(context.__droiddeckSteamVr.pending.keys()), ['1234567']);
assert.strictEqual(timers.size, 1);
