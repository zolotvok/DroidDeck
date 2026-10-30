package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.frontend.StoreListBackfill
import com.droiddeck.launcher.frontend.WinCompSources
import java.io.File

object StoreLaunch {
    private const val TAG = "StoreLaunch"
    const val LAUNCHER = ".droiddeck-launch.bat"
    const val EPIC_CODE = ".droiddeck-epic-code"
    const val EPIC_OVT = ".droiddeck-epic.ovt"

    fun writeLauncher(folder: File, sidecar: StoreGameSidecar): StoreGameSidecar {
        val needs = sidecar.args.isNotEmpty() || sidecar.env.isNotEmpty() || sidecar.store == Store.EPIC
        val file = File(folder, LAUNCHER)
        if (!needs) { file.delete(); return sidecar.copy(launcher = null) }
        file.writeText(launcherText(sidecar))
        return sidecar.copy(launcher = LAUNCHER)
    }

    fun launcherText(sidecar: StoreGameSidecar): String {
        val exe = sidecar.exe.replace('/', '\\')
        val dir = exe.substringBeforeLast('\\', "")
        val lines = ArrayList<String>()
        lines += "@echo off"
        lines += "setlocal"
        lines += "rem Written by DroidDeck for ${sidecar.store.label}: ${sidecar.title}. Steam's shortcut runs this file."
        lines += "cd /d \"%~dp0$dir\""
        for ((k, v) in sidecar.env) if (ENV_NAME.matches(k)) lines += "set \"$k=${v.replace("\"", "")}\""
        lines += prereqLines(sidecar)
        // Epic: the manifest's launch command goes first, as the Epic launcher and Legendary do; batch operators dropped
        val command = if (sidecar.store == Store.EPIC) batchSafe(sidecar.extra[WinCompSources.EPIC_LAUNCH_COMMAND].orEmpty()) else ""
        val args = (listOf(command).filter { it.isNotEmpty() } + sidecar.args.map { quoteArg(it) }).joinToString(" ")
        val run = "\"%~dp0$exe\"" + (if (args.isNotEmpty()) " $args" else "")
        if (sidecar.store == Store.EPIC) {
            lines += "set \"$CODE_VAR=\""
            lines += "if exist \"%~dp0$EPIC_CODE\" set /p $CODE_VAR=<\"%~dp0$EPIC_CODE\""
            lines += "if exist \"%~dp0$EPIC_CODE\" del \"%~dp0$EPIC_CODE\""
            lines += "if defined $CODE_VAR goto signed"
            lines += run
            lines += "goto :eof"
            lines += ":signed"
            // the ownership verification token, when the launch wrote one (Ubisoft titles need it)
            lines += "set \"$OVT_VAR=\""
            lines += "if exist \"%~dp0$EPIC_OVT\" set \"$OVT_VAR=-epicovt=\"%~dp0$EPIC_OVT\"\""
            lines += "set \"$CODE_VAR=\" & $run -AUTH_LOGIN=unused -AUTH_PASSWORD=%$CODE_VAR% -AUTH_TYPE=exchangecode %$OVT_VAR%"
        } else {
            lines += run
        }
        return lines.joinToString("\r\n") + "\r\n"
    }

    private val ENV_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Text for a batch line: control characters and the batch operators dropped (quotes stay). */
    private fun batchSafe(text: String) = text.filter { it >= ' ' && it != '\u007f' && it !in "&|<>^%" }.trim()

    private val UBISOFT = Regex("(?i)uplay|ubisoft")

    /**
     * Epic: the manifest's prerequisite installer, when no Windows component covers it, run in the game's own prefix
     * before the game, as the Epic launcher, Legendary/Heroic and unifideck do. Most run once per prefix (a marker in
     * the prefix's ProgramData, whatever the exit code, as Heroic does). Ubisoft Connect (prereq `Uplay`, Ubisoft
     * titles that start through UplayLaunch.exe) has no marker: it runs while the prefix has no Ubisoft Connect.
     */
    internal fun prereqLines(sidecar: StoreGameSidecar): List<String> {
        if (sidecar.store != Store.EPIC) return emptyList()
        val path = sidecar.extra[WinCompSources.EPIC_PREREQ_PATH].orEmpty().replace('\\', '/')
        val name = sidecar.extra[WinCompSources.EPIC_PREREQ_NAME].orEmpty()
        val parts = path.split('/')
        if (path.isEmpty() || batchSafe(path) != path || '"' in path || ':' in path || path.startsWith("/") || parts.any { it == ".." || it.isEmpty() }) return emptyList()
        if (WinCompSources.epic(path, name).isNotEmpty()) return emptyList()
        val ubisoft = UBISOFT.containsMatchIn(path) || UBISOFT.containsMatchIn(name)
        // without its arguments an installer may wait for clicks; Ubisoft's is known to take /S
        val args = batchSafe(sidecar.extra[WinCompSources.EPIC_PREREQ_ARGS] ?: if (ubisoft) "/S" else return emptyList())
        val exe = "%~dp0" + path.replace('/', '\\')
        val run = "start \"\" /wait \"$exe\"" + (if (args.isNotEmpty()) " $args" else "")
        val lines = ArrayList<String>()
        lines += "rem The Epic prerequisite ${batchSafe(name).replace("\"", "").ifEmpty { parts.last() }}, run in this prefix before the game"
        lines += "if not exist \"$exe\" goto dd_prereq_done"
        if (ubisoft) {
            lines += "if exist \"%ProgramFiles(x86)%\\Ubisoft\\Ubisoft Game Launcher\\upc.exe\" goto dd_prereq_done"
            lines += run
        } else {
            val key = sidecar.id.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }.take(40) + "-" +
                Integer.toHexString((path + "|" + args).hashCode()).padStart(8, '0')
            val marker = "%ProgramData%\\DroidDeck\\epic-prereq-$key.done"
            lines += "if exist \"$marker\" goto dd_prereq_done"
            lines += run
            lines += "set \"DD_RC=%errorlevel%\""
            lines += "if not exist \"%ProgramData%\\DroidDeck\" mkdir \"%ProgramData%\\DroidDeck\""
            lines += ">\"$marker\" echo rc=%DD_RC%"
        }
        lines += ":dd_prereq_done"
        return lines
    }

    fun quoteArg(arg: String): String {
        val clean = arg.filter { it >= ' ' && it != '\u007f' }
        return if (' ' in clean && !clean.contains('"')) "\"$clean\"" else clean
    }

    private const val CODE_VAR = "DD_X"
    private const val OVT_VAR = "DD_OVT"

    fun refreshLauncher(folder: File, sidecar: StoreGameSidecar) {
        if (sidecar.launcher != LAUNCHER) return
        val file = File(folder, LAUNCHER)
        val text = launcherText(sidecar)
        if (runCatching { file.readText() }.getOrNull() != text) {
            file.writeText(text)
            Log.i(TAG, "launcher rewritten for ${sidecar.id}")
        }
    }

    fun prepare(context: Context, store: Store, id: String, onReady: () -> Unit) {
        if (store != Store.EPIC) { onReady(); return }
        val app = context.applicationContext
        Thread({
            epicCode(app, id)
            StoresState.post(onReady)
        }, "stores-launch-prep").start()
    }

    class CodeResult(val written: Boolean, val reason: String)

    fun epicCode(context: Context, id: String): CodeResult {
        val app = context.applicationContext
        val result = try {
            val folder = StoreInstallRoot.gameFolders(app).firstOrNull { f -> StoreGameSidecar.read(f)?.let { it.store == Store.EPIC && it.id == id } == true }
            // games installed before the launch command was read get it (and their prerequisite) from the store here
            folder?.let { StoreListBackfill.fill(app, it) }
            val sidecar = folder?.let { StoreGameSidecar.read(it) }
            sidecar?.let { refreshLauncher(folder, it) }
            val options = sidecar?.epic
            if (folder == null) CodeResult(false, "not-installed")
            else if (options != null && !options.wantsCode) {
                File(folder, EPIC_CODE).delete()
                CodeResult(false, if (options.offline) "offline" else "eos-off")
            } else {
                val file = File(folder, EPIC_CODE)
                file.delete()
                val support = StoresState.backend(Store.EPIC) as? EpicLaunchSupport
                val code = support?.exchangeCode(app)
                when {
                    support == null -> CodeResult(false, "no-backend")
                    code == null && !StoresNative.available -> CodeResult(false, "engine-unavailable")
                    code != null -> {
                        file.writeText(code)
                        writeOwnershipToken(app, folder, sidecar, support)
                        CodeResult(true, "ok")
                    }
                    StoreAccounts.signedInAs(app, Store.EPIC) == null -> CodeResult(false, "signed-out")
                    support.signInExpired(app) -> { StoresState.markSignInExpired(Store.EPIC); CodeResult(false, "sign-in-expired") }
                    else -> CodeResult(false, "exchange-failed")
                }
            }
        } catch (e: Exception) {
            CodeResult(false, "error-" + e.javaClass.simpleName)
        }
        Log.i(TAG, "epic launch id=$id code=${if (result.written) "yes" else "no"} reason=${result.reason}")
        return result
    }

    /** Writes (or removes) the game's ownership verification token next to the launcher; see EpicLaunchSupport. */
    private fun writeOwnershipToken(context: Context, folder: File, sidecar: StoreGameSidecar?, support: EpicLaunchSupport) {
        val file = File(folder, EPIC_OVT)
        file.delete()
        val namespace = sidecar?.extra?.get("namespace").orEmpty()
        val item = sidecar?.extra?.get("catalogItemId").orEmpty()
        if (namespace.isEmpty() || item.isEmpty()) return
        val token = runCatching { support.ownershipToken(context, namespace, item) }.getOrNull() ?: return
        file.writeBytes(token)
        Log.i(TAG, "epic ownership token written for ${sidecar?.id} (${token.size} bytes)")
    }

    fun prepare(context: Context, game: Library.SteamGame, onReady: () -> Unit) {
        val store = Store.byId(game.source)
        val id = game.storeId
        if (store == null || id == null) { onReady(); return }
        prepare(context, store, id, onReady)
    }
}

interface EpicLaunchSupport {
    fun exchangeCode(context: Context): String?
    fun signInExpired(context: Context): Boolean
    /** The ownership verification token for games whose catalog entry asks for one (`OwnershipToken`), else null. */
    fun ownershipToken(context: Context, namespace: String, catalogItemId: String): ByteArray? = null
}
