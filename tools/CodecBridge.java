package com.emre.dynamicbt.root;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Root-only bridge that talks to the A2DP profile directly.
 *
 * Android only lets privileged callers read or change the Bluetooth codec
 * configuration (BLUETOOTH_PRIVILEGED / CDM association), so a normal app
 * cannot do it. This class is compiled into a small standalone dex, shipped
 * inside the APK as an asset and executed through `app_process` with uid 0 by
 * {@code RootCodec}. Running as root makes the framework permission checks pass.
 *
 * Usage: CodecBridge read | CodecBridge apply <rateHz> <bits>
 * Prints one JSON line on stdout.
 */
public final class CodecBridge {

    private static final int TIMEOUT_MS = 12000;

    private static volatile String sJson;

    /** Why the last device lookup came back empty; folded into error output. */
    private static String sActiveDeviceError;

    private static boolean sFrameworkInitialized;

    private CodecBridge() {
    }

    public static void main(String[] args) {
        final String action = args.length > 0 ? args[0] : "read";
        final int rate = args.length > 1 ? safeInt(args[1]) : 0;
        final int bits = args.length > 2 ? safeInt(args[2]) : 0;

        try {
            if (Looper.getMainLooper() == null) {
                Looper.prepareMainLooper();
            }
        } catch (Throwable ignored) {
            // Looper already prepared by the runtime.
        }

        if ("probe".equals(action)) {
            probe();
            return;
        }

        ensureBluetoothFrameworkInitialized();

        final Looper looper = Looper.getMainLooper();
        try {
            final Context ctx = systemContext();
            final BluetoothManager manager =
                    (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            final BluetoothAdapter adapter = resolveAdapter(ctx, manager);
            if (adapter == null) {
                print(error("no_bluetooth_adapter"));
                return;
            }

            final Handler handler = new Handler(looper);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sJson == null) {
                        sJson = error("timeout");
                        looper.quitSafely();
                    }
                }
            }, TIMEOUT_MS);

            adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile != BluetoothProfile.A2DP) {
                        return;
                    }
                    String result = null;
                    try {
                        result = execute(proxy, action, rate, bits);
                    } catch (Throwable t) {
                        result = error(describe(t));
                    } finally {
                        try {
                            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy);
                        } catch (Throwable ignored) {
                        }
                        if (sJson == null) {
                            sJson = result != null ? result : error("no_result");
                        }
                        looper.quitSafely();
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, BluetoothProfile.A2DP);

            Looper.loop();
        } catch (Throwable t) {
            if (sJson == null) {
                sJson = error(describe(t));
            }
        }

        print(sJson != null ? sJson : error("no_result"));
    }

    /**
     * Diagnostic entry point: reports whether this process can see system
     * services at all. Kept in the shipped jar because it is tiny and makes
     * field debugging possible without a rebuilt APK.
     */
    private static void probe() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method get = sm.getDeclaredMethod("getService", String.class);
            get.setAccessible(true);
            Object binder = get.invoke(null, "bluetooth_manager");
            sb.append("\"binder\":").append(binder != null).append(',');

            Method list = sm.getDeclaredMethod("listServices");
            list.setAccessible(true);
            String[] names = (String[]) list.invoke(null);
            int btServices = 0;
            if (names != null) {
                for (String name : names) {
                    if (name != null && name.contains("bluetooth")) {
                        btServices++;
                    }
                }
            }
            sb.append("\"services\":").append(names == null ? -1 : names.length).append(',');
            sb.append("\"btServices\":").append(btServices).append(',');

            Class<?> adapterClass = Class.forName("android.bluetooth.BluetoothAdapter");
            Method defaultAdapter = adapterClass.getDeclaredMethod("getDefaultAdapter");
            defaultAdapter.setAccessible(true);
            Object adapter = defaultAdapter.invoke(null);
            sb.append("\"adapter\":").append(adapter != null).append(',');
            sb.append("\"state\":").append(adapter != null
                    ? String.valueOf(call(adapter, "getState", new Class<?>[]{}))
                    : "null").append(',');

            StringBuilder ctors = new StringBuilder();
            for (java.lang.reflect.Constructor<?> ctor : adapterClass.getDeclaredConstructors()) {
                if (ctors.length() > 0) {
                    ctors.append('|');
                }
                for (Class<?> p : ctor.getParameterTypes()) {
                    ctors.append(p.getSimpleName()).append(',');
                }
            }
            sb.append("\"ctors\":\"").append(escape(ctors.toString())).append("\",");

            try {
                Class<?> bsm = Class.forName("android.os.BluetoothServiceManager");
                sb.append("\"bsmClass\":true,");
                StringBuilder methods = new StringBuilder();
                for (Method m : bsm.getDeclaredMethods()) {
                    if (methods.length() > 0) {
                        methods.append('|');
                    }
                    methods.append(m.getName());
                }
                sb.append("\"bsmMethods\":\"").append(escape(methods.toString())).append("\",");
                StringBuilder fields = new StringBuilder();
                for (java.lang.reflect.Field f : bsm.getDeclaredFields()) {
                    if (fields.length() > 0) {
                        fields.append('|');
                    }
                    fields.append(f.getName()).append(':').append(f.getType().getSimpleName());
                }
                sb.append("\"bsmFields\":\"").append(escape(fields.toString())).append("\",");
                Class<?> reg = Class.forName("android.os.BluetoothServiceManager$ServiceRegisterer");
                StringBuilder regCtors = new StringBuilder();
                for (java.lang.reflect.Constructor<?> ctor : reg.getDeclaredConstructors()) {
                    if (regCtors.length() > 0) {
                        regCtors.append('|');
                    }
                    for (Class<?> p : ctor.getParameterTypes()) {
                        regCtors.append(p.getSimpleName()).append(',');
                    }
                }
                sb.append("\"regCtors\":\"").append(escape(regCtors.toString())).append("\",");
            } catch (Throwable ignored) {
                sb.append("\"bsmClass\":false,");
            }

            // Try to build an adapter directly from the binder.
            Context probeContext = systemContext();
            BluetoothManager probeManager =
                    (BluetoothManager) probeContext.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter direct = resolveAdapter(probeContext, probeManager);
            sb.append("\"directAdapter\":").append(direct != null).append(',');
            sb.append("\"directState\":").append(direct != null
                    ? String.valueOf(call(direct, "getState", new Class<?>[]{}))
                    : "null").append(',');

            // Binder-level detail: if the adapter path stays blocked we want to
            // know whether IBluetoothA2dp can be reached directly instead.
            sb.append("\"ibmMethods\":\"").append(escape(methodList(
                    "android.bluetooth.IBluetoothManager", 24))).append("\",");
            sb.append("\"ia2dpMethods\":\"").append(escape(methodList(
                    "android.bluetooth.IBluetoothA2dp", 40))).append("\",");
            StringBuilder a2dpSvcs = new StringBuilder();
            Class<?> smClass = Class.forName("android.os.ServiceManager");
            Method listSvc = smClass.getDeclaredMethod("listServices");
            listSvc.setAccessible(true);
            String[] all = (String[]) listSvc.invoke(null);
            if (all != null) {
                for (String name : all) {
                    if (name != null && (name.contains("a2dp")
                            || name.contains("avrcp")
                            || name.contains("profile"))) {
                        if (a2dpSvcs.length() > 0) {
                            a2dpSvcs.append('|');
                        }
                        a2dpSvcs.append(name);
                    }
                }
            }
            sb.append("\"profileSvcs\":\"").append(escape(a2dpSvcs.toString())).append("\"");
        } catch (Throwable t) {
            sb.append("\"error\":\"").append(escape(describe(t))).append("\",");
            sb.append("\"adapter\":false");
        }
        sb.append('}');
        print(sb.toString());
    }

    private static String methodList(String className, int limit) {
        try {
            Class<?> type = Class.forName(className);
            StringBuilder sb = new StringBuilder();
            for (Method m : type.getDeclaredMethods()) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(m.getName()).append('(');
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(params[i].getSimpleName());
                }
                sb.append(')');
                if (--limit <= 0) {
                    sb.append("|...");
                    break;
                }
            }
            return sb.toString();
        } catch (Throwable t) {
            return "unavailable: " + describe(t);
        }
    }

    private static String execute(BluetoothProfile proxy, String action, int rate, int bits) {
        Object device = activeDevice(proxy);
        if (device == null) {
            return error("no_active_device"
                    + (sActiveDeviceError != null ? ": " + sActiveDeviceError : ""));
        }

        Object status;
        try {
            status = callChecked(
                    proxy, "getCodecStatus", new Class<?>[]{BluetoothDevice.class}, device);
        } catch (Throwable t) {
            return error("codec_status: " + describe(t));
        }
        if (status == null) {
            return error("codec_status_unavailable");
        }
        Object cfg;
        try {
            cfg = callChecked(status, "getCodecConfig", new Class<?>[]{});
        } catch (Throwable t) {
            return error("codec_config: " + describe(t));
        }
        if (cfg == null) {
            return error("codec_config_null");
        }

        int codecType = asInt(call(cfg, "getCodecType", new Class<?>[]{}));
        int selRateMask = 0;
        int selBitsMask = 0;
        Object caps = call(status, "getCodecsSelectableCapabilities", new Class<?>[]{});
        if (caps instanceof List<?>) {
            for (Object cap : (List<?>) caps) {
                if (asInt(call(cap, "getCodecType", new Class<?>[]{})) == codecType) {
                    selRateMask = asInt(call(cap, "getSampleRate", new Class<?>[]{}));
                    selBitsMask = asInt(call(cap, "getBitsPerSample", new Class<?>[]{}));
                    break;
                }
            }
        }

        boolean applied = false;
        String applyError = null;

        if ("apply".equals(action)) {
            int rateMask = rateMask(rate);
            int bitsMask = bitsMask(bits);
            if (rateMask == 0 || bitsMask == 0) {
                return error("bad_target");
            }
            if (selRateMask != 0 && (rateMask & selRateMask) == 0) {
                return error("unsupported_rate");
            }
            if (selBitsMask != 0 && (bitsMask & selBitsMask) == 0) {
                return error("unsupported_bits");
            }
            try {
                Object newCfg = buildConfig(cfg, rateMask, bitsMask);
                // Checked call: a rejected write must be reported as an error,
                // not silently swallowed.
                callChecked(
                        proxy,
                        "setCodecConfigPreference",
                        new Class<?>[]{BluetoothDevice.class, newCfg.getClass()},
                        device,
                        newCfg
                );
                applied = true;
                Thread.sleep(700);
                Object after = call(
                        proxy, "getCodecStatus", new Class<?>[]{BluetoothDevice.class}, device);
                Object afterCfg = after != null
                        ? call(after, "getCodecConfig", new Class<?>[]{})
                        : null;
                if (afterCfg != null) {
                    cfg = afterCfg;
                }
            } catch (Throwable t) {
                applyError = describe(t);
            }
        }

        int currentRate = maskToRate(asInt(call(cfg, "getSampleRate", new Class<?>[]{})));
        int currentBits = maskToBits(asInt(call(cfg, "getBitsPerSample", new Class<?>[]{})));
        String deviceAddress = "";
        try {
            Object address = call(device, "getAddress", new Class<?>[]{});
            if (address != null) {
                deviceAddress = address.toString();
            }
        } catch (Throwable ignored) {
        }

        String deviceName = "";
        try {
            Object name = call(device, "getName", new Class<?>[]{});
            if (name != null) {
                deviceName = name.toString();
            }
        } catch (Throwable ignored) {
        }

        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"ok\":").append(applyError == null).append(',');
        sb.append("\"action\":\"").append(action).append("\",");
        sb.append("\"applied\":").append(applied).append(',');
        sb.append("\"connected\":true,");
        sb.append("\"codecType\":").append(codecType).append(',');
        sb.append("\"codec\":\"").append(codecName(codecType)).append("\",");
        sb.append("\"rate\":").append(currentRate).append(',');
        sb.append("\"bits\":").append(currentBits).append(',');
        sb.append("\"selRateMask\":").append(selRateMask).append(',');
        sb.append("\"selBitsMask\":").append(selBitsMask).append(',');
        sb.append("\"selRates\":\"").append(rateList(selRateMask)).append("\",");
        sb.append("\"selBits\":\"").append(bitsList(selBitsMask)).append("\",");
        sb.append("\"device\":\"").append(escape(deviceAddress)).append("\",");
        sb.append("\"deviceName\":\"").append(escape(deviceName)).append("\",");
        sb.append("\"error\":");
        if (applyError == null) {
            sb.append("null");
        } else {
            sb.append('"').append(escape(applyError)).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static Object buildConfig(Object current, int rateMask, int bitsMask) throws Exception {
        Class<?> builderClass = Class.forName("android.bluetooth.BluetoothCodecConfig$Builder");
        java.lang.reflect.Constructor<?> ctor = builderClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object builder = ctor.newInstance();

        int codecType = asInt(call(current, "getCodecType", new Class<?>[]{}));
        int channelMode = asInt(call(current, "getChannelMode", new Class<?>[]{}));
        long specific1 = asLong(call(current, "getCodecSpecific1", new Class<?>[]{}));
        long specific2 = asLong(call(current, "getCodecSpecific2", new Class<?>[]{}));
        long specific3 = asLong(call(current, "getCodecSpecific3", new Class<?>[]{}));
        long specific4 = asLong(call(current, "getCodecSpecific4", new Class<?>[]{}));

        callChecked(builder, "setCodecType", new Class<?>[]{int.class}, codecType);
        callChecked(builder, "setCodecPriority", new Class<?>[]{int.class}, 1000);
        callChecked(builder, "setSampleRate", new Class<?>[]{int.class}, rateMask);
        callChecked(builder, "setBitsPerSample", new Class<?>[]{int.class}, bitsMask);
        callChecked(builder, "setChannelMode", new Class<?>[]{int.class}, channelMode);
        callChecked(builder, "setCodecSpecific1", new Class<?>[]{long.class}, specific1);
        callChecked(builder, "setCodecSpecific2", new Class<?>[]{long.class}, specific2);
        callChecked(builder, "setCodecSpecific3", new Class<?>[]{long.class}, specific3);
        callChecked(builder, "setCodecSpecific4", new Class<?>[]{long.class}, specific4);

        Object config = callChecked(builder, "build", new Class<?>[]{});
        if (config == null) {
            throw new IllegalStateException("builder returned null");
        }
        return config;
    }

    /**
     * Resolves the Bluetooth adapter.
     *
     * On some OEM builds (seen on HyperOS/Android 17) the framework's
     * {@code BluetoothAdapter.getDefaultAdapter()} refuses to hand an adapter
     * to a non-app process even when the binder itself is present. Building
     * the adapter from the raw IBluetoothManager binder works in that case.
     */
    private static BluetoothAdapter resolveAdapter(Context context, BluetoothManager manager) {
        ensureBluetoothFrameworkInitialized();
        if (manager != null) {
            try {
                BluetoothAdapter adapter = manager.getAdapter();
                if (adapter != null) {
                    return adapter;
                }
            } catch (Throwable t) {
                System.err.println("manager.getAdapter failed: " + describe(t));
            }
        }

        try {
            Object binder = serviceBinder("bluetooth_manager");
            if (binder == null) {
                System.err.println("bluetooth_manager binder is null");
                return null;
            }
            Class<?> stub = Class.forName("android.bluetooth.IBluetoothManager$Stub");
            Method asInterface = stub.getMethod("asInterface", android.os.IBinder.class);
            Object service = asInterface.invoke(null, binder);

            // The adapter stamps every binder call with an AttributionSource.
            // If its uid does not match the real calling uid, the Bluetooth
            // service rejects the call, so build one for uid 0 (root).
            Object attributionSource = buildAttributionSource(context, 0);

            for (java.lang.reflect.Constructor<?> ctor
                    : BluetoothAdapter.class.getDeclaredConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length == 3
                        && attributionSource != null
                        && types[0].isInstance(service)
                        && types[1].isInstance(context)
                        && types[2].isInstance(attributionSource)) {
                    ctor.setAccessible(true);
                    BluetoothAdapter adapter =
                            (BluetoothAdapter) ctor.newInstance(service, context, attributionSource);
                    System.err.println("built BluetoothAdapter from binder (uid 0 attribution)");
                    return adapter;
                }
            }

            for (java.lang.reflect.Constructor<?> ctor
                    : BluetoothAdapter.class.getDeclaredConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length == 2
                        && types[0].isInstance(service)
                        && types[1].isInstance(context)) {
                    ctor.setAccessible(true);
                    BluetoothAdapter adapter = (BluetoothAdapter) ctor.newInstance(service, context);
                    System.err.println("built BluetoothAdapter from binder (default attribution)");
                    return adapter;
                }
            }
            System.err.println("no usable BluetoothAdapter constructor");
        } catch (Throwable t) {
            System.err.println("direct adapter build failed: " + describe(t));
        }
        return null;
    }

    /**
     * Installs the Bluetooth service registerer.
     *
     * `app_process` starts a bare runtime without the `SystemServiceRegistry`
     * bootstrap, so `android.os.BluetoothServiceManager` has no registerer and
     * every profile call dies with a NullPointerException. Registering the
     * "bluetooth_manager" binder directly is more reliable than replaying the
     * module bootstrap, which refuses to run outside class initialization.
     */
    private static synchronized void ensureBluetoothFrameworkInitialized() {
        if (sFrameworkInitialized) {
            return;
        }

        // Preferred: run the real framework bootstrap. It knows exactly where
        // the registerer belongs, including future layout changes.
        try {
            Class<?> registry = Class.forName("android.app.SystemServiceRegistry");
            Method register = registry.getDeclaredMethod("registerServiceWrappers");
            register.setAccessible(true);
            register.invoke(null);
            Class<?> bsm = Class.forName("android.os.BluetoothServiceManager");
            if (haveServiceRegisterer(bsm)) {
                sFrameworkInitialized = true;
                System.err.println("SystemServiceRegistry.registerServiceWrappers() ok");
                return;
            }
            System.err.println("registerServiceWrappers ran, registerer still null");
        } catch (Throwable t) {
            System.err.println("SystemServiceRegistry bootstrap failed: " + describe(t));
        }

        try {
            Class<?> bsm = Class.forName("android.os.BluetoothServiceManager");
            if (haveServiceRegisterer(bsm)) {
                sFrameworkInitialized = true;
                return;
            }

            Class<?> registererClass =
                    Class.forName("android.os.BluetoothServiceManager$ServiceRegisterer");
            Object registerer = newServiceRegisterer(registererClass);
            if (registerer == null) {
                System.err.println("could not construct a ServiceRegisterer");
                return;
            }

            int installed = 0;
            for (java.lang.reflect.Field field : bsm.getDeclaredFields()) {
                if (!field.getType().isAssignableFrom(registererClass)) {
                    continue;
                }
                field.setAccessible(true);
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    field.set(null, registerer);
                    installed++;
                }
            }

            if (haveServiceRegisterer(bsm)) {
                sFrameworkInitialized = true;
                System.err.println("installed bluetooth_manager ServiceRegisterer");
            } else {
                System.err.println("ServiceRegisterer fields patched=" + installed
                        + " but getter still null");
            }
        } catch (Throwable t) {
            System.err.println("bluetooth registerer install failed: " + describe(t));
        }
    }

    private static boolean haveServiceRegisterer(Class<?> bsm) {
        try {
            Method getter = bsm.getDeclaredMethod("getBluetoothManagerServiceRegisterer");
            getter.setAccessible(true);
            return getter.invoke(null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Object newServiceRegisterer(Class<?> registererClass) {
        for (java.lang.reflect.Constructor<?> ctor : registererClass.getDeclaredConstructors()) {
            Class<?>[] types = ctor.getParameterTypes();
            try {
                ctor.setAccessible(true);
                if (types.length == 1 && types[0] == String.class) {
                    return ctor.newInstance("bluetooth_manager");
                }
                if (types.length == 0) {
                    return ctor.newInstance();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object serviceBinder(String name) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        return getService.invoke(null, name);
    }

    /**
     * Builds an {@code android.content.AttributionSource} for [uid].
     *
     * Binder calls from this process are stamped with the adapter's
     * attribution source, and the Bluetooth service validates codec access
     * against that uid rather than the process uid. Building one for uid 0
     * makes the framework treat the write as root.
     */
    private static Object buildAttributionSource(Context context, int uid) {
        try {
            Class<?> builderClass = Class.forName("android.content.AttributionSource$Builder");
            Object builder = null;
            for (java.lang.reflect.Constructor<?> ctor : builderClass.getDeclaredConstructors()) {
                Class<?>[] types = ctor.getParameterTypes();
                if (types.length == 1 && types[0] == int.class) {
                    ctor.setAccessible(true);
                    builder = ctor.newInstance(uid);
                    break;
                }
            }
            if (builder == null) {
                System.err.println("no AttributionSource.Builder(int) constructor");
                return null;
            }
            try {
                callChecked(
                        builder,
                        "setPackageName",
                        new Class<?>[]{String.class},
                        context.getPackageName()
                );
            } catch (Throwable t) {
                System.err.println("setPackageName failed: " + describe(t));
            }
            return callChecked(builder, "build", new Class<?>[]{});
        } catch (Throwable t) {
            System.err.println("buildAttributionSource failed: " + describe(t));
            return null;
        }
    }

    private static Object activeDevice(BluetoothProfile proxy) {
        try {
            Object device = call(proxy, "getActiveDevice", new Class<?>[]{});
            if (device != null) {
                return device;
            }
            sActiveDeviceError = "getActiveDevice returned null";
        } catch (Throwable t) {
            sActiveDeviceError = "getActiveDevice: " + describe(t);
        }
        try {
            List<?> connected = proxy.getConnectedDevices();
            if (connected != null && !connected.isEmpty()) {
                return connected.get(0);
            }
            sActiveDeviceError += " / getConnectedDevices empty";
        } catch (Throwable t) {
            sActiveDeviceError += " / getConnectedDevices: " + describe(t);
        }
        return null;
    }

    private static Context systemContext() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Object thread = null;
        try {
            Method current = atClass.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            thread = current.invoke(null);
        } catch (Throwable ignored) {
        }
        if (thread == null) {
            Method systemMain = atClass.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            thread = systemMain.invoke(null);
        }
        Method getContext = atClass.getMethod("getSystemContext");
        getContext.setAccessible(true);
        Object ctx = getContext.invoke(thread);
        if (!(ctx instanceof Context)) {
            throw new IllegalStateException("no system context");
        }
        return (Context) ctx;
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(name, types);
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable first) {
            try {
                Method m = findMethod(target.getClass(), name, types);
                if (m == null) {
                    throw first;
                }
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    private static Object callChecked(
            Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getMethod(name, types);
        m.setAccessible(true);
        return m.invoke(target, args);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] types) {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredMethod(name, types);
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static int rateMask(int hz) {
        switch (hz) {
            case 44100: return 0x1;
            case 48000: return 0x2;
            case 88200: return 0x4;
            case 96000: return 0x8;
            case 176400: return 0x10;
            case 192000: return 0x20;
            default: return 0;
        }
    }

    private static int bitsMask(int bits) {
        switch (bits) {
            case 16: return 0x1;
            case 24: return 0x2;
            case 32: return 0x4;
            default: return 0;
        }
    }

    private static int maskToRate(int mask) {
        if ((mask & 0x1) != 0) return 44100;
        if ((mask & 0x2) != 0) return 48000;
        if ((mask & 0x4) != 0) return 88200;
        if ((mask & 0x8) != 0) return 96000;
        if ((mask & 0x10) != 0) return 176400;
        if ((mask & 0x20) != 0) return 192000;
        return 0;
    }

    private static int maskToBits(int mask) {
        if ((mask & 0x1) != 0) return 16;
        if ((mask & 0x2) != 0) return 24;
        if ((mask & 0x4) != 0) return 32;
        return 0;
    }

    private static String rateList(int mask) {
        StringBuilder sb = new StringBuilder();
        append(sb, mask, 0x1, "44100");
        append(sb, mask, 0x2, "48000");
        append(sb, mask, 0x4, "88200");
        append(sb, mask, 0x8, "96000");
        append(sb, mask, 0x10, "176400");
        append(sb, mask, 0x20, "192000");
        return sb.toString();
    }

    private static String bitsList(int mask) {
        StringBuilder sb = new StringBuilder();
        append(sb, mask, 0x1, "16");
        append(sb, mask, 0x2, "24");
        append(sb, mask, 0x4, "32");
        return sb.toString();
    }

    private static void append(StringBuilder sb, int mask, int bit, String value) {
        if ((mask & bit) == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('|');
        }
        sb.append(value);
    }

    private static String codecName(int codecType) {
        switch (codecType) {
            case 0: return "SBC";
            case 1: return "AAC";
            case 2: return "aptX";
            case 3: return "aptX HD";
            case 4: return "LDAC";
            case 5: return "LC3";
            case 6: return "Opus";
            case 12: return "LHDC_V2";
            case 13: return "LHDC_V3";
            case 15: return "LHDC V5";
            default: return "CODEC_" + codecType;
        }
    }

    private static int asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static int safeInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String describe(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return cause.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
    }

    private static String error(String message) {
        return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    private static void print(String json) {
        System.out.println(json);
        System.out.flush();
        try {
            System.exit(0);
        } catch (Throwable ignored) {
        }
    }
}
