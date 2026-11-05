package local.spw.mv

import java.awt.event.KeyEvent
import javax.swing.Timer

data class MvShortcut(val key: Int, val modifiers: Int, val action: String)

object SpwShortcuts {
    private val r = HostBridge.resolver
    private val settings = r.type("com.xuncorp.voxzen.service.hotkey.HotkeySettings")
    private val binding = r.type("com.xuncorp.voxzen.data.model.HotkeyBinding")
    private val action = r.getter(binding, "action")
    private val key = r.getter(binding, "keyCode")
    private val modifiers = r.getter(binding, "modifiers")
    private val mask = r.type("com.xuncorp.voxzen.service.hotkey.HotkeyExtensionsKt").getMethod("buildModifierMask", KeyEvent::class.java)
    val handler = r.function(r.type("com.xuncorp.voxzen.service.hotkey.HotkeyActionHandlerDesktopKt"), "handleHotkeyAction")
    val supported = setOf("PLAY_PAUSE", "PREVIOUS", "NEXT", "STOP", "FORWARD_5S", "BACKWARD_5S", "VOLUME_UP", "VOLUME_DOWN", "BACK")
    fun handles(action: String, videoOpen: Boolean, focused: Boolean) = videoOpen && action in supported && (action != "BACK" || focused)
    fun bindings(): List<MvShortcut> = (settings.getMethod("getAppBindings").invoke(r.singleton(settings)) as List<*>).mapNotNull { value ->
        value ?: return@mapNotNull null
        val name = (action.invoke(value) as? Enum<*>)?.name ?: return@mapNotNull null
        if (name !in supported) null else MvShortcut(key.invoke(value) as Int, modifiers.invoke(value) as Int, name)
    }
    fun mask(event: KeyEvent) = mask.invoke(null, event) as Int
}

/** Delay a short arrow action until release, so a hold never seeks before boosting. */
class MvKeys(private val bindings: () -> List<MvShortcut>, private val action: (String) -> Unit,
    private val boost: (Double?) -> Unit, private val fullscreen: () -> Unit,
    private val timer: (Int, () -> Unit) -> (() -> Unit) = { delay, run ->
        val value = Timer(delay) { run() }.apply { isRepeats = false; start() }
        ({ value.stop() })
    }) {
    private val down = mutableSetOf<Int>()
    private var arrow: Int? = null
    private var shortAction: String? = null
    private var boosted = false
    private var cancelTimer: (() -> Unit)? = null
    fun key(code: Int, modifiers: Int, pressed: Boolean): Boolean {
        if (!pressed) {
            if (!down.remove(code)) return false
            if (arrow == code) {
                cancelTimer?.invoke(); cancelTimer = null
                if (boosted) boost(null) else shortAction?.let(action)
                arrow = null; boosted = false; shortAction = null
            }
            return true
        }
        if (code in down) return true
        val command = bindings().firstOrNull { it.key == code && it.modifiers == modifiers }?.action
        if (modifiers == 0 && code in setOf(KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT)) {
            cancelTimer?.invoke()
            if (boosted) boost(null)
            arrow = code; boosted = false; shortAction = command; down.add(code)
            cancelTimer = timer(320) {
                if (arrow == code && code in down) { boosted = true; boost(if (code == KeyEvent.VK_RIGHT) 2.0 else 3.0) }
            }
            return true
        }
        if (code == KeyEvent.VK_F11 && modifiers == 0) { down.add(code); fullscreen(); return true }
        if (command == null) return false
        down.add(code); action(command); return true
    }
    fun cancel() {
        cancelTimer?.invoke(); cancelTimer = null
        if (boosted) boost(null)
        down.clear(); arrow = null; boosted = false; shortAction = null
    }
}
