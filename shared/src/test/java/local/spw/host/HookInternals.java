package local.spw.host;

import java.lang.reflect.Method;
import java.util.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import androidx.compose.runtime.Composer;
import com.xuncorp.spw.workshop.api.hook.*;
import com.xuncorp.spw.workshop.api.WorkshopApi;

/** Exercise the host's actual resolver/transformer without embedding their obfuscated names. */
public final class HookInternals {
    public static final HostResolver HOST = new HostResolver(WorkshopApi.class);
    private static final Class<?> TARGETS = HOST.type("com.xuncorp.voxzen.workshop.hooks.WorkshopHookTargets");
    private static final Object INSTANCE = HOST.singleton(TARGETS);
    public static Object ui(String owner, String name, String caller, List<String> parameters) throws Exception {
        return HOST.function(TARGETS, "ui").invoke(INSTANCE, owner, name, caller, parameters);
    }
    public static Object method(String owner, String name, List<String> parameters) throws Exception {
        return HOST.function(TARGETS, "method").invoke(INSTANCE, owner, name, parameters);
    }
    public static byte[] bytes(String name) throws Exception {
        try (var stream = WorkshopApi.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            return Objects.requireNonNull(stream, name).readAllBytes();
        }
    }
    public static byte[] transform(Method method, String caller, Object resolved) throws Exception {
        return transform(method, caller, resolved, bytes(caller));
    }
    private static byte[] transform(Method method, String caller, Object resolved, byte[] input) throws Exception {
        Class<?> signatureType = HOST.type("com.xuncorp.voxzen.workshop.hooks.WorkshopComposableCallSignature");
        Class<?> companionType = HOST.type("com.xuncorp.voxzen.workshop.hooks.WorkshopComposableCallSignature$Companion");
        Object companion = HOST.singleton(companionType);
        Method factory = HostResolver.unique(Arrays.stream(companionType.getDeclaredMethods()).filter(m ->
                m.getParameterCount() == 6 && m.getParameterTypes()[0] == int.class && m.getReturnType() == signatureType).toList(), "hook signature factory");
        factory.setAccessible(true);
        Class<?> readerType = factory.getParameterTypes()[5];
        String owner = method.getDeclaringClass().getName();
        String descriptor = java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString();
        Object signature = factory.invoke(companion, 184, owner.replace('.', '/'), method.getName(), descriptor, false,
                readerType.getConstructor(byte[].class).newInstance((Object)bytes(owner)));
        Object masks = HOST.function(signatureType, "resolveDefaultMaskIndices").invoke(signature);
        Class<?> siteType = HOST.type("com.xuncorp.voxzen.workshop.hooks.WorkshopHookRegistrar$UiSite");
        Object site = siteType.getConstructor(int.class, resolved.getClass()).newInstance(1, resolved);
        HOST.field(siteType, "defaultMaskIndices").set(site, masks);
        Class<?> transformType = HOST.type("com.xuncorp.voxzen.workshop.hooks.WorkshopUiHookTransformer");
        Object transformer = transformType.getConstructor(siteType).newInstance(site);
        return (byte[]) HOST.function(transformType, "transform").invoke(transformer, caller, input);
    }

    public static int callDefaultMask(Method method, String caller) throws Exception {
        if (method.getParameterTypes()[method.getParameterCount() - 1] == Composer.class) return 0;
        String owner = method.getDeclaringClass().getName().replace('.', '/');
        String descriptor = java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString();
        List<Integer> masks = new ArrayList<>();
        for (var member : ClassFile.of().parse(bytes(caller)).methods()) {
            if (member.code().isEmpty()) continue;
            Instruction previous = null;
            for (var element : member.code().get()) {
                if (element instanceof InvokeInstruction call && call.owner().asInternalName().equals(owner)
                        && call.name().equalsString(method.getName()) && call.type().equalsString(descriptor)) {
                    if (!(previous instanceof ConstantInstruction constant) || !(constant.constantValue() instanceof Integer mask))
                        throw new IllegalStateException("Native default mask must be an explicit constant");
                    masks.add(mask);
                }
                if (element instanceof Instruction instruction) previous = instruction;
            }
        }
        return HostResolver.unique(masks, "native call default mask");
    }

    /** Run the real transformer/guard with the default mask extracted from the actual host call. */
    public static Method probe(Method method, String caller) throws Exception {
        int mask = callDefaultMask(method, caller);
        int composer = Arrays.asList(method.getParameterTypes()).indexOf(Composer.class);
        var descriptor = MethodTypeDesc.ofDescriptor(java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString());
        byte[] fixture = ClassFile.of().build(ClassDesc.of(caller), builder -> builder.withMethodBody("probe", descriptor,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> {
                    for (int i = 0; i <= composer; i++) {
                        if (method.getParameterTypes()[i].isPrimitive()) code.iload(i); else code.aload(i);
                    }
                    for (int i = composer + 1; i < method.getParameterCount(); i++)
                        code.loadConstant(i == method.getParameterCount() - 1 && method.getParameterCount() > composer + 2 ? mask : 0);
                    code.invokestatic(ClassDesc.of(method.getDeclaringClass().getName()), method.getName(), descriptor).return_();
                }));
        var resolved = ui(method.getDeclaringClass().getName(), method.getName(), caller,
                Arrays.stream(method.getParameterTypes()).limit(composer).map(Class::getName).toList());
        byte[] transformed = transform(method, caller, resolved, fixture);
        byte[] redirected = ClassFile.of().transformClass(ClassFile.of().parse(transformed),
                ClassTransform.transformingMethodBodies((code, element) -> {
                    if (element instanceof InvokeInstruction call && call.owner().asInternalName().equals("com/xuncorp/voxzen/workshop/hooks/WorkshopHookBridge"))
                        code.invokestatic(ClassDesc.of(ProbeBridge.class.getName()), call.name().stringValue(), call.typeSymbol());
                    else code.with(element);
                }));
        class ProbeLoader extends ClassLoader {
            ProbeLoader() { super(HookInternals.class.getClassLoader()); }
            Class<?> loadProbe() { return defineClass(caller, redirected, 0, redirected.length); }
        }
        return new ProbeLoader().loadProbe().getMethod("probe", method.getParameterTypes());
    }

    public static final class ProbeBridge {
        public static ComposableHook hook;
        public static int replacements;
        public static boolean hasReplacement(int id) { return true; }
        public static void RenderReplacement(int id, Object receiver, Object[] args, Composer composer, int changed) {
            replacements++;
            hook.Content(new UiHookCall() {
                public Object getThisObject() { return receiver; }
                public List<Object> getArguments() { return Arrays.asList(args); }
                public Object getArgument(String name) { return null; }
            }, composer, 0);
        }
        public static void RenderAfter(int id, Object receiver, Object[] args, Composer composer, int changed) {
        }
    }
}
