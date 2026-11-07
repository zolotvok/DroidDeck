import hashlib
import json
import os
import runpy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

BIN = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin"
OVERLAY = BIN.parents[2]
MALI = runpy.run_path(str(BIN / "droiddeck-mali-defaults"))
GAME_ENV = runpy.run_path(str(BIN / "droiddeck-game-env"))
BRIDGE = {"ENABLE_MALI_COMPAT": "1", "VK_DRIVER_FILES": "/usr/lib/mali_bridge/mali_bridge_icd.json",
          "DXVK_CONFIG": "dxvk.enableGraphicsPipelineLibrary = False"}


def md5(data):
    return hashlib.md5(data).hexdigest()


class RecipeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def store_game(self, store, app, folder_name="Game"):
        folder = self.root / "Games/Stores" / folder_name
        (folder / "bin").mkdir(parents=True)
        (folder / ".droiddeck-store.json").write_text(json.dumps({"store": store, "id": app, "title": folder_name}))
        (folder / ".droiddeck-launch.bat").write_text("")
        return folder

    def command(self, exe):
        return ["/proton/proton", "waitforexitandrun", str(exe)]

    def env(self, compat, **extra):
        return dict(BRIDGE, STEAM_COMPAT_DATA_PATH=str(self.root / "compatdata" / compat), **extra)

    def test_epic_games_are_found_by_their_appname_whatever_the_shortcut_id(self):
        folder = self.store_game("epic", "Angelonia", "Watch Dogs 2")
        for shortcut in ("2274579123", "3000000001"):
            values, notes, found = MALI["recipe"](self.command(folder / ".droiddeck-launch.bat"), self.env(shortcut))
            self.assertEqual(found.title, "Watch Dogs 2")
            self.assertEqual(values["PROTON_NO_NTSYNC"], "1")
            self.assertEqual(values["BL_SYNC"], "0")
            self.assertEqual(values["FEX_SILENTLOG"], "1")
            self.assertEqual(values["MALI_DEBUG_CONFIG"], "/usr/share/droiddeck-mali/mali-debug-wd2.config")
            self.assertIn("epic:Angelonia", notes[0])
        values, _, found = MALI["recipe"](self.command(folder / "bin/WatchDogs2.exe"), self.env("2274579123"))
        self.assertEqual(found.title, "Watch Dogs 2")

    def test_recipes_leave_out_measuring_and_debug_keys(self):
        debug = {"MALI_COMPAT_GPU_TIME", "MALI_WSI_FPS", "MALI_COMPAT_DUMP_HASH", "MALI_COMPAT_MEMLOG", "LD_PRELOAD",
                 "WD2DUMP_DIR", "MALI_COMPAT_BUDGET_LOG_S", "MALI_COMPAT_RT_COMPRESS", "DXVK_HUD", "PROTON_LOG",
                 "WINEDEBUG", "FEX_UNALIGNED_ATOMIC_STATS"}
        for key, recipe in MALI["RECIPES"].items():
            with self.subTest(key=key):
                self.assertFalse(debug & set(recipe.env))
                self.assertRegex(key, r"^(steam:[1-9][0-9]*|epic:[A-Za-z0-9]+)$")
                self.assertFalse(key.split(":", 1)[1].isdigit() and int(key.split(":", 1)[1]) >= 2**31)

    def test_steam_games_are_found_by_app_id(self):
        values, notes, found = MALI["recipe"](self.command(self.root / "ELDEN RING/Game/start_protected_game.exe"),
                                              self.env("1245620"))
        self.assertEqual(found.title, "Elden Ring")
        self.assertEqual(values["VKD3D_FEATURE_LEVEL"], "12_1")
        self.assertEqual(values["MALI_COMPAT_BUDGET_MB"], "2048")
        self.assertEqual(values["DXVK_CONFIG"], "dxvk.enableGraphicsPipelineLibrary = False; dxgi.maxDeviceMemory = 2048")

    def test_control_gets_async_present_its_shader_stub_and_the_low_preset_note(self):
        folder = self.store_game("epic", "Calluna", "Control")
        values, notes, _ = MALI["recipe"](self.command(folder / ".droiddeck-launch.bat"), self.env("3749198560"))
        self.assertEqual(values["MALI_WSI_ASYNC_PRESENT"], "1")
        self.assertEqual(values["VKD3D_CONFIG"], "nodxr")
        stub = values["VKD3D_SHADER_OVERRIDE"].removeprefix("Z:")
        self.assertTrue((OVERLAY / stub.lstrip("/") / "bf125ef61cc75d97.spv").is_file())
        self.assertTrue(any("Low preset" in note for note in notes))

    def test_watch_dogs_keeps_its_clamp_and_ubisoft_sync(self):
        folder = self.store_game("epic", "Jasper", "Watch Dogs")
        values, _, found = MALI["recipe"](self.command(folder / ".droiddeck-launch.bat"), self.env("3529167647"))
        self.assertFalse(found.wpad)
        self.assertEqual({k: values[k] for k in ("PROTON_NO_NTSYNC", "BL_SYNC", "FEX_SILENTLOG", "MALI_COMPAT_WLS_CLAMP")},
                         {"PROTON_NO_NTSYNC": "1", "BL_SYNC": "0", "FEX_SILENTLOG": "1", "MALI_COMPAT_WLS_CLAMP": "all"})

    def test_wd2_debug_config_file_ships_and_only_prints(self):
        config = OVERLAY / MALI["WD2_DEBUG_CONFIG"].lstrip("/")
        lines = [l for l in config.read_text().splitlines() if l and not l.startswith("#")]
        self.assertEqual(lines, ["MALI_DFC_ENABLE_PRINTING_DEVICE_FAULTS=1"])

    def test_nothing_off_the_bridge_or_when_switched_off(self):
        folder = self.store_game("epic", "Angelonia")
        command = self.command(folder / ".droiddeck-launch.bat")
        self.assertIsNone(MALI["recipe"](command, {"STEAM_COMPAT_DATA_PATH": "/c/2274579123"})[2])
        self.assertIsNone(MALI["recipe"](command, self.env("2274579123", BL_MALI_GAME_DEFAULTS="0"))[2])
        config = {"version": 1, "shared": {}, "games": {"2274579123": {"BL_MALI_GAME_DEFAULTS": "0"}}}
        self.assertIsNone(MALI["recipe"](command, self.env("2274579123"), config, "2274579123")[2])
        self.assertIsNotNone(MALI["recipe"](command, self.env("2274579123"), config, "1")[2])
        config = {"version": 1, "shared": {"BL_MALI_GAME_DEFAULTS": "0"}, "games": {"2274579123": {"BL_MALI_GAME_DEFAULTS": "1"}}}
        self.assertIsNotNone(MALI["recipe"](command, self.env("2274579123"), config, "2274579123")[2])

    def test_unknown_games_and_a_shortcut_id_that_looks_like_an_app_id_get_nothing(self):
        self.assertIsNone(MALI["recipe"](self.command(self.root / "x/game.exe"), self.env("70"))[2])
        self.assertIsNone(MALI["recipe"](self.command(self.root / "x/game.exe"), self.env(str(2**31 + 1245620)))[2])
        folder = self.store_game("gog", "Angelonia")
        self.assertIsNone(MALI["recipe"](self.command(folder / "game.exe"), self.env("2274579123"))[2])

    def test_merge_appends_dll_overrides(self):
        self.assertEqual(MALI["merge"]({"WINEDLLOVERRIDES": "d3dgear64=d", "X": "1"}, {"WINEDLLOVERRIDES": "a=n,b", "X": "2"}),
                         {"WINEDLLOVERRIDES": "d3dgear64=d;a=n,b", "X": "2"})


class WpadTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.prefix = Path(self.tmp.name) / "compatdata/2274579123"
        self.reg = self.prefix / "pfx/user.reg"
        self.reg.parent.mkdir(parents=True)
        self.head = 'WINE REGISTRY Version 2\n;; All keys relative to REGISTRY\\\\User\\\\S-1\n\n#arch=win64\n\n' \
                    '[Software\\\\Microsoft\\\\Windows\\\\CurrentVersion\\\\Internet Settings] 1791989186\n"x"=dword:1\n'

    def test_block_is_appended_once_like_the_b9_1_file(self):
        self.reg.write_text(self.head)
        self.reg.chmod(0o640)
        self.assertIn("proxy auto-detect off", MALI["wpad_off"](self.prefix, now=1793000000))
        text = self.reg.read_text()
        self.assertEqual(text, self.head + "\n[Software\\\\Microsoft\\\\Windows\\\\CurrentVersion\\\\Internet Settings\\\\Connections]"
                         " 1793000000\n\"DefaultConnectionSettings\"=hex:46,00,00,00,00,00,00,00,01,00,00,00,"
                         + ",".join(["00"] * 44) + "\n")
        self.assertEqual(self.reg.stat().st_mode & 0o777, 0o640)
        self.assertIsNone(MALI["wpad_off"](self.prefix))
        self.assertEqual(self.reg.read_text(), text)

    def test_an_existing_connections_key_is_left_alone(self):
        own = self.head + '\n[Software\\\\Microsoft\\\\Windows\\\\CurrentVersion\\\\Internet Settings\\\\connections] 1\n"y"=dword:2\n'
        self.reg.write_text(own)
        self.assertIsNone(MALI["wpad_off"](self.prefix))
        self.assertEqual(self.reg.read_text(), own)

    def test_a_prefix_without_user_reg_is_not_created(self):
        self.reg.parent.rmdir()
        self.assertIn("next launch", MALI["wpad_off"](self.prefix))
        self.assertFalse(self.reg.parent.exists())


class FexPatchTest(unittest.TestCase):
    STOCK = b"MZ stock FEX-2610-101-g1c4f04b"
    PATCHED = b"MZ patched FEX-2610-101-g1c4f04b uafb"

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.tool = root / "steamapps/common/Proton Experimental (ARM64)"
        self.target = self.tool / MALI["FEX_DLL"]
        self.target.parent.mkdir(parents=True)
        self.target.write_bytes(self.STOCK)
        (self.tool / "version").write_text("1761000000 experimental-11.0-20261027b\n")
        self.bundled = root / "usr/local/lib/droiddeck-fex-arm64ec/libarm64ecfex.dll"
        self.bundled.parent.mkdir(parents=True)
        self.bundled.write_bytes(self.PATCHED)
        self.components = root / "components"
        self.components.mkdir()
        self.patch = mock.patch.dict(MALI["fex_patch"].__globals__, {
            "FEX_PATCHED_MD5": md5(self.PATCHED), "FEX_BASE_MD5": (md5(self.STOCK),)})
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def run_patch(self, verb="waitforexitandrun", app="2274579123", **env):
        return MALI["fex_patch"]([str(self.tool / "proton"), verb, "game.exe"], env, app, self.components, str(self.bundled))

    def test_stock_is_replaced_saved_and_put_back_after_a_proton_update(self):
        self.assertIn("patched DLL put in", self.run_patch())
        self.assertEqual(self.target.read_bytes(), self.PATCHED)
        self.assertEqual((self.components / "fex-arm64ec" / (md5(self.STOCK) + ".dll")).read_bytes(), self.STOCK)
        self.assertIn("Proton Experimental (ARM64)", (self.components / "reapplied.log").read_text())
        self.assertIsNone(self.run_patch())
        self.target.write_bytes(self.STOCK)
        self.assertIn("patched DLL put in", self.run_patch())
        self.assertEqual(self.target.read_bytes(), self.PATCHED)

    def test_a_game_can_opt_out_and_back_in(self):
        self.run_patch()
        self.assertIn("stock DLL for this game", self.run_patch(BL_FEX_ARM64EC_PATCH="0"))
        self.assertEqual(self.target.read_bytes(), self.STOCK)
        self.assertIsNone(self.run_patch(BL_FEX_ARM64EC_PATCH="0"))
        self.run_patch(BL_FEX_ARM64EC_PATCH="1")
        self.assertEqual(self.target.read_bytes(), self.PATCHED)

    def test_another_fex_build_is_never_replaced(self):
        self.target.write_bytes(b"MZ FEX-2611-3-gabcdef0")
        self.assertIn("FEX-2611-3-gabcdef0, not the patched DLL's base", self.run_patch())
        self.assertEqual(self.target.read_bytes(), b"MZ FEX-2611-3-gabcdef0")

    def test_same_fex_build_with_another_checksum_counts_as_stock(self):
        self.target.write_bytes(b"MZ rebuilt FEX-2610-101-g1c4f04b")
        self.assertIn("patched DLL put in", self.run_patch())

    def test_a_damaged_bundle_keeps_stock(self):
        self.bundled.write_bytes(b"MZ truncated")
        self.assertIn("missing or damaged", self.run_patch())
        self.assertEqual(self.target.read_bytes(), self.STOCK)

    def test_the_components_choice_for_this_proton_wins(self):
        row = "\t".join(["*", self.tool.name, "experimental-11.0-20261027b", "fex", MALI["FEX_DLL"], "abc", "/src"])
        (self.components / "desired.tsv").write_text(row + "\n")
        self.assertIn("Components choice", self.run_patch())
        self.assertEqual(self.target.read_bytes(), self.STOCK)
        (self.components / "desired.tsv").write_text(row.replace("*", "123", 1) + "\n")
        self.assertIn("patched DLL put in", self.run_patch())

    def test_only_game_launches_of_an_arm64_proton(self):
        self.assertIsNone(self.run_patch(verb="run"))
        self.target.unlink()
        self.assertIsNone(self.run_patch())


class GameEnvMaliDefaultsTest(unittest.TestCase):
    def test_launch_on_the_bridge_gets_the_recipe_below_the_games_own_entries(self):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            folder = home / "Games/Stores/Epic/Watch Dogs 2"
            folder.mkdir(parents=True)
            (folder / ".droiddeck-store.json").write_text(json.dumps({"store": "epic", "id": "Angelonia", "title": "WD2"}))
            prefix = home / "compatdata/2274579123"
            (prefix / "pfx").mkdir(parents=True)
            (prefix / "pfx/user.reg").write_text("WINE REGISTRY Version 2\n")
            icd = home / "mali_bridge_icd.json"
            icd.write_text("{}")
            config = home / ".config/droiddeck/game-environment.json"
            config.parent.mkdir(parents=True)
            config.write_text(json.dumps({"version": 1, "shared": {"VKD3D_FEATURE_LEVEL": "12_2"},
                                          "games": {"2274579123": {"MALI_COMPAT_BUDGET_MB": "3072", "FEX_SILENTLOG": None}}}))
            probe = home / "proton"
            probe.write_text("#!" + sys.executable + "\nimport json, os\nprint(json.dumps(dict(os.environ)))\n")
            probe.chmod(0o755)
            env = {k: v for k, v in os.environ.items() if not k.startswith(("WINE", "PROTON", "DROIDDECK", "BL_", "STEAM", "MALI", "FEX", "VK"))}
            env.update(HOME=tmp, STEAM_COMPAT_DATA_PATH=str(prefix), BL_VK_GAME_DRIVER=str(icd))
            result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun",
                                     str(folder / ".droiddeck-launch.bat")], env=env, text=True, capture_output=True, check=True)
            game = json.loads(result.stdout)
            self.assertIn("Mali defaults for Watch Dogs 2", result.stderr)
            self.assertEqual(game["PROTON_NO_NTSYNC"], "1")
            self.assertEqual(game["MALI_COMPAT_ASYNC_PSO"], "50")
            self.assertEqual(game["MALI_COMPAT_BUDGET_MB"], "3072")
            self.assertNotIn("FEX_SILENTLOG", game)
            self.assertIn("Internet Settings\\\\Connections", (prefix / "pfx/user.reg").read_text())
            env.pop("BL_VK_GAME_DRIVER")
            result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun",
                                     str(folder / ".droiddeck-launch.bat")], env=env, text=True, capture_output=True, check=True)
            self.assertNotIn("MALI_COMPAT_ASYNC_PSO", json.loads(result.stdout))
            self.assertNotIn("Mali defaults", result.stderr)

    def test_the_module_is_extracted_at_session_start(self):
        source = (Path(__file__).resolve().parents[2] / "app/src/main/java/com/droiddeck/launcher/session/SessionFiles.kt").read_text()
        for name in ("usr/local/bin/droiddeck-mali-defaults", "usr/share/droiddeck-mali/mali-debug-wd2.config",
                     "usr/share/droiddeck-mali/vkd3d-override/control/bf125ef61cc75d97.spv",
                     "usr/local/lib/droiddeck-fex-arm64ec/$it"):
            self.assertIn('"%s"' % name, source)


class SessionMaliDefaultsTest(unittest.TestCase):
    def block(self):
        source = (BIN / "droiddeck-session").read_text()
        start = source.index('if [ -n "${BL_VK_DRIVER:-}" ]; then')
        return source[start:source.index("  unset bl_icd_lib\nfi\n", start) + len("  unset bl_icd_lib\nfi\n")]

    def session(self, library, **extra):
        with tempfile.TemporaryDirectory() as tmp:
            lib = Path(tmp) / library
            lib.write_bytes(b"")
            icd = Path(tmp) / "icd.json"
            icd.write_text(json.dumps({"ICD": {"library_path": str(lib)}}))
            env = {"PATH": os.environ["PATH"], "BL_VK_DRIVER": str(icd), **extra}
            script = self.block() + '\nfor n in BL_STEAM_SOFTWARE_MENUS PANVK_DEBUG PANVK_KBASE_HEAP_RENEW_INTERVAL ' \
                'VKD3D_DISABLE_EXTENSIONS; do printf "%s=%s\\n" "$n" "${!n-unset}"; done >&3'
            out = subprocess.run(["bash", "-c", "exec 3>&1 >/dev/null\n" + script], env=env, text=True,
                                 capture_output=True, check=True).stdout
            return dict(line.split("=", 1) for line in out.splitlines())

    def test_panvk_session_defaults_to_software_menus_and_panvk_light(self):
        self.assertEqual(self.session("libvulkan_panfrost.so"), {
            "BL_STEAM_SOFTWARE_MENUS": "1", "PANVK_DEBUG": "no_gs,no_tess,noafbc,no_crc",
            "PANVK_KBASE_HEAP_RENEW_INTERVAL": "32", "VKD3D_DISABLE_EXTENSIONS": "VK_EXT_present_timing"})

    def test_the_users_own_values_win(self):
        got = self.session("libvulkan_panfrost.so", BL_STEAM_GPU_MENUS="1", PANVK_DEBUG="", VKD3D_DISABLE_EXTENSIONS="X")
        self.assertEqual(got["BL_STEAM_SOFTWARE_MENUS"], "unset")
        self.assertEqual(got["PANVK_DEBUG"], "")
        self.assertEqual(got["VKD3D_DISABLE_EXTENSIONS"], "X")
        self.assertEqual(self.session("libvulkan_panfrost.so", BL_STEAM_SOFTWARE_MENUS="0")["BL_STEAM_SOFTWARE_MENUS"], "0")

    def test_other_session_drivers_are_untouched(self):
        self.assertEqual(set(self.session("libvulkan_freedreno.so").values()), {"unset"})


MANIFEST = OVERLAY / "usr/share/droiddeck-mali/env-defaults.json"
CASES = json.loads((Path(__file__).resolve().parent / "data/env-defaults-cases.json").read_text())


class EnvDefaultsManifestTest(unittest.TestCase):
    """The env defaults manifest (online updater): validation, newest-wins, user keys win, pending never applies."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.home = self.root / "home"
        MALI["_ACTIVE"].clear()
        self.addCleanup(MALI["_ACTIVE"].clear)

    def write(self, path, manifest):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(manifest) if not isinstance(manifest, str) else manifest)
        return path

    def online(self, manifest):
        return self.write(self.home / ".config/droiddeck/mali-env-defaults.json", manifest)

    def bundled(self, manifest):
        return str(self.write(self.root / "share/env-defaults.json", manifest))

    def er(self, version, level):
        return {"schema": "droiddeck-mali-env-defaults/1", "version": version, "games": {
            "steam:1245620": {"title": "Elden Ring", "env": {"VKD3D_FEATURE_LEVEL": level}, "evidence": "t"}}}

    def er_recipe(self, bundled, config=None):
        env = dict(BRIDGE, STEAM_COMPAT_DATA_PATH=str(self.root / "compatdata/1245620"))
        return MALI["recipe"](["/proton/proton", "waitforexitandrun", "/g/eldenring.exe"], env, config, "1245620",
                              home=str(self.home), bundled=bundled)

    def test_the_shipped_manifest_is_valid_and_keeps_every_built_in_recipe(self):
        version, recipes = MALI["load_manifest"](MANIFEST)
        self.assertRegex(version, r"^2026\.11\.07\.[0-9]+$")
        self.assertEqual(set(recipes), set(MALI["RECIPES"]))
        for key, built_in in MALI["RECIPES"].items():
            self.assertEqual(recipes[key].env, built_in.env, key)
            self.assertEqual(recipes[key].wpad, built_in.wpad, key)
            self.assertEqual(recipes[key].note, built_in.note, key)
        raw = json.loads(MANIFEST.read_text())
        self.assertEqual(raw.get("status"), "release")
        for key, game in raw["games"].items():
            self.assertTrue(game["evidence"], key)
            for pending in game.get("pending", []):
                self.assertTrue(pending["why"], key)
        # the flash/flicker winner is in force (the built-in PASS_ORDER was reverted), not pending
        for key in ("steam:292030", "epic:Calluna"):
            self.assertEqual(recipes[key].env["MALI_COMPAT_FB_MASK"], "0x0d6", key)
            self.assertFalse(any("MALI_COMPAT_FB_MASK" in p["env"] for p in raw["games"][key].get("pending", [])), key)

    def test_no_dxr_forcing_and_the_vendor_spoof_only_for_doom_eternal(self):
        """1106: 12_2 claimed DXR 1.1 / mesh shaders the driver lacks; MALI_COMPAT_VENDOR_ID=10de makes the GPU an
        "RTX 2080". Neither may reach a game that does not need it."""
        _, recipes = MALI["load_manifest"](MANIFEST)
        raw = json.loads(MANIFEST.read_text())
        for source in (recipes, MALI["RECIPES"]):
            for key, recipe in source.items():
                with self.subTest(key=key):
                    self.assertNotEqual(recipe.env.get("VKD3D_FEATURE_LEVEL"), "12_2")
                    self.assertNotIn("dxr", recipe.env.get("VKD3D_CONFIG", "").replace("nodxr", ""))
                    self.assertFalse([n for n in recipe.env if "NVAPI" in n or n.startswith("MALI_COMPAT_DEVICE_")])
                    self.assertNotIn("nvapi", recipe.env.get("WINEDLLOVERRIDES", "").lower())
                    if key != "steam:782330":
                        self.assertNotIn("MALI_COMPAT_VENDOR_ID", recipe.env)
            self.assertEqual(source["steam:782330"].env, {"MALI_COMPAT_VENDOR_ID": "10de"})
        for game in raw["games"].values():
            for pending in game.get("pending", []):
                self.assertNotIn("MALI_COMPAT_VENDOR_ID", pending["env"])
                self.assertNotEqual(pending["env"].get("VKD3D_FEATURE_LEVEL"), "12_2")

    def test_shared_cases_refused_and_accepted(self):
        for case in CASES["invalid"]:
            with self.assertRaises(ValueError, msg=case["why"]):
                MALI["parse_manifest"](json.dumps(case["manifest"]).encode())
        for case in CASES["valid"]:
            MALI["parse_manifest"](json.dumps(case["manifest"]).encode())

    def test_newest_valid_manifest_wins_else_built_in(self):
        bundled = self.bundled(self.er("2026.11.06.1", "12_1"))
        self.online(self.er("2026.11.07.1", "12_0"))
        values, notes, _ = self.er_recipe(bundled)
        self.assertEqual(values["VKD3D_FEATURE_LEVEL"], "12_0")
        self.assertIn("env defaults 2026.11.07.1 (downloaded)", notes[0])
        MALI["_ACTIVE"].clear()
        self.online(self.er("2026.11.05.9", "12_0"))
        values, notes, _ = self.er_recipe(bundled)
        self.assertEqual(values["VKD3D_FEATURE_LEVEL"], "12_1")
        self.assertIn("(bundled)", notes[0])
        MALI["_ACTIVE"].clear()
        values, notes, _ = self.er_recipe(str(self.root / "missing.json"))
        self.assertIn("env defaults 2026.11.05.9 (downloaded)", notes[0])
        MALI["_ACTIVE"].clear()
        (self.home / ".config/droiddeck/mali-env-defaults.json").unlink()
        values, notes, _ = self.er_recipe(str(self.root / "missing.json"))
        self.assertIn("env defaults built-in", notes[0])
        self.assertEqual(values["MALI_COMPAT_BUDGET_MB"], "2048")

    def test_a_broken_download_is_ignored_with_a_log_line(self):
        bundled = self.bundled(self.er("2026.11.06.1", "12_1"))
        bad = self.er("2026.11.09.1", "12_0")
        bad["games"]["steam:1245620"]["env"]["LD_PRELOAD"] = "/root/evil.so"
        self.online(bad)
        values, notes, _ = self.er_recipe(bundled)
        self.assertEqual(values["VKD3D_FEATURE_LEVEL"], "12_1")
        self.assertNotIn("LD_PRELOAD", values)
        self.assertTrue(any("downloaded env defaults" in n and "ignored" in n for n in notes))
        MALI["_ACTIVE"].clear()
        self.online("{not json")
        values, notes, _ = self.er_recipe(bundled)
        self.assertEqual(values["VKD3D_FEATURE_LEVEL"], "12_1")

    def test_user_keys_win_over_the_defaults(self):
        bundled = self.bundled(self.er("2026.11.06.1", "12_1"))
        mine = {"version": 1, "userShared": ["VKD3D_FEATURE_LEVEL"], "shared": {"VKD3D_FEATURE_LEVEL": "12_2"}, "games": {}}
        values, notes, _ = self.er_recipe(bundled, mine)
        self.assertNotIn("VKD3D_FEATURE_LEVEL", values)
        self.assertTrue(any("your own VKD3D_FEATURE_LEVEL kept" in n for n in notes))
        # the app's own shared defaults (not in userShared) do not count as the user's: the recipe still applies
        values, _, _ = self.er_recipe(bundled, {"version": 1, "shared": {"VKD3D_FEATURE_LEVEL": "12_2"}, "games": {}})
        self.assertEqual(values["VKD3D_FEATURE_LEVEL"], "12_1")
        env = dict(BRIDGE, STEAM_COMPAT_DATA_PATH=str(self.root / "compatdata/1245620"))
        config = dict(mine, shared=dict(GameEnvironment_defaults(), VKD3D_FEATURE_LEVEL="12_2"))
        values, _, _ = self.er_recipe(bundled, config)
        out = GAME_ENV["apply_config"](env, config, "1245620", MALI["merge"]({}, values))
        self.assertEqual(out["VKD3D_FEATURE_LEVEL"], "12_2")

    def test_a_per_game_opt_out_stays(self):
        bundled = self.bundled({"schema": "droiddeck-mali-env-defaults/1", "version": "2026.11.06.1", "games": {
            "steam:1245620": {"title": "Elden Ring", "env": {"BL_FEX_ARM64EC_PATCH": "1", "VKD3D_FEATURE_LEVEL": "12_1"},
                              "evidence": "t"}}})
        config = {"version": 1, "shared": {}, "games": {"1245620": {"BL_FEX_ARM64EC_PATCH": "0"}}}
        values, _, _ = self.er_recipe(bundled, config)
        self.assertNotIn("BL_FEX_ARM64EC_PATCH", values)
        env = dict(BRIDGE, STEAM_COMPAT_DATA_PATH=str(self.root / "compatdata/1245620"))
        out = GAME_ENV["apply_config"](env, config, "1245620", MALI["merge"]({}, values))
        self.assertEqual(out["BL_FEX_ARM64EC_PATCH"], "0")
        self.assertTrue(MALI["switch_off"]("BL_FEX_ARM64EC_PATCH", out))

    def test_pending_entries_never_apply(self):
        values, notes, found = MALI["recipe"](["/proton/proton", "waitforexitandrun", "/g/witcher3.exe"],
                                              dict(BRIDGE, STEAM_COMPAT_DATA_PATH=str(self.root / "compatdata/292030")),
                                              None, "292030", home=str(self.home), bundled=str(MANIFEST))
        self.assertEqual(found.title, "The Witcher 3")
        self.assertEqual(values["MALI_COMPAT_FB_MASK"], "0x0d6")
        self.assertNotIn("DXVK_MALI_LAZY_STORAGE", values)
        self.assertEqual(values["VKD3D_CONFIG"], "nodxr")
        self.assertTrue(values["FEX_EXTENDEDVOLATILEMETADATA"].startswith("witcher3.exe;1e447c0-1e4487e;"))


def GameEnvironment_defaults():
    """GameEnvironment.defaults' D3D12 part (GameEnvironment.kt), as the app publishes it in the shared block."""
    return {"MESA_SHADER_CACHE_DISABLE": "false", "DROIDDECK_ANTICHEAT_OFFLINE": "1", "VKD3D_FEATURE_LEVEL": "12_1",
            "VKD3D_SHADER_MODEL": "6_6"}


class EffectiveEnvTest(unittest.TestCase):
    """The whole merge for a launch on the bridge: session env < bridge (game_driver) < shared (the app's defaults and
    the user's shared keys) < engine fixes + Mali defaults (minus the user's keys) < the game's own entries."""

    def launch(self, compat, exe, config):
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            prefix = home / "compatdata" / compat
            (prefix / "pfx").mkdir(parents=True)
            (prefix / "pfx/user.reg").write_text("WINE REGISTRY Version 2\n")
            icd = home / "mali_bridge_icd.json"
            icd.write_text("{}")
            path = home / ".config/droiddeck/game-environment.json"
            path.parent.mkdir(parents=True)
            path.write_text(json.dumps(config))
            probe = home / "proton"
            probe.write_text("#!" + sys.executable + "\nimport json, os\nprint(json.dumps(dict(os.environ)))\n")
            probe.chmod(0o755)
            game = home / exe
            game.parent.mkdir(parents=True, exist_ok=True)
            game.write_bytes(b"")
            env = {k: v for k, v in os.environ.items() if not k.startswith(("WINE", "PROTON", "DROIDDECK", "BL_", "STEAM", "MALI", "FEX", "VK", "DXVK"))}
            env.update(HOME=tmp, STEAM_COMPAT_DATA_PATH=str(prefix), BL_VK_GAME_DRIVER=str(icd))
            result = subprocess.run([sys.executable, str(BIN / "droiddeck-game-env"), str(probe), "waitforexitandrun", str(game)],
                                    env=env, text=True, capture_output=True, check=True)
            return json.loads(result.stdout)

    def test_a_normal_dx12_game_gets_12_1_no_dxr_no_spoof_and_keeps_the_bridge_flags(self):
        game = self.launch("2050650", "Game/game.exe", {"version": 1, "shared": GameEnvironment_defaults(), "games": {}})
        self.assertEqual(game["VKD3D_FEATURE_LEVEL"], "12_1")
        self.assertEqual(game["VKD3D_SHADER_MODEL"], "6_6")
        self.assertEqual(game["VKD3D_CONFIG"], "no_upload_hvv")
        self.assertNotIn("MALI_COMPAT_VENDOR_ID", game)
        self.assertFalse([n for n in game if "NVAPI" in n])

    def test_doom_eternal_alone_gets_the_nvidia_vendor_id_and_the_user_can_remove_it(self):
        config = {"version": 1, "shared": GameEnvironment_defaults(), "games": {}}
        self.assertEqual(self.launch("782330", "DOOMEternal/idTechLauncher.exe", config)["MALI_COMPAT_VENDOR_ID"], "10de")
        config["games"] = {"782330": {"MALI_COMPAT_VENDOR_ID": None}}
        self.assertNotIn("MALI_COMPAT_VENDOR_ID", self.launch("782330", "DOOMEternal/idTechLauncher.exe", config))

    def test_the_users_shared_feature_level_wins(self):
        config = {"version": 1, "userShared": ["VKD3D_FEATURE_LEVEL"],
                  "shared": dict(GameEnvironment_defaults(), VKD3D_FEATURE_LEVEL="12_2"), "games": {}}
        self.assertEqual(self.launch("1245620", "ELDEN RING/Game/eldenring.exe", config)["VKD3D_FEATURE_LEVEL"], "12_2")
        config = dict(config, userShared=[])
        self.assertEqual(self.launch("1245620", "ELDEN RING/Game/eldenring.exe", config)["VKD3D_FEATURE_LEVEL"], "12_1")


if __name__ == "__main__":
    unittest.main()
