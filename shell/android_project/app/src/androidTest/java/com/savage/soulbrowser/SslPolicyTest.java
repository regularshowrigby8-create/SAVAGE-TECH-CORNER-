package com.savage.soulbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.view.View;
import android.webkit.WebView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.savage.soulbrowser.browser.SavageWebViewClient;
import com.savage.soulbrowser.domain.ViewState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.savage.soulbrowser.ui.MainActivity;

/**
 * AC-021: a certificate error must be refused and reported.
 *
 * <p>This hands the client the running browser installed on its real {@code WebView} a real
 * framework {@code SslError} - built from a real (self-issued) certificate description, the way the
 * framework builds it from the certificate the server presented - and asserts what the browser did
 * with it: the failure was reported and the user is told the connection is not secure.
 *
 * <p>What this test cannot observe is the framework {@code SslErrorHandler} call: the SDK declares no
 * public constructor for it, so no instrumented test can subclass it and record whether the browser
 * called {@code cancel()} or {@code proceed()}. An earlier version of this test tried to infer the
 * answer by loading a host with a genuinely invalid certificate over the network, which made the
 * acceptance criterion depend on the emulator's egress and could only have shown that a page did not
 * render. The refusal itself is pinned where it can be checked exhaustively instead:
 * {@code SslDecisionTest} (JVM) proves every framework error code resolves to CANCEL, and
 * {@code shell/tests/test_ssl_fail_closed.py} proves no application source contains the accept path
 * at all. This test covers the half that needs a device: the real client and the real screen.
 */
@RunWith(AndroidJUnit4.class)
public class SslPolicyTest {

    private static final String BAD_CERT_HOST = "https://self-signed.example/";

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @After
    public void restoreRadios() {
        TestSupport.setNetworkEnabled(true);
    }

    /**
     * The certificate the "server" presented: a self-issued one, described exactly as the framework
     * describes a certificate in an {@link SslError}. It carries no key, so nothing about it is a
     * credential - it exists so the client under test receives a real error object.
     */
    private static SslCertificate selfIssuedCertificate() {
        return new SslCertificate("CN=self-signed.example", "CN=self-signed.example",
                "2026-01-01", "2027-01-01");
    }

    @Test
    public void tlsFailureIsRefusedAndVisible() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            SslError error = new SslError(SslError.SSL_UNTRUSTED, selfIssuedCertificate(),
                    BAD_CERT_HOST);

            TestSupport.on(scenario, activity -> {
                WebView webView = TestSupport.webViewOf(activity);
                assertNotNull("the running browser must have installed its own WebView client",
                        webView.getWebViewClient());
                assertTrue("the installed client is the production one",
                        webView.getWebViewClient() instanceof SavageWebViewClient);
                // A null handler is the one case the production path guards against: the browser
                // still has to report the failure. Whether cancel() is called is settled by the JVM
                // decision test and the source check named above, because the SDK offers no way to
                // observe the framework handler from here.
                ((SavageWebViewClient) webView.getWebViewClient())
                        .onReceivedSslError(webView, null, error);
            });

            TestSupport.waitUntil("the refusal to be shown to the user",
                    () -> TestSupport.onResult(scenario, MainActivity::currentViewState)
                            == ViewState.TLS_ERROR,
                    () -> TestSupport.describeBrowser(scenario));

            TestSupport.on(scenario, activity -> {
                assertEquals("the refusal replaces the page",
                        View.VISIBLE, TestSupport.visibilityOf(activity, R.id.state_overlay));
                assertEquals(activity.getString(R.string.state_ssl_title),
                        TestSupport.textOf(activity, R.id.state_title));
                assertEquals(activity.getString(R.string.state_ssl_message),
                        TestSupport.textOf(activity, R.id.state_message));
                assertEquals("a refused connection can be retried once the user has a good address",
                        View.VISIBLE, TestSupport.visibilityOf(activity, R.id.state_retry));
                assertNotNull("the WebView still exists for the next navigation",
                        TestSupport.webViewOf(activity));
            });
        }
    }
}
