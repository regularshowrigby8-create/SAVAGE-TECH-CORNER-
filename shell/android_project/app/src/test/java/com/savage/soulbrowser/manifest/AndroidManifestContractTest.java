package com.savage.soulbrowser.manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * AC-028, AC-029, AC-043: the shipped manifest is the source of truth for what the app is allowed
 * to ask for. This test fails when a permission, an exported component or a cleartext switch is
 * added without the acceptance criteria being updated.
 */
public class AndroidManifestContractTest {

    private static final Set<String> EXPECTED_PERMISSIONS = new LinkedHashSet<>(Arrays.asList(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE"));

    private static Document manifest;

    @BeforeClass
    public static void parseManifest() throws Exception {
        File file = locateManifest();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        manifest = factory.newDocumentBuilder().parse(file);
        assertNotNull("manifest must parse", manifest.getDocumentElement());
    }

    private static final String[] RELATIVE_CANDIDATES = {
            "src/main/AndroidManifest.xml",
            "app/src/main/AndroidManifest.xml",
            "shell/android_project/app/src/main/AndroidManifest.xml"};

    /**
     * Finds the manifest whether the tests run from the module directory (Gradle), from the
     * repository root, or from anywhere else (an explicit {@code savage.manifest} system property
     * wins). Both the working directory and the location the test class was loaded from are
     * searched, so the check does not depend on how the JVM was started.
     */
    private static File locateManifest() {
        String override = System.getProperty("savage.manifest");
        if (override != null && new File(override).isFile()) {
            return new File(override);
        }
        for (File start : new File[] {workingDirectory(), codeSourceDirectory()}) {
            for (File dir = start; dir != null; dir = dir.getParentFile()) {
                for (String candidate : RELATIVE_CANDIDATES) {
                    File file = new File(dir, candidate);
                    if (file.isFile()) {
                        return file;
                    }
                }
            }
        }
        fail("AndroidManifest.xml not found from " + System.getProperty("user.dir")
                + " or " + workingDirectory());
        throw new IllegalStateException();
    }

    private static File workingDirectory() {
        return new File(System.getProperty("user.dir")).getAbsoluteFile();
    }

    private static File codeSourceDirectory() {
        try {
            java.net.URL location = AndroidManifestContractTest.class.getProtectionDomain()
                    .getCodeSource().getLocation();
            return new File(location.toURI()).getAbsoluteFile();
        } catch (Exception exception) {
            return workingDirectory();
        }
    }

    @Test
    public void permissionsAreMinimal() {
        Set<String> declared = new LinkedHashSet<>();
        NodeList permissions = manifest.getElementsByTagName("uses-permission");
        for (int i = 0; i < permissions.getLength(); i++) {
            declared.add(((Element) permissions.item(i)).getAttribute("android:name"));
        }
        assertEquals(EXPECTED_PERMISSIONS, declared);
    }

    @Test
    public void browserActivityHandlesOrientation() {
        NodeList activities = manifest.getElementsByTagName("activity");
        Element browser = null;
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            if (".ui.MainActivity".equals(activity.getAttribute("android:name"))) {
                browser = activity;
            }
        }
        assertNotNull("the browser activity must be declared", browser);
        String configChanges = browser.getAttribute("android:configChanges");
        assertTrue("the browser must keep its WebView across a rotation, so it declares "
                        + "android:configChanges with orientation|screenSize, found: " + configChanges,
                configChanges.contains("orientation") && configChanges.contains("screenSize"));
        assertTrue("a rotation must not restart the activity, so it also handles screenLayout",
                configChanges.contains("screenLayout"));
    }

    @Test
    public void declaresNoDangerousRuntimePermission() {
        String[] dangerous = {
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.READ_CONTACTS",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.POST_NOTIFICATIONS",
                "android.permission.READ_PHONE_STATE",
                "android.permission.BODY_SENSORS",
                "android.permission.MIDI_SYSEX",
                "android.permission.SYSTEM_ALERT_WINDOW",
                "android.permission.REQUEST_INSTALL_PACKAGES",
        };
        NodeList permissions = manifest.getElementsByTagName("uses-permission");
        for (int i = 0; i < permissions.getLength(); i++) {
            String name = ((Element) permissions.item(i)).getAttribute("android:name");
            for (String forbidden : dangerous) {
                assertFalse("must not declare " + forbidden, forbidden.equals(name));
            }
        }
    }

    @Test
    public void cleartextIsRefusedAndANetworkSecurityConfigIsConfigured() {
        Element application = (Element) manifest.getElementsByTagName("application").item(0);
        assertEquals("false", application.getAttribute("android:usesCleartextTraffic"));
        assertEquals("@xml/network_security_config",
                application.getAttribute("android:networkSecurityConfig"));
        assertEquals("false", application.getAttribute("android:allowBackup"));
    }

    @Test
    public void everyActivitySetsExportedExplicitlyAndOnlyLauncherIsExported() {
        NodeList activities = manifest.getElementsByTagName("activity");
        assertTrue("the app has at least the browser activity", activities.getLength() >= 1);
        List<String> exported = new ArrayList<>();
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            String name = activity.getAttribute("android:name");
            assertTrue(name + " must set android:exported explicitly",
                    activity.hasAttribute("android:exported"));
            if ("true".equals(activity.getAttribute("android:exported"))) {
                exported.add(name);
            }
        }
        assertEquals(Arrays.asList(".ui.MainActivity"), exported);
    }

    @Test
    public void launcherIntentFilterPointsAtMainActivityAndNothingElse() {
        List<String> launchers = new ArrayList<>();
        NodeList activities = manifest.getElementsByTagName("activity");
        for (int i = 0; i < activities.getLength(); i++) {
            Element activity = (Element) activities.item(i);
            NodeList filters = activity.getElementsByTagName("intent-filter");
            for (int f = 0; f < filters.getLength(); f++) {
                Element filter = (Element) filters.item(f);
                if (hasChild(filter, "action", "android.intent.action.MAIN")
                        && hasChild(filter, "category", "android.intent.category.LAUNCHER")) {
                    launchers.add(activity.getAttribute("android:name"));
                }
            }
        }
        assertEquals(Arrays.asList(".ui.MainActivity"), launchers);
    }

    @Test
    public void applicationIsNotDebuggableInTheShippedManifest() {
        Element application = (Element) manifest.getElementsByTagName("application").item(0);
        assertNull("android:debuggable must come from the build type, not the manifest",
                application.getAttributeNode("android:debuggable"));
        assertEquals(".SavageBrowserApp", application.getAttribute("android:name"));
        assertEquals("@style/Theme.SavageBrowser", application.getAttribute("android:theme"));
    }

    @Test
    public void safeBrowsingIsRequested() {
        boolean enabled = false;
        NodeList metas = manifest.getElementsByTagName("meta-data");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if ("android.webkit.WebView.EnableSafeBrowsing".equals(meta.getAttribute("android:name"))
                    && "true".equals(meta.getAttribute("android:value"))) {
                enabled = true;
            }
        }
        assertTrue("Safe Browsing must be requested", enabled);
    }

    private static boolean hasChild(Element parent, String tag, String name) {
        NodeList children = parent.getElementsByTagName(tag);
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element
                    && name.equals(((Element) node).getAttribute("android:name"))) {
                return true;
            }
        }
        return false;
    }
}
