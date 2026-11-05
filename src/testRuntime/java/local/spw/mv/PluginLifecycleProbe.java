package local.spw.mv;

import com.xuncorp.spw.workshop.api.*;
import com.xuncorp.spw.workshop.api.config.*;
import local.spw.host.HostResolver;
import org.pf4j.*;
import java.awt.EventQueue;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual package loader, permission store, Hook registrar and update transaction in an isolated profile. */
public class PluginLifecycleProbe {
    public static void main(String[] args) throws Exception {
        Thread watchdog = new Thread(() -> { try { Thread.sleep(90000); } catch (InterruptedException ignored) {} System.exit(4); });
        watchdog.setDaemon(true); watchdog.start();
        Path profile = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Path.of(System.getenv("APPDATA")).toAbsolutePath().normalize().equals(profile))
            throw new IllegalStateException("APPDATA must point to the isolated test profile");
        var r = new HostResolver(WorkshopApi.class);
        Class<?> paths = r.type("com.xuncorp.spw.core.util.PathUtil");
        Path app = ((java.io.File)r.getter(paths,"appDir").invoke(r.receiver(r.getter(paths,"appDir")))).toPath();
        if (!app.toAbsolutePath().normalize().startsWith(profile)) throw new IllegalStateException("Host profile escaped isolation");
        Class<?> apiType = r.type("com.xuncorp.voxzen.workshop.WorkshopApiInstance");
        WorkshopApi nativeApi = (WorkshopApi)r.singleton(apiType);
        var manager = (AbstractPluginManager)r.field(apiType,"pluginManager").get(null);
        Path config = app.resolve("workshop/data/local.spw.mv/config.json"); Files.createDirectories(config.getParent());
        var values = new ConcurrentHashMap<String,Object>();
        ConfigHelper helper = new ConfigHelper() {
            @SuppressWarnings("unchecked") public <T> T get(String key,T fallback) { return (T)values.getOrDefault(key,fallback); }
            public void set(String key,Object value) { values.put(key,value); }
            public boolean save() { return true; }
            public boolean reload() { return true; }
            public Path getConfigPath() { return config; }
        };
        ConfigManager configManager = proxy(ConfigManager.class,(p,m,a)->m.getName().equals("getConfig")?helper:null);
        WorkshopApi.Manager apiManager = proxy(WorkshopApi.Manager.class,(p,m,a)->switch(m.getName()) {
            case "createConfigManager" -> configManager;
            case "isPermissionGranted" -> true;
            default -> throw new UnsupportedOperationException(m.toString());
        });
        WorkshopApi.Playback playback = proxy(WorkshopApi.Playback.class,(p,m,a)->null);
        WorkshopApi.Library library = proxy(WorkshopApi.Library.class,(p,m,a)->CompletableFuture.completedFuture(List.of()));
        WorkshopApi.Ui ui = proxy(WorkshopApi.Ui.class,(p,m,a)-> { throw new AssertionError("Unexpected startup toast: "+Arrays.toString(a)); });
        WorkshopApi.Companion.setInstance(proxy(WorkshopApi.class,(p,m,a)->switch(m.getName()) {
            case "getManager" -> apiManager;
            case "getPlayback" -> playback;
            case "getLibrary" -> library;
            case "getUi" -> ui;
            case "getHookRegistrar" -> nativeApi.getHookRegistrar();
            default -> throw new UnsupportedOperationException(m.toString());
        }));
        var importPackage = r.function(apiType,"importPlugin",m->m.getParameterCount()==1 && m.getParameterTypes()[0]==java.io.File.class);
        var updatePackage = r.function(apiType,"updatePlugin",m->m.getParameterCount()==2 && m.getParameterTypes()[0]==String.class && m.getParameterTypes()[1]==java.io.File.class);
        var permissions = r.function(manager.getClass(),"confirmPermissions",m->m.getParameterCount()==3 && m.getParameterTypes()[0]==String.class);
        var requested = Set.of("library-read","class-transform");
        var granted = EnumSet.of(PluginPermission.LIBRARY_READ,PluginPermission.CLASS_TRANSFORM);
        Path packageFile = Path.of(args[1]).toAbsolutePath();
        importPackage.invoke(nativeApi,packageFile.toFile());
        var propertyType = r.type("com.moriafly.salt.ui.window.SaltWindowProperties");
        var constructor = Arrays.stream(propertyType.getDeclaredConstructors()).filter(c->c.getParameterCount()==14).findFirst().orElseThrow(); constructor.setAccessible(true);
        kotlin.jvm.functions.Function2<Object,Object,kotlin.Unit> callback = (a,b)->kotlin.Unit.INSTANCE;
        Object props = constructor.newInstance(0L,callback,callback,32f,true,32f,true,constructor.getParameterTypes()[7].getEnumConstants()[0],true,1.75f,1.25f,true,true,true);
        ClassLoader previous = null;
        for(int cycle=0;cycle<3;cycle++) {
            permissions.invoke(manager,"local.spw.mv",granted,requested);
            manager.enablePlugin("local.spw.mv");
            if(manager.startPlugin("local.spw.mv")!=PluginState.STARTED) throw new AssertionError("Plugin did not start");
            Object plugin=manager.getPlugin("local.spw.mv").getPlugin();
            ClassLoader loader=plugin.getClass().getClassLoader();
            if(!(loader instanceof PluginClassLoader) || loader==previous) throw new AssertionError("Package must load in a fresh plugin classloader");
            previous=loader;
            assertSidebar(r, true);
            Field controlField=plugin.getClass().getDeclaredField("activeController");controlField.setAccessible(true);
            Object control=controlField.get(null);
            Object page=control.getClass().getMethod("getPage").invoke(control);
            Field visible=page.getClass().getDeclaredField("visible$delegate");visible.setAccessible(true);
            @SuppressWarnings("unchecked") var shown=(androidx.compose.runtime.MutableState<Boolean>)visible.get(page);
            NativeWindowProbe.run(props, page);
            assertCaption(r,props,true,32f,true);
            EventQueue.invokeAndWait(()->shown.setValue(true));
            assertCaption(r,props,false,0f,false);
            if(!r.getter(propertyType,"extraDisplayScale").invoke(props).equals(1.75f)) throw new AssertionError("Display scale changed");
            EventQueue.invokeAndWait(()->shown.setValue(false));
            assertCaption(r,props,true,32f,true);
            System.out.println("PASS: native package start and caption getters cycle="+cycle);
            sidebarSettings(control).set("show_sidebar",false);
            if(!sidebarSettings(control).save()) throw new AssertionError("Cannot save sidebar preference");
            awaitSidebarVisibility(control,false);
            updatePackage.invoke(nativeApi,"local.spw.mv",packageFile.toFile());
            permissions.invoke(manager,"local.spw.mv",granted,requested);
            manager.enablePlugin("local.spw.mv");
            if(manager.startPlugin("local.spw.mv")!=PluginState.STARTED) throw new AssertionError("Updated plugin did not start");
            Object updated=manager.getPlugin("local.spw.mv").getPlugin();
            Field updatedField=updated.getClass().getDeclaredField("activeController");updatedField.setAccessible(true);
            Object updatedControl=updatedField.get(null);
            awaitSidebarVisibility(updatedControl,false);
            sidebarSettings(updatedControl).set("show_sidebar",true);
            if(!sidebarSettings(updatedControl).save()) throw new AssertionError("Cannot restore sidebar preference");
            awaitSidebarVisibility(updatedControl,true);
            System.out.println("PASS: native sidebar preference survives enabled update and restores without restart cycle="+cycle);
            assertCaption(r,props,true,32f,true);
            System.gc();
            System.out.println("PASS: actual native update transaction while MV plugin is enabled cycle="+cycle);
            manager.stopPlugin("local.spw.mv");
        }
        manager.stopPlugins();
        assertSidebar(r, false);
        System.out.println("PASS: actual native package lifecycle, getter hooks and enabled-update-reload transactions");
        System.exit(0);
    }
    private static com.xuncorp.spw.workshop.api.config.ConfigHelper sidebarSettings(Object control) throws Exception {
        Field field=control.getClass().getDeclaredField("settings");field.setAccessible(true);
        return (com.xuncorp.spw.workshop.api.config.ConfigHelper)field.get(control);
    }
    private static void awaitSidebarVisibility(Object control,boolean expected) throws Exception {
        var state=(androidx.compose.runtime.State<?>)control.getClass().getMethod("getSidebarVisible").invoke(control);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(!Boolean.valueOf(expected).equals(state.getValue()) && System.nanoTime()<until) Thread.sleep(20);
        if(!Boolean.valueOf(expected).equals(state.getValue())) throw new AssertionError("Sidebar visibility did not follow saved native config");
    }
    private static void assertSidebar(HostResolver r, boolean enabled) throws Exception {
        Class<?> model = r.type("com.xuncorp.voxzen.ui.main.sidebar.SidebarViewModel");
        List<?> items = null;
        for(Field field:model.getDeclaredFields()) if(Modifier.isStatic(field.getModifiers()) && List.class.isAssignableFrom(field.getType())) {
            field.setAccessible(true);
            var list=(List<?>)field.get(null);
            if(list.size()>2) { if(items!=null) throw new AssertionError("Ambiguous native sidebar defaults"); items=list; }
        }
        if(items==null || items.size()!=14 || !r.type("com.xuncorp.voxzen.ui.main.sidebar.SidebarItem$DividerItem").isInstance(items.getFirst())) throw new AssertionError("Native leading divider fixture missing");
        Method nativeKey=Arrays.stream(r.type("com.xuncorp.voxzen.ui.main.sidebar.SidebarItem").getDeclaredMethods()).filter(m->m.getReturnType()==Object.class && m.getParameterCount()==0).findFirst().orElseThrow();
        var expected=new ArrayList<Object>();for(Object item:items) { Object key=nativeKey.invoke(item); expected.add(key); if(enabled && key.equals("Track")) expected.add("local.spw.mv.library"); }
        Object success = r.type("com.xuncorp.voxzen.ui.main.sidebar.SidebarUiState$Success").getConstructors()[0].newInstance(items);
        var keys = new ArrayList<Object>();
        Class<?> scopeType = androidx.compose.foundation.lazy.LazyListScope.class;
        Object scope = Proxy.newProxyInstance(scopeType.getClassLoader(), new Class<?>[]{scopeType}, (p,m,a)-> {
            if(m.getName().equals("item")) keys.add(a[0]);
            else if(m.getName().equals("items")) {
                for(int i=0;i<(int)a[0];i++) keys.add(kotlin.jvm.functions.Function1.class.getMethod("invoke",Object.class).invoke(a[1],i));
            } else throw new AssertionError("Unexpected sidebar method "+m);
            return null;
        });
        Method builder=Arrays.stream(r.facade("Sidebar").getDeclaredMethods()).filter(m->m.getParameterCount()==3 && m.getParameterTypes()[2]==scopeType).findFirst().orElseThrow();
        Field unsafeField=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");unsafeField.setAccessible(true);
        Object nav=((sun.misc.Unsafe)unsafeField.get(null)).allocateInstance(builder.getParameterTypes()[1]);
        builder.setAccessible(true);
        builder.invoke(null,success,nav,scope);
        if(!keys.equals(expected)) throw new AssertionError("Native hooked sidebar order "+keys+" expected="+expected);
        System.out.println("PASS: actual fourteen-item native sidebar including leading divider preserves all keys enabled="+enabled);
        System.out.println("PASS: native registered sidebar method preserves Songs MV Genre ordering enabled="+enabled);
    }
    private static void assertCaption(HostResolver r,Object props,boolean buttons,float height,boolean move) throws Exception {
        if(!r.getter(props.getClass(),"captionButtonsVisible").invoke(props).equals(buttons)
            || !r.getter(props.getClass(),"captionBarHeight").invoke(props).equals(height)
            || !r.getter(props.getClass(),"captionButtonHeight").invoke(props).equals(height)
            || !r.getter(props.getClass(),"moveable").invoke(props).equals(move)) throw new AssertionError("Incorrect native caption getter override");
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type,InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
}
