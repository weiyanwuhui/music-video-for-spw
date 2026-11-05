@file:OptIn(com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)
package local.spw.mv

import com.xuncorp.spw.workshop.api.*
import com.xuncorp.spw.workshop.api.hook.HookHandle
import com.xuncorp.spw.workshop.api.hook.MethodHook
import com.xuncorp.spw.workshop.api.hook.MethodHookParam

class MvPlugin(context: PluginContext) : SpwPlugin(context) {
    @Volatile private var active = false
    private val hooks = mutableListOf<HookHandle>()
    private var controller: MvController? = null
    private var navigation: MvNavigationHooks? = null
    override fun start() {
        check(!active && hooks.isEmpty()) { "MV 插件已经启用或尚未完成清理" }
        for (permission in listOf(PluginPermission.LIBRARY_READ, PluginPermission.CLASS_TRANSFORM))
            check(WorkshopApi.manager.isPermissionGranted(permission)) { "Music Video for SPW 需要授权：$permission" }
        try {
            HostBridge.validate()
            val config = WorkshopApi.manager.createConfigManager().getConfig()
            val directory = config.getConfigPath().parent
            val store = MvStore(directory.resolve("mv-associations.properties"))
            config.set("auto_open", config.get("auto_open", store.autoOpen()))
            config.set("show_sidebar", config.get("show_sidebar", true))
            check(config.save()) { "无法保存 MV 配置" }
            if (store.autoOpen() != config.get("auto_open", false)) store.setAutoOpen(config.get("auto_open", false))
            val controls = MvController(pluginContext.pluginId, store, settings = config)
            controller = controls
            active = true
            activeController = controls
            // Remounting the native frame chains JNA callbacks whose previous owner can be collected.
            for ((getter, hidden) in MvHostWindow.captionOverrides) {
                hooks += WorkshopApi.hookRegistrar.hookMethod(getter.declaringClass.name, getter.name, emptyList(), object : MethodHook() {
                    override fun before(call: MethodHookParam) {
                        if (active && controls.page.isVisible) call.setResult(hidden)
                    }
                })
            }
            hooks += WorkshopApi.hookRegistrar.replaceComposable(MvPageHook.original.declaringClass.name, MvPageHook.original.name,
                MvPageHook.caller, MvPageHook.parameters, 50, MvPageHook(controls.page) { active })
            hooks += WorkshopApi.hookRegistrar.replaceComposable(MvEntryHook.original.declaringClass.name, MvEntryHook.original.name,
                MvEntryHook.caller, MvEntryHook.parameters, 50, MvEntryHook({ active }, controls::open))
            val library = MvNavigationHooks(controls) { active }
            navigation = library
            hooks += WorkshopApi.hookRegistrar.hookMethod(MvNavigationHooks.sidebar.declaringClass.name, MvNavigationHooks.sidebar.name,
                MvNavigationHooks.sidebarParameters, library.sidebarHook)
            hooks += WorkshopApi.hookRegistrar.replaceComposable(MvNavigationHooks.navigation.declaringClass.name, MvNavigationHooks.navigation.name,
                MvNavigationHooks.navigationCaller, emptyList(), 50, library.navigationHook)
            val handler = SpwShortcuts.handler
            hooks += WorkshopApi.hookRegistrar.hookMethod(handler.declaringClass.name, handler.name, handler.parameterTypes.map { it.name }, object : MethodHook() {
                override fun before(call: MethodHookParam) {
                    if (active && edt { controls.page.hasModal }) { call.setResult(true); return }
                    val name = (call.args[0] as? Enum<*>)?.name ?: return
                    if (active && edt { SpwShortcuts.handles(name, controls.page.isOpen, controls.page.ownsFocus) }) {
                        edt { controls.page.action(name) }
                        call.setResult(true)
                    }
                }
            })
        } catch (error: Throwable) {
            try { stop() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw IllegalStateException("Music Video for SPW 启用失败", error)
        }
    }
    override fun stop() {
        active = false
        if (activeController === controller) activeController = null
        var failure: Throwable? = null
        try { navigation?.close(); navigation = null } catch (error: Throwable) { failure = error }
        try { controller?.close(); controller = null } catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        for (handle in hooks.toList()) try { handle.unhook(); hooks.remove(handle) }
            catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
    }
    companion object {
        @Volatile var activeController: MvController? = null
        @JvmStatic fun addBilibili() { activeController?.textAction("link") }
        @JvmStatic fun loginBilibili() { activeController?.login() }
        @JvmStatic fun logoutBilibili() { activeController?.logout() }
        @JvmStatic fun backupFile() { activeController?.textAction("backup") }
        @JvmStatic fun exportBackup() { activeController?.textAction("export") }
        @JvmStatic fun importBackup() { activeController?.textAction("import") }
        @JvmStatic fun chooseFolder() { activeController?.chooseFolder() }
        @JvmStatic fun rescan() { activeController?.refresh() }
        @JvmStatic fun bindCurrent() { activeController?.selectVideo(false) }
        @JvmStatic fun unbindCurrent() { activeController?.unbindCurrent() }
        @JvmStatic fun resumeMatching() { activeController?.resumeMatching() }
    }
}

class MvPlayback : PlaybackExtensionPoint {
    override fun onStateChanged(state: PlaybackExtensionPoint.State) { MvPlugin.activeController?.playbackChanged() }
    override fun onIsPlayingChanged(isPlaying: Boolean) { MvPlugin.activeController?.playbackChanged() }
    override fun onBeforeLoadLyrics(mediaItem: PlaybackExtensionPoint.MediaItem): String? {
        MvPlugin.activeController?.playbackChanged(); return null
    }
}
