package com.savage.soulbrowser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.savage.soulbrowser.ui.MainActivity;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * AC-028 / AC-029: a page's permission request is refused and the refusal is visible, never silent.
 *
 * <p>This exercises the real object graph: the {@link WebChromeClient} that the activity installed on
 * the real {@link android.webkit.WebView}, driven through the platform's own
 * {@link GeolocationPermissions.Callback} contract. The denial is then observed twice, the way a
 * user or an engineer would observe it - as the record the activity keeps for the current screen
 * and as the logcat warning line.
 *
 * <p>{@code PermissionRequest} (camera, microphone, MIDI) has no public constructor, so its denial is
 * covered by {@code PermissionPolicyTest} at the policy level and by the absence of any runtime
 * permission in the installed package, which is asserted here.
 */
@RunWith(AndroidJUnit4.class)
public final class WebPermissionTest {

    private static final String TRUSTED_ORIGIN = "https://savage-tech-corner.io/";

    /** Records what the browser answered to a geolocation prompt. */
    private static final class RecordingCallback implements GeolocationPermissions.Callback {
        boolean invoked;
        boolean allow;
        boolean retain;
        String origin;

        @Override
        public void invoke(String origin, boolean allow, boolean retain) {
            this.invoked = true;
            this.origin = origin;
            this.allow = allow;
            this.retain = retain;
        }
    }

    @Test
    public void geolocationPromptIsDeniedAndReported() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            WebChromeClient client = TestSupport.onResult(scenario,
                    activity -> TestSupport.webViewOf(activity).getWebChromeClient());
            assertNotNull("the running browser must have installed its own chrome client", client);

            RecordingCallback callback = new RecordingCallback();
            TestSupport.on(scenario,
                    activity -> client.onGeolocationPermissionsShowPrompt(TRUSTED_ORIGIN, callback));

            assertTrue("the prompt must be answered, not ignored", callback.invoked);
            assertFalse("geolocation is never allowed", callback.allow);
            assertFalse("nothing is retained for a later request", callback.retain);
            Assert.assertEquals("the prompt is answered for the requesting origin",
                    TRUSTED_ORIGIN, callback.origin);

            TestSupport.waitUntil("the denial to be recorded for the screen",
                    () -> TestSupport.onResult(scenario, MainActivity::lastWebPermissionDenial) != null);
            String recorded = TestSupport.onResult(scenario, MainActivity::lastWebPermissionDenial);
            assertTrue("the record names the host that asked: " + recorded,
                    recorded.contains("savage-tech-corner.io"));
            assertTrue("the record names the denied resource: " + recorded,
                    recorded.contains("GEOLOCATION"));
            assertTrue("the record says why it was denied: " + recorded,
                    recorded.contains("no runtime permission declared by the manifest"));

            String visibleMessage = TestSupport.onResult(scenario,
                    activity -> activity.getString(R.string.permission_denied));
            assertFalse("the user is told, not left guessing", visibleMessage.trim().isEmpty());
        }
    }

    @Test
    public void denialIsLoggedForTheEmulatorLog() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            WebChromeClient client = TestSupport.onResult(scenario,
                    activity -> TestSupport.webViewOf(activity).getWebChromeClient());
            assertNotNull(client);
            RecordingCallback callback = new RecordingCallback();
            TestSupport.on(scenario,
                    activity -> client.onGeolocationPermissionsShowPrompt(
                            "https://untrusted.example/", callback));
            assertTrue("the untrusted prompt is answered", callback.invoked);

            // The app logs the denied host, never the full URL: the log is evidence, not a
            // browsing record. The host is what the log must name, and the test reads it back.
            TestSupport.waitUntil("the denial to reach the log",
                    () -> TestSupport.logcat("SavageBrowser:W", 2000)
                            .contains("Web permission denied for host=untrusted.example"),
                    () -> TestSupport.logcat("SavageBrowser:W", 2000));
            String log = TestSupport.logcat("SavageBrowser:W", 2000);
            assertTrue("the denial is logged with its reason: " + log,
                    log.contains("Web permission denied"));
            assertTrue("the log names the denying rule: " + log,
                    log.contains("untrusted origin") || log.contains("no runtime permission"));
        }
    }

    @Test
    public void theInstalledPackageRequestsNoRuntimePermission() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String packageName = context.getPackageName();

        int camera = context.checkSelfPermission(Manifest.permission.CAMERA);
        int recordAudio = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO);
        int fineLocation = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        Assert.assertEquals("camera must not be granted", PackageManager.PERMISSION_DENIED, camera);
        Assert.assertEquals("microphone must not be granted",
                PackageManager.PERMISSION_DENIED, recordAudio);
        Assert.assertEquals("location must not be granted",
                PackageManager.PERMISSION_DENIED, fineLocation);

        assertFalse("the app declares no runtime permission, so the browser cannot trade one away",
                com.savage.soulbrowser.domain.PermissionPolicy.DECLARED_RUNTIME_PERMISSIONS
                        .contains(Manifest.permission.CAMERA));
    }
}
