package org.omri.radio.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * libirtdab resolves Java classes and methods by name when the tuner is attached; a missing
 * or misspelled one aborts the process on the head unit, where it cannot be unit-tested.
 * So this reads the lookups straight out of the C++ sources and checks each against the
 * Java classes.
 */
public class JniContractTest {
    private static final File CPP = new File("src/main/cpp/irtdab/platformspecific/android");

    /** jclass variable in the C++ code -> Java class it holds. */
    private static final Map<String, Class<?>> CLASSES = new HashMap<String, Class<?>>();

    static {
        CLASSES.put("m_usbHelperClass", UsbHelper.class);
        CLASSES.put("m_usbTunerClass", TunerUsb.class);
        CLASSES.put("m_dabServiceClass", RadioServiceDabImpl.class);
        CLASSES.put("m_javaDabServiceClass", RadioServiceDabImpl.class);
        CLASSES.put("m_dabServiceComponentClass", RadioServiceDabComponentImpl.class);
        CLASSES.put("m_dabServiceUserApplicationClass", RadioServiceDabUserApplicationImpl.class);
        CLASSES.put("m_termIdClass", TermIdImpl.class);
        CLASSES.put("m_javaDlsClass", TextualDabDynamicLabelImpl.class);
        CLASSES.put("m_javaDlPlusItemClass", TextualDabDynamicLabelPlusItemImpl.class);
        CLASSES.put("m_javaSlsClass", VisualDabSlideShowImpl.class);
    }

    /**
     * The android.hardware.usb methods that exist since API 12. The unit-test classpath has
     * the newest android.jar, so reflection cannot tell what the API 17 head unit lacks.
     */
    private static final Set<String> USB_API12 = new HashSet<String>(Arrays.asList(
            "getDeviceName()Ljava/lang/String;", "getProductId()I", "getVendorId()I", "getInterfaceCount()I",
            "getInterface(I)Landroid/hardware/usb/UsbInterface;",
            "claimInterface(Landroid/hardware/usb/UsbInterface;Z)Z",
            "releaseInterface(Landroid/hardware/usb/UsbInterface;)Z",
            "bulkTransfer(Landroid/hardware/usb/UsbEndpoint;[BII)I",
            "getEndpointCount()I", "getEndpoint(I)Landroid/hardware/usb/UsbEndpoint;",
            "getEndpointNumber()I", "getAddress()I", "getDirection()I", "getInterval()I"));

    private static String read(String name) throws Exception {
        File f = new File(CPP, name);
        assertTrue("run from the app module: " + f.getAbsolutePath(), f.exists());
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        try {
            int off = 0;
            while (off < b.length) off += in.read(b, off, b.length - off);
        } finally {
            in.close();
        }
        return new String(b, "UTF-8");
    }

    private static String descriptor(Class<?> c) {
        if (c == void.class) return "V";
        if (c == int.class) return "I";
        if (c == boolean.class) return "Z";
        if (c == long.class) return "J";
        if (c == byte.class) return "B";
        if (c.isArray()) return "[" + descriptor(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static String signature(Class<?>[] params, Class<?> result) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(descriptor(p));
        return sb.append(')').append(descriptor(result)).toString();
    }

    private static boolean has(Class<?> c, String name, String sig, boolean isStatic) {
        if ("<init>".equals(name)) {
            for (Constructor<?> k : c.getDeclaredConstructors()) {
                if (signature(k.getParameterTypes(), void.class).equals(sig)) return true;
            }
            return false;
        }
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && signature(m.getParameterTypes(), m.getReturnType()).equals(sig)
                    && Modifier.isStatic(m.getModifiers()) == isStatic) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void everyMethodTheNativeLibraryLooksUpExists() throws Exception {
        Pattern lookup = Pattern.compile("Get(Static)?MethodID\\(\\s*(\\w+)\\s*,\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+)\"\\s*\\)");
        int checked = 0;
        for (String file : new String[]{"jusbdevice.cpp", "jtunerusbdevice.cpp", "jdabservice.cpp"}) {
            Matcher m = lookup.matcher(read(file));
            while (m.find()) {
                Class<?> c = CLASSES.get(m.group(2));
                if (c == null) { // android.hardware.usb.* classes of the platform
                    assertTrue(file + ": " + m.group(3) + m.group(4) + " is not in the USB host API of Android 3.1."
                            + " A failed lookup leaves an exception pending and Dalvik then aborts the process.",
                            USB_API12.contains(m.group(3) + m.group(4)));
                    continue;
                }
                if (!has(c, m.group(3), m.group(4), m.group(1) != null)) {
                    fail(file + ": " + c.getName() + "." + m.group(3) + m.group(4) + " is looked up by libirtdab but missing");
                }
                checked++;
            }
        }
        // 4 UsbHelper + 7 TunerUsb + 18 service + 19 component + 9 user app + 4 term id + 23 payload
        assertTrue("expected the full set of lookups, found " + checked, checked >= 80);
        // The one lookup written inline rather than through a class variable.
        assertTrue(has(TunerUsb.class, "getUsbDevice", "()Landroid/hardware/usb/UsbDevice;", false));
    }

    @Test
    public void everyClassTheNativeLibraryLoadsExists() throws Exception {
        Matcher m = Pattern.compile("FindClass\\(\"(org/omri/[^\"]+)\"\\)").matcher(
                read("native-lib.cpp") + read("jusbdevice.cpp") + read("jtunerusbdevice.cpp"));
        int n = 0;
        while (m.find()) {
            Class.forName(m.group(1).replace('/', '.'));
            n++;
        }
        assertTrue(n >= 9);
    }

    @Test
    public void nativeMethodsMatchTheLibraryExports() throws Exception {
        Set<String> exported = new HashSet<String>();
        Matcher m = Pattern.compile("Java_org_omri_radio_impl_UsbHelper_(\\w+)\\(").matcher(read("native-lib.cpp"));
        while (m.find()) exported.add(m.group(1));
        Set<String> declared = new HashSet<String>();
        for (Method method : UsbHelper.class.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())) declared.add(method.getName());
        }
        for (String d : declared) assertTrue("no native implementation for UsbHelper." + d, exported.contains(d));
        // tuneFreq is exported upstream but is an empty stub there; Welle does not declare it.
        exported.remove("tuneFreq");
        assertEquals(exported, declared);
    }
}
