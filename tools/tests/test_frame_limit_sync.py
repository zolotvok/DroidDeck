from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
LIVE = runpy.run_path(str(BIN / "droiddeck-steam-compat"))

HARNESS = r"""
const assert = require('assert');
globalThis.window = globalThis;
globalThis.btoa = text => Buffer.from(text, 'binary').toString('base64');
let timers = new Map(), nextTimer = 0;
globalThis.setTimeout = (callback, ms) => { timers.set(++nextTimer, callback); return nextTimer; };
globalThis.clearTimeout = id => timers.delete(id);
globalThis.setInterval = () => 0;
const fire = async () => {
  const pending = [...timers.values()]; timers = new Map();
  for (const callback of pending) await callback();
};
let rates = [36, 48, 72, 144];
const notify = [];
const service = {
  GetState: async () => ({ BSuccess: () => true,
    Body: () => ({ toObject: () => ({ state: { active_display_info: { supported_frame_rates: rates } } }) }) }),
  RegisterForNotifyStateChanged(callback) { notify.push(callback); return {}; },
  SetBlurParams() {},
};
const writes = [];
globalThis.settingsStore = { clientSettings: {
  gamescope_enable_app_target_framerate: true, gamescope_app_target_framerate: 120, gamescope_disable_framelimit: false } };
const varints = bytes => { const out = []; let value = 0, shift = 0;
  for (const b of bytes) { value += (b & 127) * 2 ** shift; shift += 7; if (b < 128) { out.push(value); value = 0; shift = 0; } }
  return out; };
globalThis.SteamClient = { Settings: { SetSetting(encoded) {
  const [key, value] = varints([...Buffer.from(encoded, 'base64')]);
  assert.strictEqual(key, 21002 * 8);
  writes.push(value);
  settingsStore.clientSettings.gamescope_app_target_framerate = value;
} } };
const modules = {
  1: { source: () => 'RegisterForNotifyStateChanged only', exports: { other: { GetState() {} } } },
  2: { source: () => 'n.MX.SetBlurParams(); n.MX.RegisterForNotifyStateChanged()', exports: { hooks: () => {} } },
  3: { source: () => 'SetBlurParams RegisterForNotifyStateChanged GetState', exports: { MX: service } },
};
const webpack = modules => { const require = id => modules[id].exports;
  require.m = Object.fromEntries(Object.entries(modules).map(([id, m]) => [id, { toString: m.source }]));
  return require; };
globalThis.webpackChunksteamui = { push([, , callback]) { callback(webpack(modules)); } };
const script = require('fs').readFileSync(process.argv[2], 'utf8');
const change = async next => { rates = next; notify.forEach(callback => callback()); };
(async () => {
  assert.strictEqual(eval(script), 'installed');
  assert.strictEqual(eval(script), 'present');
  await fire();
  assert.deepStrictEqual(writes, [144], 'a remembered limit the panel does not offer moves to the highest value');
  await fire();
  assert.deepStrictEqual(writes, [144], 'unchanged choices are not written again');
  settingsStore.clientSettings.gamescope_app_target_framerate = 72;
  await change([24, 30, 40, 60, 120]);
  await fire();
  assert.deepStrictEqual(writes, [144, 120], '72 is not offered at 120 Hz');
  settingsStore.clientSettings.gamescope_app_target_framerate = 60;
  await change([12, 15, 20, 30, 60]);
  await fire();
  assert.deepStrictEqual(writes, [144, 120], '60 is still offered at 60 Hz and is kept');
  await change([18, 24, 36, 72]);
  await change([24, 30, 40, 60, 120]);
  await fire();
  assert.deepStrictEqual(writes, [144, 120], 'a passing rate during a mode switch is not acted on');
  settingsStore.clientSettings.gamescope_enable_app_target_framerate = false;
  settingsStore.clientSettings.gamescope_app_target_framerate = 72;
  await change([36, 48, 144]);
  await fire();
  assert.deepStrictEqual(writes, [144, 120], 'nothing changes while Steam has the frame limit turned off');
  settingsStore.clientSettings.gamescope_enable_app_target_framerate = true;
  window.__droiddeckFrameLimit.schedule();
  await fire();
  assert.deepStrictEqual(writes, [144, 120, 144], 'turning the limit on after a rate change moves an unoffered value');
  settingsStore.clientSettings.gamescope_app_target_framerate = 72;
  const setSetting = SteamClient.Settings.SetSetting;
  SteamClient.Settings.SetSetting = () => { throw new Error('busy'); };
  window.__droiddeckFrameLimit.schedule();
  await fire();
  SteamClient.Settings.SetSetting = setSetting;
  window.__droiddeckFrameLimit.schedule();
  await fire();
  assert.deepStrictEqual(writes, [144, 120, 144, 144], 'a failed write is tried again on the next check');
  settingsStore.clientSettings.gamescope_app_target_framerate = 72;
  SteamClient.Settings.SetSetting = () => Promise.reject(new Error('busy'));
  window.__droiddeckFrameLimit.schedule();
  await fire();
  SteamClient.Settings.SetSetting = setSetting;
  window.__droiddeckFrameLimit.schedule();
  await fire();
  assert.deepStrictEqual(writes, [144, 120, 144, 144, 144], 'a rejected write is caught and tried again');
  settingsStore.clientSettings.gamescope_app_target_framerate = 120;
  await change([24, 30, 40, 60, 120]);
  await fire();
  await change([12, 15, 20, 30, 60]);
  await fire();
  assert.deepStrictEqual(writes.slice(5), [60], 'a 60 Hz dip moves 120 to 60');
  await change([24, 30, 40, 60, 120]);
  await fire();
  assert.deepStrictEqual(writes.slice(5), [60, 120], 'back at 120 Hz the limit the helper replaced comes back');
  await change([30, 45, 90]);
  await fire();
  await change([24, 30, 40, 60, 120]);
  await fire();
  assert.deepStrictEqual(writes.slice(5), [60, 120, 90, 120], 'the same through a 90 Hz phase');
  settingsStore.clientSettings.gamescope_app_target_framerate = 60;
  await change([12, 15, 20, 30, 60]);
  await change([24, 30, 40, 60, 120]);
  await fire();
  assert.deepStrictEqual(writes.slice(5), [60, 120, 90, 120], 'a 60 the user chose stays 60');
  delete window.__droiddeckFrameLimit;
  webpackChunksteamui.push = ([, , callback]) => callback(webpack({ 1: modules[1], 2: modules[2] }));
  assert.strictEqual(eval(script), 'unavailable');
})().catch(error => { console.error(error); process.exit(1); });
"""


UNLOCK_HARNESS = r"""
const assert = require('assert');
globalThis.window = globalThis;
globalThis.btoa = text => Buffer.from(text, 'binary').toString('base64');
let timers = new Map(), nextTimer = 0;
globalThis.setTimeout = (callback, ms) => { timers.set(++nextTimer, callback); return nextTimer; };
globalThis.clearTimeout = id => timers.delete(id);
globalThis.setInterval = () => 0;
const fire = async () => {
  const pending = [...timers.values()]; timers = new Map();
  for (const callback of pending) await callback();
};
const service = {
  GetState: async () => ({ BSuccess: () => true,
    Body: () => ({ toObject: () => ({ state: { active_display_info: { supported_frame_rates: [30, 45, 90] } } }) }) }),
  RegisterForNotifyStateChanged() { return {}; },
  SetBlurParams() {},
};
const cs = { gamescope_enable_app_target_framerate: true, gamescope_app_target_framerate: 30 };
globalThis.settingsStore = { clientSettings: cs };
const varints = bytes => { const out = []; let value = 0, shift = 0;
  for (const b of bytes) { value += (b & 127) * 2 ** shift; shift += 7; if (b < 128) { out.push(value); value = 0; shift = 0; } }
  return out; };
let writes = [], sticks = true;
globalThis.SteamClient = { Settings: { SetSetting(encoded) {
  const [key, value] = varints([...Buffer.from(encoded, 'base64')]);
  writes.push([key / 8, value]);
  if (key === 21002 * 8) cs.gamescope_app_target_framerate = value;
  else if (key === 21003 * 8 && sticks) cs.gamescope_enable_app_target_framerate = !!value;
} } };
const table = process.argv[3];
const modules = {
  1: { source: () => table, exports: {} },
  2: { source: () => 'SetBlurParams RegisterForNotifyStateChanged GetState', exports: { MX: service } },
};
const webpack = modules => { const require = id => modules[id].exports;
  require.m = Object.fromEntries(Object.entries(modules).map(([id, m]) => [id, { toString: m.source }]));
  return require; };
globalThis.webpackChunksteamui = { push([, , callback]) { callback(webpack(modules)); } };
const script = require('fs').readFileSync(process.argv[2], 'utf8');
const check = async () => { window.__droiddeckFrameLimit.schedule(); await fire(); };
(async () => {
  assert.strictEqual(eval(script), 'installed');
  const mode = process.argv[4];
  if (mode === 'unlock') {
    await fire();
    assert.deepStrictEqual(writes, [[21003, 0]], 'an offered 30 is switched off, not kept');
    assert.strictEqual(cs.gamescope_enable_app_target_framerate, false);
    await check();
    assert.deepStrictEqual(writes, [[21003, 0]], 'nothing is written while the limit is off');
    cs.gamescope_enable_app_target_framerate = true;
    await check();
    assert.deepStrictEqual(writes, [[21003, 0], [21003, 0]], 'Steam turning it on again is undone');
    await check();
    sticks = false; writes = [];
    cs.gamescope_enable_app_target_framerate = true; cs.gamescope_app_target_framerate = 72;
    for (let i = 0; i < 4; i++) await check();
    assert.deepStrictEqual(writes, [[21003, 0], [21003, 0], [21003, 0], [21002, 90]],
      'after three writes that do not stick it falls back to an offered value');
    await check();
    assert.deepStrictEqual(writes.length, 4, 'and stops writing');
  } else {
    await fire();
    cs.gamescope_app_target_framerate = 72;
    await check();
    assert.deepStrictEqual(writes, [[21002, 90]], mode + ': the limit stays on; only an unoffered value moves');
    assert.strictEqual(cs.gamescope_enable_app_target_framerate, true);
  }
})().catch(error => { console.error(error); process.exit(1); });
"""
TABLE = 'a={gamescope_app_target_framerate:{n:21002,br:o.d.readUint32},gamescope_enable_app_target_framerate:{n:21003,br:o.d.readBool}}'


class FrameLimitSyncTest(unittest.TestCase):
    def run_unlock(self, unlock, table, mode):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "frame_limit.js"
            script.write_text(LIVE["frame_limit_js"](unlock))
            test = Path(directory) / "test.js"
            test.write_text(UNLOCK_HARNESS)
            result = subprocess.run(["node", str(test), str(script), table, mode], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_unlock_switches_the_limit_off_and_keeps_it_off(self):
        self.run_unlock(True, TABLE, "unlock")

    def test_unlock_off_respects_the_users_limit(self):
        self.run_unlock(False, TABLE, "kept")

    def test_unlock_writes_nothing_when_the_enable_field_is_not_unique(self):
        self.run_unlock(True, "e.gamescope_enable_app_target_framerate && f()", "no field")
        self.run_unlock(True, TABLE + ";b={gamescope_enable_app_target_framerate:{n:21004}}", "two fields")

    def test_pref_reaches_the_script(self):
        with mock.patch.dict("os.environ", {}, clear=False) as env:
            env.pop("BL_UNLOCK_FRAME_LIMIT", None)
            self.assertTrue(LIVE["unlock_frame_limit"]())
            env["BL_UNLOCK_FRAME_LIMIT"] = "0"
            self.assertFalse(LIVE["unlock_frame_limit"]())
            env["BL_UNLOCK_FRAME_LIMIT"] = "1"
            self.assertTrue(LIVE["unlock_frame_limit"]())
        self.assertIn("unlock: true,", LIVE["frame_limit_js"](True))
        self.assertIn("unlock: false,", LIVE["frame_limit_js"](False))
        self.assertNotIn("__UNLOCK__", LIVE["frame_limit_js"](True))

    def test_limiter_file_and_running_game_are_read(self):
        with tempfile.TemporaryDirectory() as directory:
            limiter = Path(directory) / "limiter"
            self.assertFalse(LIVE["limiter_on"](str(limiter)))
            limiter.write_bytes(b"\x01\x00\x00\x00")
            self.assertTrue(LIVE["limiter_on"](str(limiter)))
            limiter.write_bytes(b"\x00\x00\x00\x00")
            self.assertFalse(LIVE["limiter_on"](str(limiter)))
            proc = Path(directory) / "proc"
            (proc / "12").mkdir(parents=True)
            (proc / "12" / "cmdline").write_bytes(b"steam\0-gamepadui\0")
            (proc / "self").mkdir()
            self.assertFalse(LIVE["game_running"](str(proc)))
            (proc / "40").mkdir()
            (proc / "40" / "cmdline").write_bytes(b"/usr/bin/FEX\0/steam/reaper\0SteamLaunch\0AppId=70\0--\0")
            self.assertTrue(LIVE["game_running"](str(proc)))

    def test_gamescope_limit_is_cleared_only_when_steam_has_it_off_and_a_game_still_runs_limited(self):
        helper = LIVE["Helper"]("/steam", 0)
        helper.frame_limit_state = "installed"
        page = mock.Mock()
        steam_off = [True]
        page.evaluate.side_effect = lambda expression: steam_off[0]
        helper.page = page
        clock = [100.0]
        limited = [True]
        runs, said = [], []
        globals_ = LIVE["Helper"].sync_limiter.__globals__
        run = lambda argv, **kw: runs.append(argv) or mock.Mock(returncode=0)
        def tick(seconds, times=1):
            for _ in range(times):
                helper.sync_limiter()
                clock[0] += seconds
        with mock.patch.dict(globals_, {"say": said.append, "limiter_on": lambda: limited[0], "game_running": lambda: True}), \
                mock.patch.object(globals_["time"], "monotonic", lambda: clock[0]), \
                mock.patch.object(globals_["subprocess"], "run", run), mock.patch.dict("os.environ", {"BL_UNLOCK_FRAME_LIMIT": "1"}):
            tick(10, 2)
            self.assertEqual(runs, [], "the limiter must stay on for the hold time first")
            tick(10)
            self.assertEqual(runs, [["gamescopectl", "debug_set_fps_limit", "0"]])
            self.assertEqual(len(said), 1)
            tick(10, 5)
            self.assertEqual(len(runs), 1, "at most once a minute")
            tick(10)
            self.assertEqual(len(runs), 2)
            steam_off[0] = False
            tick(10, 8)
            self.assertEqual(len(runs), 2, "a limit Steam itself still has on is left alone")
            steam_off[0] = True
            limited[0] = False
            tick(10, 8)
            self.assertEqual(len(runs), 2, "nothing to do when the limiter is off")
            limited[0] = True
            os_env = globals_["os"].environ
            os_env["BL_UNLOCK_FRAME_LIMIT"] = "0"
            tick(10, 8)
            self.assertEqual(len(runs), 2, "the pref off leaves gamescope alone")


    def test_limit_is_kept_when_offered_and_moved_to_the_highest_value_otherwise(self):
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "frame_limit.js"
            script.write_text(LIVE["frame_limit_js"](False))
            test = Path(directory) / "test.js"
            test.write_text(HARNESS)
            result = subprocess.run(["node", str(test), str(script)], capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_install_is_retried_until_steam_is_ready_and_reported_once_per_state(self):
        helper = LIVE["Helper"]("/steam", 0)
        page = mock.Mock()
        page.evaluate.side_effect = ["unavailable", "unavailable", "installed"]
        helper.page = page
        said = []
        clock = [100.0]
        globals_ = LIVE["Helper"].sync_frame_limit.__globals__
        with mock.patch.dict(globals_, {"say": said.append}), mock.patch.object(globals_["time"], "monotonic", lambda: clock[0]):
            helper.sync_frame_limit()
            helper.sync_frame_limit()
            clock[0] += 10
            helper.sync_frame_limit()
            clock[0] += 10
            helper.sync_frame_limit()
            clock[0] += 10
            helper.sync_frame_limit()
        self.assertEqual(page.evaluate.call_count, 3)
        self.assertEqual(said, ["frame limit sync: unavailable", "frame limit sync: installed"])


if __name__ == "__main__":
    unittest.main()
