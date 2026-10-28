<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="artwork/droiddeck-banner-dark.svg">
    <img alt="DroidDeck" src="artwork/droiddeck-banner-light.svg" width="100%">
  </picture>
</p>

> **This is an unofficial fork of [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck) that adds experimental Mali GPU support through [PanVK-kbase](https://github.com/zenithblue-oss/panvk-kbase-android)** (branch `mali-panvk`, modified since 2026-10-26). It is not made or supported by the DroidDeck team: report Mali problems in this fork's issues, not to upstream. Everything else below is the upstream README, with the Mali notes added.

DroidDeck brings the SteamOS experience to Android: Valve's Steam client in Big Picture on your Adreno or Mali handheld, with Windows games through Valve's ARM64 Proton.

<p align="center"><a href="https://discord.gg/JRGAvawjsm"><img src="https://img.shields.io/badge/Discord-Join%20the%20community-5865F2?logo=discord&logoColor=white" alt="Join the DroidDeck Discord"></a></p>

<p align="center"><img src="docs/releases/media/0.2.0/launch-into-steam.gif" width="80%" alt="Tapping DroidDeck on the Android home screen and landing in Steam Big Picture"></p>

> Note: DroidDeck does not have a stand-alone website. Do not click on any download links from websites claiming to be the DroidDeck team. 

## Requirements and install

Use Android 9 or newer on a supported Adreno device (730 or newer, or 8xx). Adreno 6xx is experimental: DirectX 11 uses DXVK 2 and may run, and DirectX 12 games can still crash. Mali is experimental in this fork (see [Mali (PanVK), experimental](#mali-panvk-experimental)). Xclipse, PowerVR, and Adreno 710 are unsupported. No root is required. Allow about 3 GB for the runtime and 1.1 GB more for the desktop and emulators. Install the APK from [Releases](https://github.com/Droid-Deck/DroidDeck/releases), install the Linux runtime, then press **Play** and sign in. Steam downloads on first launch. Install **Desktop & apps** to use the desktop and emulators. The **Store** installs Linux apps and games from Flathub (ARM64 builds) with Flatpak; with additional options to install Appimages and set up scripts. **Stores** (Setup › Stores › "Show Stores in the rail") signs in to GOG, Epic Games and Amazon Games, browses their catalogs and libraries, downloads games into the Games storage and adds each one to the Steam client as a non-Steam game, so it launches through Proton like everything else; the cog on its chip row sets which tab a store opens on and the download speed tier.

Before Steam launches, you must turn off **Restrict child processes** in Developer options. If this option is not available in developer settings (Android 12 and 13 devices), first launch of Steam will present a "Fix it for me" button, which will help automate the setup process.

## Mali (PanVK), experimental

On a Mali device, the Linux runtime (Steam, Proton, games) uses PanVK-kbase, an open Vulkan driver that runs on the vendor's Mali kernel driver (`/dev/mali0`). The Android side uses the phone's own Vulkan driver.

**Tested:** one device only.
- vivo X300 Pro: MediaTek Dimensity 9500, Mali-G1-Ultra MC12 (arch v14), Android 16.

**Untested:** other Mali CSF GPUs (G610-G925). PanVK-kbase supports them, but they have not been tried with DroidDeck.

**Setup:**
- Install the APK from this fork's releases, or build it as described in [Build](#build).
- You need an aarch64 **glibc (Linux)** build of PanVK-kbase. The upstream PanVK-kbase releases ship Android builds only.
- On the vivo X300 Pro the driver also needs three kbase patches:
  - report the DRM render node (gamescope needs it);
  - close imported sync files (fd leak);
  - fast exported fences.

  These are being submitted to PanVK-kbase upstream.
- Import the driver zip (with `libvulkan_panfrost.so`) under **Linux runtime driver**. With no Linux driver chosen, a Mali device uses the imported PanVK automatically.

**Done for you on PanVK:**
- `ENABLE_GAMESCOPE_WSI=0` is set automatically: through gamescope's WSI layer, games showed only their first frames, then a black screen.
- Launcher dialogs smaller than the screen are moved by one pixel once, so they take input. This is for Oblivion's launcher; turn it off with `BL_NUDGE_DIALOGS=0`.

**Session environment to set yourself:** use `tools/droiddeckctl env set --persistent` from a PC with adb.
- `VKD3D_DISABLE_EXTENSIONS=VK_EXT_present_timing`: Horizon Zero Dawn otherwise crashes in Mesa's X11 WSI.
- `PANVK_KBASE_HEAP_RENEW_INTERVAL=32`: Oblivion otherwise freezes after a few minutes at a high frame rate (tiler heap out of memory).

**Mali system driver (bridge):**
- The APK bundles [Blobferry](https://github.com/zolotvok/blobferry), which runs games on the phone's own Mali Vulkan driver through libhybris. Steam, gamescope and the desktop stay on PanVK. On a Mali phone it appears on the **Runtime driver** page as "Mali system driver (bridge)". Choose it there for all games.
- To choose per game: in the game's environment, set `DROIDDECK_GAME_DRIVER` to `bridge` (Mali system driver) or `session` (PanVK). The **Templates** button in the editor does this too.
- Settings: **Performance › Mali system driver** (descriptor buffers, pipeline libraries, Wine logs, FPS log, GPU timing log).
- Bridge games so far:
  - Oblivion (about 90 FPS);
  - The Witcher 3 classic (DX11) and current (DX12, about 19 FPS in the open world);
  - Tomb Raider (2013);
  - Horizon Zero Dawn.

  Release notes: [docs/releases/0.4.0-mali.1.md](docs/releases/0.4.0-mali.1.md).

**Status (PanVK):**
- Steam starts in the gamescope session. It sometimes stalls at start.
- Oblivion (DXVK) runs at about 90 FPS in game.
- The Witcher 3 (DXVK) reaches the main menu (47 FPS), then hits a GPU fault in the prologue.
- Horizon Zero Dawn reaches the menu, then hits the same GPU fault at the first in-game frame. That fault is an open PanVK bug.

## Community

Mali/PanVK problems: open an issue in this fork. The upstream Discord and bug forum are for the official, Adreno-only builds.

Join the [DroidDeck Discord](https://discord.gg/JRGAvawjsm) for help, Preview builds, and device reports. Bug reports go in its **#bug-reports** forum; attach the zip from **Share logs** (Setup, or the session drawer) so the logs come with it.

## Build

Run `tools/build_local.sh` with Docker, Java 17, the Android SDK/NDK, Rust through rustup, GitHub CLI, Python 3, and `zstd` installed. It prepares the same native libraries, pinned components and audio sinks as CI, then checks the APK's assets, JNI exports and dependencies. The APK is at `app/build/outputs/apk/release/app-release.apk`. Set `DROIDDECK_PA13_SOURCE_DIR` to an existing PulseAudio 13.0 source directory to skip downloading it. Set `DROIDDECK_SIGNING_ENV` to a signing environment file to install over a release build; relative keystore paths resolve beside that file. To install on an attached device, run `tools/deploy_local.sh`.

After preparation, ordinary `./gradlew :app:assembleRelease` builds can reuse the verified inputs. If native sources, component pins or staged files change, packaging stops with instructions to rerun the helper. Generated audio stays under `app/build/`; the tracked base bundle is never modified. `./gradlew -PskipRust=true :app:testDebugUnitTest` needs no native preparation. For an APK without store engines, use `DROIDDECK_SKIP_RUST=1 tools/build_local.sh`; it omits cached engine libraries too.

Run the helper and preload regression suite with `bash tools/test_local.sh`. On macOS it runs in Linux through Docker, matching the helpers' runtime and CI instead of compiling Linux preloads against macOS headers.

## Limits

Compatibility and performance vary by device; hardware validation is limited. Desktop compositing uses software rendering. Firefox sandboxing is reduced under proot. Logs are kept app-private (`files/logs/`, the last 30 sessions); **Share logs** packs the newest session, with the stores and tool logs, into one scrubbed zip.

## Credits and licence

GPL-3.0. Runtime, shim, input, and controller work build on WinNative and Bannerlator (maxjivi05). Epic Games and GOG support build on [Phobos’s](https://github.com/phobos665) adaptation of [Legendary](https://github.com/legendary-gl/legendary) and [gogdl](https://github.com/Heroic-Games-Launcher/heroic-gogdl), respectively. LSFG frame generation is from the work of Camille LaVey and the [Eden](https://eden-emu.dev) emulator project, following [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk), ported to WinNative and DroidDeck by [@maxjivi05](https://github.com/maxjivi05); it needs your own copy of [Lossless Scaling](https://store.steampowered.com/app/993090/) and ships none of its shaders. x86 AppImages, and any whose own runtime cannot unpack them, are unpacked with [uruntime](https://github.com/VHSgunzo/uruntime) by VHSgunzo (MIT), shipped unmodified with its licence. Mali support uses [PanVK-kbase](https://github.com/zenithblue-oss/panvk-kbase-android) (Mesa's PanVK on the Mali kbase kernel driver, MIT). See [LICENSE](LICENSE). Steam and Proton belong to Valve Corporation; this project is not affiliated with Valve.
