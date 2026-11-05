@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class, androidx.compose.ui.InternalComposeUiApi::class, androidx.compose.runtime.InternalComposeApi::class)
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package local.spw.mv

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint.MediaItem
import java.lang.reflect.Proxy
import java.nio.file.*
import java.util.prefs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import org.jetbrains.skia.Surface

private var checks = 0
private fun verify(value: Boolean, message: String) { check(value) { message }; checks++ }
private inline fun fails(action: () -> Unit) { var failed = false; try { action() } catch (_: Exception) { failed = true }; verify(failed, "Expected failure") }
private fun song(path: Path, title: String = "晴天", artist: String = "周杰伦") = MediaItem(title, artist, "", "", path.toString(), id = path.toString())

private fun libraryChecks(root: Path) {
    val folder = Files.createDirectory(root.resolve("MV 文件夹"))
    val audio = song(root.resolve("周杰伦 - 晴天.flac"))
    val exact = Files.write(folder.resolve("周杰伦 - 晴天.mp4"), byteArrayOf(1))
    val live = Files.write(folder.resolve("周杰伦 - 晴天 (Live).mkv"), byteArrayOf(1))
    val title = Files.write(folder.resolve("晴天.mp4"), byteArrayOf(1))
    val random = Files.write(folder.resolve("另一首歌.webm"), byteArrayOf(1))
    Files.write(folder.resolve("封面.jpg"), byteArrayOf(1))
    val nested = Files.createDirectory(folder.resolve("子文件夹"))
    val other = Files.write(nested.resolve("其他.mp4"), byteArrayOf(1))
    val videos = scanVideos(folder)
    verify(videos.size == 5 && other in videos, "Recursive video-only scan")
    verify(matchMv(audio, videos, null).file == exact, "Best audio filename is selected automatically")
    verify(matchMv(audio, listOf(title), null).file == title, "Unique title is selected automatically")
    verify(matchMv(audio, listOf(live), null).file == null, "Live version must not silently match studio")
    verify(matchMv(audio, videos, random).file == random, "Explicit binding overrides names")
    val duplicate = Files.write(nested.resolve("周杰伦 - 晴天.mkv"), byteArrayOf(1))
    verify(matchMv(audio, videos + listOf(duplicate), null).candidates.take(2).toSet() == setOf(exact, duplicate), "Both exact duplicates precede loose recommendations")
    verify(matchMv(audio, videos + listOf(duplicate), null).file == null, "Never pick one ambiguous file")
    verify(matchMv(audio, listOf(title), null, setOf("周杰伦", "其他歌手")).file == null, "Duplicate song titles without artist evidence require confirmation")
    verify(matchMv(audio, listOf(exact), null, setOf("周杰伦", "其他歌手")).candidates.single() == exact, "Artist-qualified name is suggested for confirmation")
    val messy = Files.write(folder.resolve("001_歌手【如果】 4Ｋ 高清修复 官方ＭＶ.mkv"), byteArrayOf(1))
    val ifSong = song(root.resolve("如果.flac"), "如果", "歌手")
    verify(matchMv(ifSong, listOf(messy), null).candidates.single() == messy, "Chinese title survives brackets, counters, full-width symbols and version suffixes")
    verify(matchMv(ifSong, listOf(messy), null).file == messy, "Bracketed Chinese title with artist is selected automatically")
    verify(matchMv(ifSong, listOf(folder.resolve("如果爱.mp4")), null).file == null, "A short title inside another title still needs confirmation")
    verify(matchMv(song(root.resolve("爱.flac"), "爱", ""), listOf(messy, folder.resolve("爱情故事.mp4")), null).candidates.isEmpty(), "Single-character titles do not match inside unrelated words")
    verify(matchMv(audio, listOf(title), folder.resolve("missing.mp4")).file == title, "Moved or renamed bound video can be rediscovered")
    val kiss = song(root.resolve("One Last Kiss - 宇多田ヒカル.flac"), "One Last Kiss", "宇多田ヒカル")
    val namedKiss = folder.resolve("One Last Kiss.mp4")
    val decoratedKiss = folder.resolve("【官方MV】宇多田光 - 《One Last Kiss》（电影《新·福音战士剧场版：终》主题曲）-1080P-AVC.mp4")
    verify(matchMv(kiss, listOf(namedKiss), null).file == namedKiss, "One Last Kiss exact filename is automatic")
    verify(matchMv(kiss, listOf(decoratedKiss), null).file == decoratedKiss, "One Last Kiss in the real decorated filename is automatic")
    val hotel = song(root.resolve("Hotel California (Live On MTV, 1994) - Eagles.flac"), "Hotel California (Live on MTV, 1994)", "Eagles")
    val misspelled = folder.resolve("【4K60FPS】老鹰乐队Eagles《Hotel Califormia》神级现场！人生一定要看的现场！.mp4")
    verify(matchMv(hotel, listOf(misspelled), null).file == misspelled, "Long Latin title tolerates one typo with artist and live evidence")
    verify(matchMv(audio.copy(title = "晴天 (Live)"), listOf(exact, live), null).file == live, "Live song chooses the live recording over the studio audio filename")
    verify(matchMv(kiss, listOf(folder.resolve("Other Last Song.mp4")), null).candidates.isEmpty(), "Unrelated Latin names do not match")
    verify(matchMv(audio, listOf(folder.resolve("其他歌手 - 晴天.mp4")), null, setOf("周杰伦", "其他歌手")).file == null, "Known different artist is not auto-selected")
    verify(mvName("ＡＢＣ－晴 天") == mvName("abc 晴天"), "Unicode normalization")
    verify(mvName("Beyoncé\u200b") == mvName("Beyonce") && mvName("宇多田ヒカル") != mvName("宇多田ビカル"), "Latin accents and hidden characters fold without dropping Japanese voicing")
    val halo = song(root.resolve("halo.flac"), "Halo (Official Music Video)", "Beyoncé")
    verify(matchMv(halo, listOf(folder.resolve("Beyonce - Halo 1080p.mp4")), null).file != null, "Official video suffix and artist accents match")
    val artistFolder = Files.createDirectory(folder.resolve("Justin Bieber"))
    val baby = song(root.resolve("baby.flac"), "Baby", "Justin Bieber/Ludacris")
    verify(matchMv(baby, listOf(artistFolder.resolve("Baby.mp4")), null, setOf("Justin Bieber", "Clean Bandit")).file != null, "Artist subfolder disambiguates a bare same-title video")
    verify(matchMv(baby.copy(title="One",artist=""), listOf(folder.resolve("Someone Else.mp4")), null).candidates.isEmpty(), "Short Latin titles never match inside another word")
    val joint = baby.copy(title="Stay",artist="The Kid LAROI/Justin Bieber")
    val rival = baby.copy(path=root.resolve("other.flac").toString(),title="Stay",artist="Zedd/Justin Bieber")
    val jointFile = folder.resolve("The Kid LAROI - Stay feat. Justin Bieber.mp4")
    verify(videoSong(jointFile,listOf(joint,rival)) == joint, "Primary artist outranks a shared featured artist")
    val longer = baby.copy(path=root.resolve("longer.flac").toString(), title="My Baby")
    verify(videoSong(folder.resolve("Justin Bieber - My Baby.mp4"),listOf(baby,longer)) == longer, "More specific full song title wins over a contained shorter title")
    verify(songTitle("Baby feat. MARINA & Luis Fonsi") == "Baby" && "luisfonsi" in songArtists(baby.copy(title="Baby feat. MARINA & Luis Fonsi")), "Unbracketed featured credits participate in matching")
    fails { scanVideos(folder.resolve("missing")) }
    val config = root.resolve("config/mv.properties")
    val store = MvStore(config)
    store.saveFolder(folder); store.setAutoOpen(false); store.bind(audio, random)
    val reopened = MvStore(config)
    verify(reopened.folder() == folder && !reopened.autoOpen() && reopened.bound(audio) == random, "Config survives restart")
    val rebuiltId = audio.copy(id = "new-library-id", duration = 1000)
    verify(reopened.bound(rebuiltId) == random, "Binding survives library ID regeneration")
    fails { reopened.bind(audio, folder.resolve("missing.mp4")) }
    verify(reopened.bound(audio) == random, "Invalid binding preserves existing config")
    reopened.bind(audio, null)
    verify(reopened.bound(audio) == null && reopened.folder() == folder, "Unbind keeps folder")
    reopened.setHiddenDelay(5); reopened.setDoubleClickFull(false)
    val preferences = MvStore(config)
    verify(preferences.blocked(audio) && preferences.hiddenDelay() == 5 && !preferences.doubleClickFull(), "Unlink exclusion and playback preferences survive restart")
    val readFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    val opened = java.util.concurrent.CountDownLatch(1)
    val reader = Thread {
        try {
            Files.newInputStream(config).use { opened.countDown(); Thread.sleep(40); it.readAllBytes() }
        }
        catch (error: Throwable) { readFailure.set(error) }
    }.apply { start() }
    check(opened.await(2, java.util.concurrent.TimeUnit.SECONDS))
    try { repeat(16) { reopened.bind(audio, if (it % 2 == 0) exact else random) } }
    finally { reader.join() }
    verify(readFailure.get() == null && MvStore(config).bound(audio) == random && !MvStore(config).blocked(audio), "Atomic binding replacement tolerates a bounded open Windows reader")
    val snapshot = Files.readAllBytes(config)
    Files.write(folder.resolve("notes.txt"), byteArrayOf(3))
    scanVideos(folder)
    verify(Files.readAllBytes(config).contentEquals(snapshot), "Scanning does not mutate bindings")
    val blocker = Files.write(root.resolve("not-a-directory"), byteArrayOf(1))
    fails { MvStore(blocker.resolve("config")).bind(audio, exact) }
    verify(Files.readAllBytes(exact).contentEquals(byteArrayOf(1)), "Failed persistence never writes media")
}

private fun redesignChecks(root: Path) {
    val a = song(root.resolve("Justin - Baby.flac"), "Baby", "Justin Bieber")
    val b = song(root.resolve("Clean Bandit - Baby.flac"), "Baby", "Clean Bandit Ft. Marina & Luis Fonsi")
    val fileA = Files.write(root.resolve("Baby - Justin Bieber.mp4"), byteArrayOf(1))
    val fileB = Files.write(root.resolve("Baby - Clean Bandit Ft. Marina & Luis Fonsi 中英字幕.mp4"), byteArrayOf(2))
    val names = setOf(a.artist, b.artist)
    verify(matchMv(a, listOf(fileA, fileB), null, names).file == fileA, "Same-title songs choose their own artist")
    verify(matchMv(b, listOf(fileA, fileB), null, names).file == fileB, "Featuring credits identify the correct MV")
    verify(matchMv(a, listOf(fileB), null, names).candidates.isEmpty(), "A known rival artist is not offered as a match")
    val generic = root.resolve("Baby.mp4")
    verify(matchMv(a, listOf(generic), null, names).file == null, "Identical titles without artist evidence require consent")
    val store = MvStore(root.resolve("exclusive/config.properties"))
    store.bind(a, fileB); store.bind(b, fileB)
    verify(store.bound(a) == null && store.bound(b) == fileB, "Reassigning a video clears the old song link atomically")
    verify(store.claimedElsewhere(a, fileB) && !store.claimedElsewhere(b, fileB), "A reassigned video cannot immediately auto-link to its old song")
    verify(MvStore(root.resolve("exclusive/config.properties")).bound(a) == null, "Exclusive MV ownership survives restart")
    for (aspect in MvAspect.entries) { store.setAspect(aspect); verify(store.aspect() == aspect, "Aspect choice persists: $aspect") }
    store.setPlaybackOrder("Random"); verify(MvStore(root.resolve("exclusive/config.properties")).playbackOrder() == "Random", "MV playback order persists independently")
    fails { store.setPlaybackOrder("bad") }
    val files = listOf(fileA, fileB, generic)
    verify(nextMv(files, fileA, false, "Circle") == fileB, "MV Next follows MV files")
    verify(nextMv(files, fileA, true, "Circle") == generic, "MV Previous wraps within MV files")
    verify(nextMv(files, generic, false, "CircleEnd") == null, "Sequential MV playback stops at its own list end")
    verify(nextMv(files, fileA, false, "RepeatOne") == fileB, "Manual Next advances even in repeat-one mode")
    verify((1..20).all { nextMv(files, fileA, false, "Random") != fileA }, "Random MV navigation avoids immediately repeating the same video")
    verify(nextMv(emptyList(), null, false, "Circle") == null, "Empty MV list is safe")
    val entries = listOf(MvQueueEntry(fileA, a), MvQueueEntry(fileB, b))
    verify(filterMv(entries, "baby clean").single().file == fileB, "Search combines song and artist words")
    verify(filterMv(entries, "中英字幕").single().file == fileB, "Search finds video filename suffixes")
    verify(filterMv(entries, "不存在").isEmpty(), "Search has a real empty state")
    val named = listOf(MvQueueEntry(root.resolve("Artist A - Baby.mp4")), MvQueueEntry(root.resolve("Artist B - Baby.mp4")))
    verify(filterMv(named, "Artist B").single().file == named[1].file, "One-letter artist search never matches a letter inside the title")
    val actualA = a.copy(title = "Baby", artist = "Justin Bieber/Ludacris")
    val actualB = b.copy(title = "Baby (feat. MARINA & Luis Fonsi)", artist = "Clean Bandit/MARINA/Luis Fonsi")
    val actualFile = root.resolve("Baby – Clean Bandit Ft. Marina & Luis Fonsi 中英字幕 太极狼翻译.mp4")
    val actualPeers = songArtists(actualA) + songArtists(actualB)
    verify(mvName(songTitle(actualB.title)) == mvName(songTitle(actualA.title)), "Featuring titles belong to the same title conflict group")
    verify(matchMv(actualA, listOf(actualFile), null, actualPeers).candidates.isEmpty(), "Actual Baby video never matches Justin Bieber")
    verify(matchMv(actualB, listOf(actualFile), null, actualPeers).file == actualFile, "Actual Baby featuring Luis Fonsi selects the correct video")
    verify(videoSong(actualFile, listOf(actualA, actualB)) == actualB, "Reverse video association chooses the credited Baby song")
    verify(matchMv(actualA, listOf(root.resolve("Justinbiber - Baby.mp4")), null, actualPeers).file != null, "Long artist names tolerate one filename typo")
    store.bind(a, fileB, automatic = true)
    verify(store.automatic(a), "Automatic association provenance survives writes")
    store.bind(a, fileB)
    verify(!MvStore(root.resolve("exclusive/config.properties")).automatic(a), "Explicit manual association is protected from automatic repair")
    val sidebar = MvNavigationHooks.sidebar
    verify(local.spw.host.HookInternals.method(sidebar.declaringClass.name, sidebar.name, MvNavigationHooks.sidebarParameters) != null, "Native registrar accepts the sidebar builder method hook")
    val navigation = MvNavigationHooks.navigation
    verify(local.spw.host.HookInternals.ui(navigation.declaringClass.name, navigation.name, MvNavigationHooks.navigationCaller, emptyList()) != null, "Native registrar accepts MV library navigation hook")
    verify(store.libraryLayout() == "List", "Library defaults to native list layout")
    store.setLibraryLayout("Grid")
    verify(MvStore(root.resolve("exclusive/config.properties")).libraryLayout() == "Grid", "Library layout persists across reloads")
    fails { store.setLibraryLayout("Other") }
    verify(mvGridColumns(360f) == 1 && mvGridColumns(1000f) == 4 && mvGridColumns(1800f) == 8, "Grid keeps readable covers and twenty-point gaps across window sizes")
    val directory = Files.createDirectory(root.resolve("browse"))
    val child = Files.createDirectory(directory.resolve("child"))
    Files.writeString(directory.resolve("file.txt"), "not a folder")
    verify(directoryFolders(directory) == listOf(child), "In-app directory browser returns folders only")
    fails { directoryFolders(root.resolve("missing")) }
}

private fun handoffChecks(root: Path) {
    val frameType = HostBridge.resolver.type("com.moriafly.salt.ui.window.SaltWindowProperties")
    verify(MvHostWindow.captionOverrides.size == 4, "Caption adaptation keeps button and bar heights consistent")
    for ((getter, hidden) in MvHostWindow.captionOverrides) {
        verify(getter.declaringClass == frameType && getter.parameterCount == 0, "Caption hook targets a plain properties getter")
        verify((getter.returnType == Float::class.javaPrimitiveType && hidden == 0f) ||
            (getter.returnType == Boolean::class.javaPrimitiveType && hidden == false), "Caption override preserves the native getter type")
        verify(local.spw.host.HookInternals.method(getter.declaringClass.name, getter.name, emptyList()) != null,
            "Native registrar accepts the caption property getter")
    }
    val nativeState = androidx.compose.ui.window.WindowState(position = androidx.compose.ui.window.WindowPosition(120.dp, 80.dp), size = androidx.compose.ui.unit.DpSize(1120.dp, 760.dp))
    val before = MvWindowSnapshot(nativeState)
    nativeState.placement = androidx.compose.ui.window.WindowPlacement.Fullscreen
    val fullState = MvWindowSnapshot(nativeState)
    nativeState.placement = androidx.compose.ui.window.WindowPlacement.Floating
    fullState.restore(nativeState)
    verify(nativeState.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen && nativeState.size == before.size && nativeState.position == before.position, "Fullscreen round trip preserves the host floating restore history")
    val a = song(root.resolve("a.flac")); val b = song(root.resolve("b.flac"))
    var seeks = 0; var plays = 0
    val held = SongHandoff(a, 12345, true)
    held.restore(b, { seeks++ }, { plays++ })
    held.restore(null, { seeks++ }, { plays++ })
    verify(seeks == 0 && plays == 0, "Never resume old song after a switch")
    held.restore(a, { verify(it == 12345L, "Restore original position"); seeks++ }, { plays++ })
    verify(seeks == 1 && plays == 1, "Restore original playing state")
    SongHandoff(a, 0, false).restore(a, { seeks++ }, { plays++ })
    verify(plays == 1, "Originally paused song stays paused")
    verify(displayTime(Double.NaN) == "00:00" && displayTime(-2.0) == "00:00", "Invalid time display")
    verify(displayTime(3661.0) == "1:01:01", "Hour-long MV time")
}

private fun keyChecks() {
    val shortcuts = SpwShortcuts.bindings()
    verify(SpwShortcuts.handles("PLAY_PAUSE", true, false), "Global playback controls the background MV")
    verify(!SpwShortcuts.handles("BACK", true, false) && SpwShortcuts.handles("BACK", true, true), "Back only controls the focused MV")
    verify(!SpwShortcuts.handles("NEXT", false, true), "Return host shortcuts when MV is closed")
    verify(!SpwShortcuts.handles("SEARCH", true, true), "Unrelated host actions remain untouched")
    verify(shortcuts.any { it.key == java.awt.event.KeyEvent.VK_RIGHT && it.action == "FORWARD_5S" }, "Read real SPW seek binding")
    val handler = SpwShortcuts.handler
    verify(local.spw.host.HookInternals.method(handler.declaringClass.name, handler.name, handler.parameterTypes.map { it.name }) != null, "Resolve shared app/global shortcut handler")
    val events = mutableListOf<String>()
    var pending: (() -> Unit)? = null
    var configured = listOf(MvShortcut(39, 0, "FORWARD_5S"), MvShortcut(37, 0, "BACKWARD_5S"), MvShortcut(78, 2, "NEXT"))
    val keys = MvKeys({ configured }, { events += it }, { events += "boost:$it" }, { events += "fullscreen" }, { _, run -> pending = run; ({ pending = null }) })
    verify(keys.key(39, 0, true) && events.isEmpty(), "Arrow down delays seeking")
    keys.key(39, 0, true); keys.key(39, 0, false)
    verify(events == listOf("FORWARD_5S") && pending == null, "A short arrow executes the configured seek exactly once")
    events.clear(); keys.key(39, 0, true); pending!!(); keys.key(39, 0, false)
    verify(events == listOf("boost:2.0", "boost:null"), "Hold right boosts to 2x, restores on release, never seeks")
    events.clear(); keys.key(37, 0, true); pending!!(); keys.cancel()
    verify(events == listOf("boost:3.0", "boost:null") && pending == null, "Hold left boosts to 3x and focus loss restores speed")
    events.clear(); keys.key(39, 0, true); pending!!(); keys.key(37, 0, true); pending!!()
    keys.key(39, 0, false); keys.key(37, 0, false)
    verify(events == listOf("boost:2.0", "boost:null", "boost:3.0", "boost:null"), "Opposite arrow holds cannot leave a stuck boost or seek")
    events.clear(); keys.key(78, 2, true); keys.key(78, 2, true); keys.key(78, 2, false)
    verify(events == listOf("NEXT"), "Custom SPW next binding respects modifiers and repeat suppression")
    configured = listOf(MvShortcut(80, 1, "PREVIOUS")); events.clear()
    verify(!keys.key(78, 2, true), "Removed host shortcuts stop working immediately")
    keys.key(80, 1, true); keys.key(80, 1, false)
    verify(events == listOf("PREVIOUS"), "Reload changed SPW bindings")
    keys.key(116, 0, true); keys.key(122, 0, true); keys.key(122, 0, false)
    verify(events.last() == "fullscreen", "F11 toggles fullscreen")
    keys.cancel()
}

private fun nativeChecks() {
    val root = MvPageHook.original
    val rootTarget = local.spw.host.HookInternals.ui(root.declaringClass.name, root.name, MvPageHook.caller, MvPageHook.parameters)
    verify(!local.spw.host.HookInternals.bytes(MvPageHook.caller).contentEquals(local.spw.host.HookInternals.transform(root, MvPageHook.caller, rootTarget)), "Official transformer accepts the actual host root call")
    verify(local.spw.host.HookInternals.callDefaultMask(root, MvPageHook.caller) == 0, "Real MainWindowContent root call supplies all arguments")
    val rootProbe = local.spw.host.HookInternals.probe(root, MvPageHook.caller)
    verify(MvEntryHook.parameters.isNotEmpty(), "Resolve native menu row call")
    verify(MvEntryHook.caller == HostBridge.resolver.facade("MiniPlayer").name, "Verify real playback control row")
    val target = local.spw.host.HookInternals.ui(MvEntryHook.original.declaringClass.name, MvEntryHook.original.name, MvEntryHook.caller, MvEntryHook.parameters)
    verify(!local.spw.host.HookInternals.bytes(MvEntryHook.caller).contentEquals(local.spw.host.HookInternals.transform(MvEntryHook.original, MvEntryHook.caller, target)), "Actual SPW transformer accepts MV caption entry")
    val old = HostBridge.resolver.composable("MiniPlayerMenu", 6, null)
    val oldCaller = HostBridge.resolver.facade("PlatformMiniPlayerTrailingContent").name
    verify(local.spw.host.HookInternals.callDefaultMask(old, oldCaller) == 4, "Reproduce 0.1.2: native inner call defaults viewModel")
    verify(local.spw.host.HookInternals.callDefaultMask(MvEntryHook.original, MvEntryHook.caller) == 0, "New native call supplies every argument explicitly")
    val oldProbe = local.spw.host.HookInternals.probe(old, oldCaller)
    val probe = local.spw.host.HookInternals.probe(MvEntryHook.original, MvEntryHook.caller)
    val config = java.nio.file.Path.of("src/main/resources/preference_config.json")
    val definition = Files.readString(config)
    val sidebarPreference = kotlinx.serialization.json.Json.parseToJsonElement(definition).jsonObject["configs"]!!.jsonArray.single().jsonObject["preferences"]!!.jsonArray
        .single { it.jsonObject["key"]?.jsonPrimitive?.content == "show_sidebar" }.jsonObject
    verify(sidebarPreference["type"]!!.jsonPrimitive.content == "switch" && sidebarPreference["default_value"]!!.jsonPrimitive.boolean, "Sidebar visibility is a native persisted switch enabled by default")
    for (name in listOf("chooseFolder", "rescan", "bindCurrent", "unbindCurrent", "resumeMatching")) {
        verify(java.lang.reflect.Modifier.isStatic(MvPlugin::class.java.getMethod(name).modifiers) && definition.contains("MvPlugin.$name"), "Config action is callable: $name")
    }
    javax.swing.SwingUtilities.invokeAndWait {
        var enabled by mutableStateOf(true)
        var opened = 0
        var queued = 0
        val hook = MvEntryHook({ enabled }) { opened++ }
        local.spw.host.HookInternals.ProbeBridge.hook = hook
        local.spw.host.HookInternals.ProbeBridge.replacements = 0
        val frame = FrameRecomposer(Dispatchers.Unconfined)
        val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(1f), size = IntSize(900, 650))
        val surface = Surface.makeRasterN32Premul(900, 650)
        val navigatorLocal = Class.forName("com.xuncorp.voxzen.ui.navigation.AppNavigationKt").getMethod("getLocalNavigator").invoke(null) as ProvidableCompositionLocal<Any>
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        val navigator = unsafe.allocateInstance(com.xuncorp.voxzen.ui.navigation.Navigator::class.java)
        try {
            local.spw.host.HookInternals.ProbeBridge.hook = object : com.xuncorp.spw.workshop.api.hook.ComposableHook() {
                @Composable override fun Content(call: com.xuncorp.spw.workshop.api.hook.UiHookCall) { NativeUi.Text("MV root") }
            }
            scene.setContent { NativeUi.Theme { HostBridge.invoke(rootProbe, null, false, currentComposer, 0, 0) } }
            frame.performFrame(0); scene.measureAndLayout(); scene.draw(surface.canvas.asComposeCanvas())
            verify(local.spw.host.HookInternals.ProbeBridge.replacements > 0, "Real root call dispatches the MV page through the official bridge")
            local.spw.host.HookInternals.ProbeBridge.hook = hook
            local.spw.host.HookInternals.ProbeBridge.replacements = 0
            scene.setContent { CompositionLocalProvider(navigatorLocal provides navigator) { NativeUi.Theme {
                HostBridge.invoke(oldProbe, null, { 1f }, Modifier, null, currentComposer, 0, 4)
            } } }
            frame.performFrame(0); scene.measureAndLayout(); surface.canvas.clear(0xFF000000.toInt()); scene.draw(surface.canvas.asComposeCanvas())
            verify(local.spw.host.HookInternals.ProbeBridge.replacements == 0, "Real SPW transformer skips the old replacement with default arguments")
            scene.setContent { CompositionLocalProvider(navigatorLocal provides navigator) { NativeUi.Theme {
                Column(Modifier.fillMaxSize()) {
                    NativeUi.Text("MV 播放 · 晴天", size = 20)
                    Row(Modifier.width(500.dp)) {
                        NativeUi.Control("播放队列", "≡") { queued++ }
                        Box(Modifier.weight(1f)) { HostBridge.invoke(probe, null, { 1f }, Modifier, currentComposer, 0, 0) }
                    }
                    NativeUi.Control("播放", "▶") { }
                    NativeUi.Slider("00:01 / 04:30", 1f, 0f..270f) { }
                }
            } } }
            repeat(3) { frame.performFrame((it + 1L) * 16_000_000); scene.measureAndLayout(); surface.canvas.clear(0xFF000000.toInt()); scene.draw(surface.canvas.asComposeCanvas()) }
            verify(local.spw.host.HookInternals.ProbeBridge.replacements > 0, "Real SPW transformer dispatches the new MV replacement")
            fun click(point: androidx.compose.ui.geometry.Offset) {
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, point,
                    buttons = androidx.compose.ui.input.pointer.PointerButtons(isPrimaryPressed = true), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, point,
                    buttons = androidx.compose.ui.input.pointer.PointerButtons(), button = androidx.compose.ui.input.pointer.PointerButton.Primary)
            }
            val mainOwner = scene.javaClass.getDeclaredField("mainOwner").apply { isAccessible = true }.get(scene)
            val owner = mainOwner.javaClass.getMethod("getOwner").invoke(mainOwner) as androidx.compose.ui.node.Owner
            fun buttons(): List<androidx.compose.ui.semantics.SemanticsNode> {
                fun descendants(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
                    listOf(node) + node.children.flatMap(::descendants)
                return descendants(owner.semanticsOwner.rootSemanticsNode).filter {
                    it.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) && it.boundsInRoot.center.y < 80f * scene.density.density
                }.sortedBy { it.boundsInRoot.left }
            }
            val row = buttons()
            verify(row.size == 5, "Queue, MV, native More, audio quality and volume remain separate")
            verify(row.zipWithNext().all { (a, b) -> a.boundsInRoot.right <= b.boundsInRoot.left }, "Playback button hit areas never overlap")
            click(row[1].boundsInRoot.center); verify(opened == 1 && queued == 0, "MV opens without invoking queue or More")
            click(row[0].boundsInRoot.center); verify(queued == 1 && opened == 1, "Adjacent queue remains clickable")
            Files.write(Path.of("build/native-controls.png"), surface.makeImageSnapshot().use { it.encodeToData()!!.use { it.bytes } })
            val layers = scene.javaClass.getDeclaredField("layers").apply { isAccessible = true }
            val before = (layers.get(scene) as List<*>).size
            click(row[2].boundsInRoot.center)
            repeat(3) { frame.performFrame((it + 4L) * 16_000_000); scene.measureAndLayout(); surface.canvas.clear(0xFF000000.toInt()); scene.draw(surface.canvas.asComposeCanvas()) }
            verify((layers.get(scene) as List<*>).size > before && opened == 1, "Native More opens its own popup without opening MV")
            click(androidx.compose.ui.geometry.Offset(600f, 500f))
            for ((index, scale) in listOf(1f, 1.5f).withIndex()) {
                scene.density = Density(scale)
                scene.size = IntSize((360 * scale).toInt(), 650)
                repeat(3) { frame.performFrame((10L + index * 3 + it) * 16_000_000); scene.measureAndLayout(); surface.canvas.clear(0xFF000000.toInt()); scene.draw(surface.canvas.asComposeCanvas()) }
                val compact = buttons()
                verify(compact.size == 5 && compact.zipWithNext().all { (a, b) -> a.boundsInRoot.right <= b.boundsInRoot.left }, "Compact native row has distinct buttons at scale $scale")
                verify(compact.all { it.boundsInRoot.right <= scene.size!!.width }, "Compact buttons remain inside the window at scale $scale")
                click(compact[1].boundsInRoot.center)
                verify(opened == index + 2 && queued == 1, "MV is clickable at scale $scale")
            }
            enabled = false
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
            repeat(3) { frame.performFrame((it + 16L) * 16_000_000); scene.measureAndLayout(); surface.canvas.clear(0xFF000000.toInt()); scene.draw(surface.canvas.asComposeCanvas()) }
            verify(buttons().size == 4, "Stopping removes only MV and retains native buttons")
            click(row[1].boundsInRoot.center); verify(opened == 3, "Disabled native entry cannot open playback")
            verify(surface.width == 900, "Native controls render")
        } finally { scene.close(); frame.close(); surface.close() }
        val host = androidx.compose.ui.awt.ComposeWindow()
        try {
            host.name = "host-fixture"; host.setSize(640, 360); host.isVisible = true
            HostBridge.captureHost(host)
            verify(!HostBridge.hostFullscreen(), "Ordinary host window is not fullscreen")
            host.placement = androidx.compose.ui.window.WindowPlacement.Fullscreen
            verify(HostBridge.hostFullscreen(), "Read actual Compose host fullscreen state")
            host.placement = androidx.compose.ui.window.WindowPlacement.Floating
            verify(!HostBridge.hostFullscreen(), "Host leaves fullscreen")
        } finally { host.dispose() }
    }
}

private fun popupContextChecks() = javax.swing.SwingUtilities.invokeAndWait {
    val locals = HostBridge.resolver.facade("BlurYesNoDialog", "LocalWallpaperHazeState", "LocalPopupHazeState").declaredFields
        .filter { it.type == ProvidableCompositionLocal::class.java }.map {
            @Suppress("UNCHECKED_CAST")
            (it.apply { isAccessible = true }.get(null) as ProvidableCompositionLocal<Any>)
        }
    val frame = FrameRecomposer(Dispatchers.Unconfined)
    val scene = CanvasLayersComposeScene(frameRecomposer = frame, density = Density(1f), size = IntSize(200, 100))
    var phase by mutableIntStateOf(0)
    var actual = emptyList<Any>()
    var nested = emptyList<Any>()
    try {
        scene.setContent { NativeUi.AppTheme { NativeUi.PopupContext {
            phase; actual = locals.map { it.current }
        } } }
        frame.performFrame(1L); scene.measureAndLayout()
        verify(actual.size == 2 && actual.all { it is dev.chrisbanes.haze.HazeState }, "Missing host haze providers receive scoped defaults")
        val first = actual
        phase++; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        frame.performFrame(20_000_000L); scene.measureAndLayout()
        verify(first.zip(actual).all { (a, b) -> a === b }, "Default haze states survive recomposition")
        val hostValues = locals.map { dev.chrisbanes.haze.HazeState() }
        scene.setContent { CompositionLocalProvider(*locals.zip(hostValues).map { (local, state) -> local provides state }.toTypedArray()) {
            NativeUi.AppTheme { NativeUi.PopupContext {
                actual = locals.map { it.current }
                NativeUi.PopupContext { nested = locals.map { it.current } }
            } }
        } }
        frame.performFrame(40_000_000L); scene.measureAndLayout()
        verify(hostValues.zip(actual).all { (a, b) -> a === b }, "Existing host haze providers are preserved")
        verify(hostValues.zip(nested).all { (a, b) -> a === b }, "Nested plugin controls preserve the same host haze providers")
    } finally { scene.close(); frame.close() }
}

fun main() {
    val root = Files.createTempDirectory(Path.of("build"), "mv-self-check-").toAbsolutePath()
    onlineChecks(root); libraryChecks(root); redesignChecks(root); handoffChecks(root); keyChecks(); nativeChecks(); popupContextChecks()
    println("PASS: $checks MV checks; matching, persistence, audio ownership, native entry and configuration.")
    System.exit(0)
}

class TestPreferencesFactory : PreferencesFactory {
    private val root = MemoryPreferences(null, "")
    override fun userRoot(): Preferences = root
    override fun systemRoot(): Preferences = root
}
private class MemoryPreferences(parent: AbstractPreferences?, name: String) : AbstractPreferences(parent, name) {
    private val values = mutableMapOf<String, String>(); private val nodes = mutableMapOf<String, MemoryPreferences>()
    override fun putSpi(key: String, value: String) { values[key] = value }
    override fun getSpi(key: String) = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() { values.clear(); nodes.clear() }
    override fun keysSpi() = values.keys.toTypedArray()
    override fun childrenNamesSpi() = nodes.keys.toTypedArray()
    override fun childSpi(name: String): AbstractPreferences = nodes.getOrPut(name) { MemoryPreferences(this, name) }
    override fun syncSpi() { }
    override fun flushSpi() { }
}



private fun onlineChecks(root: Path) {
    val link = biliLink("https://www.bilibili.com/video/BV1xx411c7mD/?p=2&share_source=copy_web")
    verify(link.page == 2 && link.url.endsWith("?p=2"), "Bilibili canonical page")
    fails { biliLink("https://www.bilibili.com.evil.test/video/BV1xx411c7mD") }
    fails { biliLink("http://www.bilibili.com/video/BV1xx411c7mD") }
    fails { biliLink("https://www.bilibili.com/video/BV1xx411c7mD?p=0") }
    verify(wbiQuery(mapOf("foo" to "114", "bar" to "514", "zab" to "1919810"), "ea1db124af3c7062474693fa704f4ff8",1702204169).endsWith("w_rid=8f6f2b5b3d485fe1886cec6a0be8c5d4"), "WBI reference vector")
    val file = root.resolve("internal/online.json")
    val ledger = MvOnlineStore(file)
    val a = song(root.resolve("baby-a.flac"),"Baby","Justin Bieber")
    val b = song(root.resolve("baby-b.flac"),"Baby","Clean Bandit/Luis Fonsi")
    ledger.bind(a,link)
    verify(MvOnlineStore(file).bound(a)?.link == link, "Online links persist across restart")
    ledger.bind(b,link)
    verify(ledger.bound(a) == null && ledger.bound(b)?.link == link,"Explicit online reassignment removes old owner")
    val backup = root.resolve("external-backup.json")
    ledger.setMirror(backup)
    ledger.bind(b,null)
    verify(MvOnlineStore.read(backup).isEmpty(),"Online deletion updates external backup")
    ledger.bind(a,link)
    val restored = MvOnlineStore(root.resolve("restored.json"))
    verify(restored.import(backup) == 1 && restored.bound(a)?.link == link,"Backup restores a fresh installation")
    verify(restored.bound(a.copy(path=root.resolve("moved-song.flac").toString()))?.link == link,"Portable match preserves artist metadata")
    verify(restored.bound(b) == null,"Same-title different-artist song never inherits online backup")
    val before = Files.readString(file)
    val broken = Files.writeString(root.resolve("bad-backup.json"),"{}")
    fails { ledger.import(broken) }
    verify(Files.readString(file) == before,"Invalid import never changes associations")
    fails { MvOnlineStore(root.resolve("empty-online.json")).setMirror(backup) }
    verify(MvOnlineStore.read(backup).size == 1,"Existing external backup cannot be overwritten on setup")
    val externalChange = root.resolve("external-edit.json")
    val protectedLedger = MvOnlineStore(root.resolve("protected/online.json"))
    protectedLedger.bind(a,link); protectedLedger.setMirror(externalChange)
    MvOnlineStore.write(externalChange, listOf(OnlineBinding(b.path,b.title,b.artist,b.album,link.url)))
    fails { protectedLedger.bind(a,null) }
    verify(protectedLedger.bound(a)?.link == link && MvOnlineStore.read(externalChange).single().title == b.title,
        "An externally changed automatic backup is not silently overwritten")
    verify(protectedLedger.import(externalChange)==1 && protectedLedger.bound(b)?.link == link && protectedLedger.bound(a)==null,
        "Importing the changed automatic backup reconciles both ledgers")
    val foreign = Files.writeString(root.resolve("not-a-backup.json"), "keep me")
    fails { protectedLedger.export(foreign) }
    verify(Files.readString(foreign)=="keep me", "Export never overwrites an unrelated file")
    val movedLedger = MvOnlineStore(root.resolve("moved/online.json"))
    movedLedger.bind(a,link)
    val movedSong = a.copy(path=root.resolve("relocated.flac").toString())
    val replacementLink = biliLink("BV1GJ411x7h7")
    val movedBackup = root.resolve("moved-backup.json")
    MvOnlineStore.write(movedBackup,listOf(OnlineBinding(movedSong.path,movedSong.title,movedSong.artist,movedSong.album,replacementLink.url)))
    movedLedger.import(movedBackup)
    verify(movedLedger.all().size==1 && movedLedger.bound(movedSong)?.link==replacementLink,"Import merges a uniquely identified song moved to a new path")
    val collisionLedger = MvOnlineStore(root.resolve("collision/online.json"))
    collisionLedger.bind(a,link); collisionLedger.bind(b,replacementLink)
    val changedMetadata = OnlineBinding(a.path,b.title,b.artist,b.album,BiliLink("BV1xx411c7mD",3).url)
    val collisionBackup = root.resolve("collision-backup.json")
    MvOnlineStore.write(collisionBackup,listOf(changedMetadata)); collisionLedger.import(collisionBackup)
    verify(collisionLedger.all().size==2 && collisionLedger.bound(b)?.link==replacementLink,
        "Exact-path updates do not remove another song with matching metadata")
    fun varint(value: Long): ByteArray {
        var n=value; val out=java.io.ByteArrayOutputStream()
        do { val b=(n and 127).toInt();n=n ushr 7;out.write(if(n==0L)b else b or 128) } while(n!=0L)
        return out.toByteArray()
    }
    fun number(field: Int,value: Long)=varint((field shl 3).toLong())+varint(value)
    fun bytes(field: Int,value: ByteArray)=varint(((field shl 3) or 2).toLong())+varint(value.size.toLong())+value
    val element=number(1,123)+number(2,1200)+number(3,5)+number(4,25)+number(5,0xffffff)+bytes(7,"弹幕测试".toByteArray())
    val parsed=parseDanmaku(bytes(1,element))
    verify(parsed.single().text=="弹幕测试" && parsed.single().time==1.2 && parsed.single().mode==5,"Bounded protobuf parser reads time, mode, text and color")
    fails { parseDanmaku(byteArrayOf(10,127)) }
    fails { parseDanmaku(ByteArray(12){0x80.toByte()}) }
    val comment=parsed.single().copy(mode=1,time=0.0)
    val layout=layoutDanmaku(listOf(comment,comment.copy(id=124,time=.01),comment.copy(id=125,time=5.0)),1280f,1,DanmakuOptions()){100f}
    verify(layoutDanmaku(listOf(comment),1280f,1,DanmakuOptions()){100f}.single().duration==20.0 && layoutDanmaku(listOf(comment),300f,1,DanmakuOptions()){100f}.single().duration==20.0,"Scrolling danmaku defaults to a media-time lifetime of 20s independent of viewport")
    verify(layoutDanmaku(listOf(comment),1280f,1,DanmakuOptions(duration=32.5)){100f}.single().duration==32.5,"Custom scrolling danmaku duration is applied")
    verify(layoutDanmaku(listOf(comment.copy(mode=4)),1280f,1,DanmakuOptions()){100f}.single().duration==4.5,"Bilibili fixed danmaku uses the shared 4.5s duration")
    verify(layout.size==2,"Danmaku lane allocator suppresses collisions")
    verify(layoutDanmaku(listOf(comment,comment.copy(id=124,time=.1,mode=5)),1280f,1,DanmakuOptions()){100f}.size==1,"Fixed and scrolling comments share occupied lanes")
    verify(layoutDanmaku(listOf(comment),1280f,1,DanmakuOptions(words=listOf("测试"))){100f}.isEmpty(),"Keyword filter runs before layout")
    verify(layoutDanmaku(listOf(comment.copy(mode=2),comment.copy(mode=3,time=30.0),comment.copy(mode=6,time=60.0),comment.copy(mode=7,time=90.0)),1280f,2,DanmakuOptions()){100f}.map { it.comment.mode } == listOf(2,3), "Rolling modes share a control; reverse and advanced modes are excluded")
    val dmSettings = MvStore(root.resolve("danmaku/config.properties"))
    dmSettings.setDanmakuSetting("duration", "12")
    verify(kotlin.math.abs(danmakuOptions(dmSettings).duration-12.0)<.001,"Legacy duration survives migration to speed")
    dmSettings.setDanmakuSetting("speed", "2")
    verify(kotlin.math.abs(danmakuOptions(dmSettings).duration-10.0)<.001,"Twice the danmaku speed halves the rolling duration")
    dmSettings.setDanmakuSetting("mode1", "false")
    verify(danmakuOptions(dmSettings).modes.intersect(setOf(1,2,3)).isEmpty(),"The rolling switch controls all rolling modes")
    dmSettings.setDanmakuSetting("weight", "6.0")
    verify(danmakuOptions(dmSettings).weight == 6, "Smart filter slider persists integer levels")
    val repeats = (0 until 12).map { i -> comment.copy(id=1000L+i, time=i * .5, text=if (i % 2 == 0) "爷青回！" else "爷 青 回", weight=0) }
    val unique = (0 until 10).map { i -> comment.copy(id=2000L+i, time=i * .5, text="不同内容$i", weight=0) }
    val sample = (repeats + unique).sortedBy { it.time }
    fun filtered(level: Int) = layoutDanmaku(sample, 1280f, 100, DanmakuOptions(weight=level)) { 100f }
    val off = filtered(0); val moderate = filtered(4); val stronger = filtered(6)
    verify(off.size == sample.size && moderate.count { it.comment.id in 1000L..1011L } == 3 && stronger.count { it.comment.id in 1000L..1011L } == 2,
        "Smart filter progressively limits normalized repeated messages")
    verify(stronger.count { it.comment.id in 2000L..2009L } == unique.size,
        "Smart filter keeps distinct messages when weights are missing")
    val crowded = (0 until 200).map { i -> comment.copy(id=3000L+i, time=i * .01, text="内容$i") }
    verify(layoutDanmaku(crowded, 1280f, 220, DanmakuOptions()) { 100f }.size == 200,
        "No global density cap evicts or rejects otherwise valid comments")
    val dmClock = DanmakuClock()
    val face = org.jetbrains.skia.FontMgr.default.matchFamilyStyle("Microsoft YaHei", org.jetbrains.skia.FontStyle.BOLD)
    org.jetbrains.skia.Font(face,25f).use { font ->
        DanmakuSprites(font,1f,32768).use { sprites ->
            val first = sprites.get("弹幕 ABC",25,0xffffff)
            verify(first.image != null && first.blob == null && sprites.get("弹幕 ABC",25,0xffffff) === first,
                "Repeated danmaku reuses the completed outlined bitmap")
            val white = first.image!!.encodeToData()!!.use { it.bytes }
            val red = sprites.get("弹幕 ABC",25,0xff0000).image!!.encodeToData()!!.use { it.bytes }
            verify(!white.contentEquals(red),"Colored comments keep distinct rasterized pixels")
            repeat(20) { sprites.get("缓存弹幕 $it",25,0xffffff) }
            verify(sprites.usedBytes<=32768 && requireNotNull(first.image).isClosed,"Sprite cache stays bounded and closes evicted bitmaps")
            val long = sprites.get("a".repeat(2000),25,0xffffff)
            verify(long.image==null && long.blob!=null,"Oversized comments retain the text path without allocating enormous bitmaps")
        }
    }
    face?.close()
    dmClock.update(0.0,true,1.0,0)
    var lastTime = 0.0
    for (i in 1..30) {
        val nanos = i * 300_000_000L
        val before = dmClock.at(nanos)
        dmClock.update(i * .3 + if (i % 2 == 0) .07 else -.07,true,1.0,nanos)
        val after = dmClock.at(nanos)
        verify(after >= lastTime && kotlin.math.abs(after-before) < .000001,"Jittery player reports do not make danmaku jump")
        lastTime = after
    }
    dmClock.update(20.0,true,2.0,9_300_000_000L)
    verify(kotlin.math.abs(dmClock.at(9_800_000_000L)-21.0)<.000001,"A large seek and double speed follow media time")
    dmClock.update(21.0,false,2.0,9_800_000_000L)
    verify(dmClock.at(10_800_000_000L)==21.0,"Danmaku clock freezes while paused or buffering")
    val pauseClock = DanmakuClock()
    pauseClock.update(0.0,true,1.0,1_000_000_000L)
    val visibleBeforePause = pauseClock.frameAt(1_200_000_000L,60)
    pauseClock.update(.05,false,1.0,1_210_000_000L)
    verify(pauseClock.at(1_210_000_000L)==visibleBeforePause,"Pausing freezes the last displayed position instead of a stale player report")
    pauseClock.update(.05,true,1.0,1_300_000_000L)
    verify(pauseClock.at(1_300_000_000L)==visibleBeforePause,"Resuming starts from the frozen position without a jump")
    for (hz in listOf(60,144,240)) {
        val cadence = DanmakuClock()
        val epoch = 1_000_000_000L
        val period = 1e9 / hz
        cadence.update(0.0,true,1.0,epoch)
        val noise = doubleArrayOf(-.3,.3,-.15,.15,0.0)
        var previous = 0.0
        var maxDeviation = 0.0
        var last = epoch
        repeat(600) { index ->
            last = epoch + ((index+1+noise[index%noise.size])*period).toLong()
            val shown = cadence.frameAt(last,hz)
            if (index>60) maxDeviation = maxOf(maxDeviation,kotlin.math.abs((shown-previous)/(period/1e9)-1))
            check(shown>=previous)
            previous=shown
        }
        verify(maxDeviation<=.121 && kotlin.math.abs(previous-cadence.at(last))<period/1e9,
            "Frame timestamp jitter is smoothed without cumulative video drift at ${hz}Hz")
        verify(cadence.frameAt(last+500_000_000L,hz)==cadence.at(last+500_000_000L),
            "A genuinely long frame gap immediately resynchronizes at ${hz}Hz")
    }
    val mismatched = DanmakuClock()
    mismatched.update(0.0,true,1.0,1_000_000_000L)
    var mismatchTime = 0L
    var mismatchPosition = 0.0
    repeat(600) { i ->
        mismatchTime = 1_000_000_000L + ((i+1)*1e9/240).toLong()
        mismatchPosition = mismatched.frameAt(mismatchTime,60)
    }
    verify(kotlin.math.abs(mismatchPosition-mismatched.at(mismatchTime))<.02,
        "A misreported monitor refresh rate does not accelerate danmaku or accumulate drift")
    val secret=root.resolve("encrypted-account.dat")
    val auth=BiliLogin(secret){}
    verify(auth.cookie().isEmpty(),"New account is anonymous without a login request")
    val protected = try { com.sun.jna.platform.win32.Crypt32Util.cryptProtectData("SESSDATA=test-secret".toByteArray()) }
        catch (error: com.sun.jna.platform.win32.Win32Exception) { if (error.errorCode != 2) throw error; null }
    if (protected != null) {
        Files.write(secret,protected)
        val saved=BiliLogin(secret){}
        verify(saved.cookie()=="SESSDATA=test-secret" && !protected.decodeToString().contains("test-secret"),"Windows account credentials are encrypted and reloadable")
        saved.logout(); saved.close()
    } else println("NOT VERIFIED: DPAPI user encryption requires the interactive Windows profile; sandbox account has no usable master key")
    auth.close()
    verify(!Files.exists(secret),"Logout removes the encrypted credential file")
}
