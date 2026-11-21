package com.droiddeck.launcher.frontend

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AddedGamesStorageBindTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var stick: File
    private lateinit var volumes: (Context) -> List<File>

    private fun exe(path: File): File = path.apply { parentFile!!.mkdirs(); writeBytes(ByteArray(16)) }

    @Before fun setUp() {
        AddedGamesPublisher.now = true
        SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
        SessionPrefs.setAddedGamesDirs(app, emptyList())
        stick = tmp.newFolder("0A1B-2C3D")
        volumes = AddedGames.volumeRoots
        AddedGames.volumeRoots = { listOf(stick) }
    }

    @After fun tearDown() { AddedGames.volumeRoots = volumes }

    private fun added(result: AddedGames.AddResult) = (result as AddedGames.AddResult.Added).game

    @Test fun anExeDeepInAStickFolderBindsTheGameRoot() {
        val root = File(stick, "Cyberpunk 2077")
        File(root, "archive/pc/content").mkdirs()
        val pick = exe(File(root, "bin/x64/Cyberpunk2077.exe"))
        val game = added(AddedGames.addExe(app, pick))
        assertEquals(listOf(root.path to "${AddedGames.GUEST_DIR}/Cyberpunk 2077"), SessionPrefs.gameFolderBinds(app))
        assertEquals(root.path, game.folder.path)
        assertEquals("${AddedGames.GUEST_DIR}/Cyberpunk 2077/bin/x64/Cyberpunk2077.exe", game.guestExe)
        assertEquals("${AddedGames.GUEST_DIR}/Cyberpunk 2077/bin/x64", game.guestDir)
        assertEquals("${AddedGames.GUEST_DIR}/Cyberpunk 2077/archive/pc/content", AddedGames.guestPath(app, File(root, "archive/pc/content")))
        assertTrue(AddedGames.addExe(app, pick) is AddedGames.AddResult.Existing)
        assertEquals(1, SessionPrefs.gameFolderBinds(app).size)
    }

    @Test fun twoGamesInAContainerFolderKeepTheirOwnFoldersAndAppIds() {
        val games = File(stick, "Games")
        val a = exe(File(games, "A/bin/x64/a.exe"))
        val b = exe(File(games, "B/b.exe"))
        val first = added(AddedGames.addExe(app, a))
        val second = added(AddedGames.addExe(app, b))
        assertEquals(listOf(games.path to "${AddedGames.GUEST_DIR}/Games"), SessionPrefs.gameFolderBinds(app))
        assertEquals(File(games, "A").path, first.folder.path)
        assertEquals(File(games, "B").path, second.folder.path)
        assertEquals("${AddedGames.GUEST_DIR}/Games/A/bin/x64/a.exe", first.guestExe)
        assertEquals("${AddedGames.GUEST_DIR}/Games/B/b.exe", second.guestExe)
        assertNotEquals(first.appId, second.appId)
        assertEquals(2, AddedGames.scan(app).size)
    }

    @Test fun aFolderHoldingSeveralGamesIsNotTheGameFolder() {
        val shelf = File(stick, "Shelf")
        val a = exe(File(shelf, "Alpha/Alpha.exe"))
        exe(File(shelf, "Beta/Beta.exe"))
        assertEquals(File(shelf, "Alpha").path, added(AddedGames.addExe(app, a)).folder.path)
        assertEquals(listOf(shelf.path to "${AddedGames.GUEST_DIR}/Shelf"), SessionPrefs.gameFolderBinds(app))
    }

    @Test fun theVolumeRootIsNeverBound() {
        val loose = exe(File(stick, "loose.exe"))
        assertEquals(AddedGames.AddResult.Unreachable, AddedGames.addExe(app, loose))
        assertEquals(0, SessionPrefs.gameFolderBinds(app).size)
        assertEquals(0, AddedExes.list(app).size)
        assertNull(AddedGames.topFolder(stick, loose))
        assertEquals(File(stick, "Game").path, AddedGames.topFolder(stick, File(stick, "Game/bin/x64/g.exe"))?.path)
    }

    @Test fun theOutermostBindWins() {
        val root = File(stick, "Game").apply { mkdirs() }
        val roots = listOf(AddedGames.Root(File(root, "bin/x64"), "${AddedGames.GUEST_DIR}/x64"), AddedGames.Root(root, "${AddedGames.GUEST_DIR}/Game"))
        assertEquals("${AddedGames.GUEST_DIR}/Game/bin/x64/g.exe", AddedGames.bindGuestPath(roots, File(root, "bin/x64/g.exe")))
        assertEquals("${AddedGames.GUEST_DIR}/Game/bin/x64/g.exe", AddedGames.bindGuestPath(roots.reversed(), File(root, "bin/x64/g.exe")))
    }

    @Test fun reAddingAGameBoundAtItsExeFolderBindsTheGameRootAndKeepsItsAppId() {
        val root = File(stick, "Cyberpunk 2077")
        val x64 = File(root, "bin/x64")
        val pick = exe(File(x64, "Cyberpunk2077.exe"))
        SessionPrefs.setGameFolderBinds(app, listOf(x64.path to "${AddedGames.GUEST_DIR}/x64"))
        AddedExes.put(app, AddedExes.Entry(x64.path, pick.path))
        val old = AddedGames.single(app, x64.path)!!
        assertEquals("${AddedGames.GUEST_DIR}/x64/Cyberpunk2077.exe", old.guestExe)
        AddedGames.remove(app, x64, old.appId)

        val again = AddedGames.addExe(app, pick) as AddedGames.AddResult.Switched
        assertEquals(old.appId, again.game.appId)
        assertEquals("${AddedGames.GUEST_DIR}/Cyberpunk 2077/bin/x64/Cyberpunk2077.exe", again.game.guestExe)
        assertEquals("${AddedGames.GUEST_DIR}/Cyberpunk 2077/bin/x64", again.game.guestDir)
        assertEquals(listOf(root.path to "${AddedGames.GUEST_DIR}/Cyberpunk 2077"), SessionPrefs.gameFolderBinds(app))
    }

    @Test fun pickingAListedGameAgainBindsItsRootAndKeepsTheGame() {
        val root = File(stick, "Game")
        val x64 = File(root, "bin/x64")
        val pick = exe(File(x64, "Game.exe"))
        SessionPrefs.setGameFolderBinds(app, listOf(x64.path to "${AddedGames.GUEST_DIR}/x64"))
        AddedExes.put(app, AddedExes.Entry(x64.path, pick.path))
        val old = AddedGames.single(app, x64.path)!!
        val again = AddedGames.addExe(app, pick) as AddedGames.AddResult.Existing
        assertEquals(old.appId, again.game.appId)
        assertEquals("${AddedGames.GUEST_DIR}/Game/bin/x64/Game.exe", again.game.guestExe)
    }

    @Test fun pruningANestedBindKeepsAppIdsAndMovesTheStartIn() {
        val root = File(stick, "Game")
        val x64 = File(root, "bin/x64")
        val pick = exe(File(x64, "Game.exe"))
        File(x64, "data").mkdirs()
        SessionPrefs.setGameFolderBinds(app, listOf(x64.path to "${AddedGames.GUEST_DIR}/x64"))
        AddedExes.put(app, AddedExes.Entry(x64.path, pick.path))
        SessionPrefs.setAddedGameStartIn(app, x64.path, "${AddedGames.GUEST_DIR}/x64/data")
        SessionPrefs.setAddedGameStartIn(app, "/elsewhere", "${AddedGames.GUEST_DIR}/x64-2")
        val old = AddedGames.single(app, x64.path)!!
        SessionPrefs.setGameFolderBinds(app, SessionPrefs.gameFolderBinds(app) + (root.path to "${AddedGames.GUEST_DIR}/Game"))

        GamesFolderMigration.run(app)

        assertEquals(listOf(root.path to "${AddedGames.GUEST_DIR}/Game"), SessionPrefs.gameFolderBinds(app))
        assertEquals("${AddedGames.GUEST_DIR}/Game/bin/x64/data", SessionPrefs.addedGameStartIn(app, x64.path))
        assertEquals("${AddedGames.GUEST_DIR}/x64-2", SessionPrefs.addedGameStartIn(app, "/elsewhere"))
        val now = AddedGames.single(app, x64.path)!!
        assertEquals(old.appId, now.appId)
        assertEquals("${AddedGames.GUEST_DIR}/Game/bin/x64/Game.exe", now.guestExe)
        assertEquals("${AddedGames.GUEST_DIR}/Game/bin/x64/data", now.guestDir)
    }

    @Test fun internalStorageKeepsTheExeFolderAndBindsNothing() {
        val pick = exe(File(Environment.getExternalStorageDirectory(), "Download/Game/bin/x64/Game.exe"))
        val game = added(AddedGames.addExe(app, pick))
        assertEquals(pick.parent, game.folder.path)
        assertEquals("/root/Storage/Download/Game/bin/x64/Game.exe", game.guestExe)
        assertEquals(0, SessionPrefs.gameFolderBinds(app).size)
    }

    @Test fun anExeOutsideEveryVolumeStillBindsItsFolder() {
        val outside = exe(File(tmp.newFolder("card"), "Hades/x64/Hades.exe"))
        added(AddedGames.addExe(app, outside))
        assertEquals(listOf(outside.parent to "${AddedGames.GUEST_DIR}/x64"), SessionPrefs.gameFolderBinds(app))
    }
}
