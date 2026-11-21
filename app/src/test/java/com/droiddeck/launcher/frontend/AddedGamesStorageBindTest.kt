package com.droiddeck.launcher.frontend

import android.content.Context
import android.os.Environment
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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
        SessionPrefs.setAddedGameRootsMigrated(app, true)
        GamesFolderMigration.rootsTried = 0L
        stick = tmp.newFolder("0A1B-2C3D")
        volumes = AddedGames.volumeRoots
        AddedGames.volumeRoots = { listOf(stick) }
    }

    @After fun tearDown() { AddedGames.volumeRoots = volumes }

    private fun added(result: AddedGames.AddResult) = (result as AddedGames.AddResult.Added).game

    private val g = AddedGames.GUEST_DIR

    private fun migrate() {
        SessionPrefs.setAddedGameRootsMigrated(app, false)
        GamesFolderMigration.rootsTried = 0L
        GamesFolderMigration.run(app)
    }

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

    @Test fun theUpdateMovesAnExeFolderBindToItsGameRootOnceAndKeepsTheAppId() {
        val root = File(stick, "Cyberpunk 2077")
        val x64 = File(root, "bin/x64")
        val pick = exe(File(x64, "Cyberpunk2077.exe"))
        File(root, "archive/pc/content").mkdirs()
        SessionPrefs.setGameFolderBinds(app, listOf(x64.path to "$g/x64"))
        AddedExes.put(app, AddedExes.Entry(x64.path, pick.path))
        SessionPrefs.setAddedGameStartIn(app, x64.path, "$g/x64")
        SessionPrefs.setAddedGameLaunch(app, x64.path, "-skipStartScreen --dir=$g/x64/mods $g/x64-2/x")
        val old = AddedGames.single(app, x64.path)!!
        assertEquals("$g/x64/Cyberpunk2077.exe", old.guestExe)

        migrate()

        val binds = listOf(root.path to "$g/Cyberpunk 2077")
        assertEquals(binds, SessionPrefs.gameFolderBinds(app))
        assertTrue(SessionPrefs.addedGameRootsMigrated(app))
        val now = AddedGames.single(app, x64.path)!!
        assertEquals(old.appId, now.appId)
        assertEquals("$g/Cyberpunk 2077/bin/x64/Cyberpunk2077.exe", now.guestExe)
        assertEquals("$g/Cyberpunk 2077/bin/x64", now.guestDir)
        assertEquals("-skipStartScreen --dir=$g/Cyberpunk 2077/bin/x64/mods $g/x64-2/x", SessionPrefs.addedGameLaunch(app, x64.path))
        assertEquals("$g/Cyberpunk 2077/archive/pc/content", AddedGames.guestPath(app, File(root, "archive/pc/content")))

        migrate()
        assertEquals(binds, SessionPrefs.gameFolderBinds(app))
        assertEquals(old.appId, AddedGames.single(app, x64.path)!!.appId)
        assertEquals("$g/Cyberpunk 2077/bin/x64", SessionPrefs.addedGameStartIn(app, x64.path))
    }

    @Test fun theBindOfAMovedFolderIsDroppedAndTheMovedGameResolvesThroughTheOuterBind() {
        val oldX64 = File(stick, "Cyberpunk 2077/bin/x64")
        val games = File(stick, "Games")
        val moved = File(games, "Cyberpunk 2077")
        val pick = exe(File(moved, "bin/x64/Cyberpunk2077.exe"))
        SessionPrefs.setGameFolderBinds(app, listOf(oldX64.path to "$g/x64", games.path to "$g/Games"))
        AddedExes.put(app, AddedExes.Entry(oldX64.path, File(oldX64, "Cyberpunk2077.exe").path))
        AddedExes.put(app, AddedExes.Entry(moved.path, pick.path))
        SessionPrefs.setAddedGameExe(app, moved.path, pick.path)

        migrate()

        assertEquals(listOf(games.path to "$g/Games"), SessionPrefs.gameFolderBinds(app))
        assertTrue(SessionPrefs.addedGameRootsMigrated(app))
        val listed = AddedGames.scan(app)
        assertEquals(listOf(moved.path), listed.map { it.folder.path })
        assertEquals("$g/Games/Cyberpunk 2077/bin/x64/Cyberpunk2077.exe", listed.single().guestExe)
    }

    @Test fun aBindOnAnUnpluggedVolumeWaitsAndIsMovedOnceTheVolumeIsBack() {
        val other = File(tmp.root, "9F9F-0000")
        val x64 = File(other, "Game/bin/x64")
        SessionPrefs.setGameFolderBinds(app, listOf(x64.path to "$g/x64"))
        AddedExes.put(app, AddedExes.Entry(x64.path, File(x64, "Game.exe").path))

        migrate()
        assertEquals(listOf(x64.path to "$g/x64"), SessionPrefs.gameFolderBinds(app))
        assertFalse(SessionPrefs.addedGameRootsMigrated(app))

        exe(File(x64, "Game.exe"))
        AddedGames.volumeRoots = { listOf(stick, other) }
        GamesFolderMigration.run(app)
        assertEquals(listOf(x64.path to "$g/x64"), SessionPrefs.gameFolderBinds(app))
        GamesFolderMigration.rootsTried = 0L
        GamesFolderMigration.run(app)
        assertEquals(listOf(File(other, "Game").path to "$g/Game"), SessionPrefs.gameFolderBinds(app))
        assertTrue(SessionPrefs.addedGameRootsMigrated(app))
    }

    @Test fun nestedBindsOnTwoVolumesCollapseToTheOutermostOnEach() {
        val second = tmp.newFolder("5E5E-1111")
        AddedGames.volumeRoots = { listOf(stick, second) }
        val c = File(stick, "C").apply { File(this, "B/A").mkdirs() }
        val d = File(second, "C").apply { File(this, "x64").mkdirs() }
        SessionPrefs.setGameFolderBinds(app, listOf(
            File(c, "B/A").path to "$g/A", File(c, "B").path to "$g/B", c.path to "$g/C",
            File(d, "x64").path to "$g/x64", d.path to "$g/C-2"))
        SessionPrefs.setAddedGameStartIn(app, "/a", "$g/A/sub")
        SessionPrefs.setAddedGameStartIn(app, "/x", "$g/x64")

        GamesFolderMigration.run(app)

        assertEquals(listOf(c.path to "$g/C", d.path to "$g/C-2"), SessionPrefs.gameFolderBinds(app))
        assertEquals("$g/C/B/A/sub", SessionPrefs.addedGameStartIn(app, "/a"))
        assertEquals("$g/C-2/x64", SessionPrefs.addedGameStartIn(app, "/x"))
    }

    @Test fun aTopFolderHoldingTheGameStorageBindsOnlyTheGameFolder() {
        val shelf = File(stick, "Shelf")
        val library = File(shelf, "Library").apply { mkdirs() }
        SessionPrefs.setGameStorage(app, library.path, "Stick")
        val stored = exe(File(library, "Stored/Stored.exe"))
        val pick = exe(File(shelf, "Foo/bin/x64/Foo.exe"))
        val game = added(AddedGames.addExe(app, pick))
        assertEquals(listOf(File(shelf, "Foo").path to "$g/Foo"), SessionPrefs.gameFolderBinds(app))
        assertEquals(File(shelf, "Foo").path, game.folder.path)
        assertEquals("$g/Foo/bin/x64/Foo.exe", game.guestExe)
        assertEquals("/mnt/droiddeck-sd/Stored/Stored.exe", AddedGames.guestPath(app, stored))
    }

    @Test fun aTopFolderHoldingAStoreRootBindsOnlyTheGameFolder() {
        val stuff = File(stick, "Stuff")
        val custom = File(stuff, "Stores").apply { mkdirs() }
        SessionPrefs.setDefaultStorageFolder(app, custom.path, "Stick")
        val storeGame = exe(File(custom, "Games/Epic/Game.exe"))
        val before = AddedGames.guestPath(app, storeGame)!!
        assertTrue(before, before.startsWith("/mnt/droiddeck-stores/"))
        val pick = exe(File(stuff, "Game/bin/x64/g.exe"))
        val game = added(AddedGames.addExe(app, pick))
        assertEquals(listOf(File(stuff, "Game").path to "$g/Game"), SessionPrefs.gameFolderBinds(app))
        assertEquals("$g/Game/bin/x64/g.exe", game.guestExe)
        assertEquals(before, AddedGames.guestPath(app, storeGame))
    }

    @Test fun theMigrationDoesNotCoverAStoreRootEither() {
        val stuff = File(stick, "Stuff")
        val custom = File(stuff, "Stores").apply { mkdirs() }
        SessionPrefs.setDefaultStorageFolder(app, custom.path, "Stick")
        File(custom, "Games").mkdirs()
        val x64 = File(stuff, "Game/bin/x64")
        val pick = exe(File(x64, "g.exe"))
        SessionPrefs.setGameFolderBinds(app, listOf(x64.path to "$g/x64"))
        AddedExes.put(app, AddedExes.Entry(x64.path, pick.path))
        migrate()
        assertEquals(listOf(File(stuff, "Game").path to "$g/Game"), SessionPrefs.gameFolderBinds(app))
    }

    @Test fun anUnreadableTopFolderIsUnreachable() {
        val top = File(stick, "Locked")
        val pick = exe(File(top, "bin/g.exe"))
        top.setReadable(false, false)
        try {
            assumeFalse(top.canRead())
            assertEquals(AddedGames.AddResult.Unreachable, AddedGames.addExe(app, pick))
            assertEquals(0, SessionPrefs.gameFolderBinds(app).size)
            assertEquals(0, AddedExes.list(app).size)
        } finally {
            top.setReadable(true, false)
        }
    }

    @Test fun theRealVolumeLookupFindsARemovableVolume() {
        AddedGames.volumeRoots = volumes
        assertEquals(emptyList<File>(), AddedGames.volumeRoots(app))
        val primary = app.getExternalFilesDir(null)!!
        val card = tmp.newFolder("ABCD-1234")
        val cardFiles = File(card, "Android/data/${app.packageName}/files").apply { mkdirs() }
        val withCard = object : android.content.ContextWrapper(app) {
            override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(primary, cardFiles)
        }
        assertEquals(listOf(card.absolutePath), AddedGames.volumeRoots(withCard).map { it.absolutePath })
        assertEquals(File(card, "Game").path, AddedGames.topFolder(AddedGames.volumeRoots(withCard).single(), File(card, "Game/bin/x64/Game.exe"))?.path)
    }
}
