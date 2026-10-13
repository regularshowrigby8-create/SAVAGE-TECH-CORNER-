package com.savage.soulbrowser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.webkit.WebView;
import android.widget.EditText;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.savage.soulbrowser.domain.ViewState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import com.savage.soulbrowser.ui.MainActivity;

/**
 * AC-021: a certificate error must be refused and reported.
 *
 * <p>The test loads a host whose certificate is genuinely invalid over the network, so the real
 * {@code WebView}, the real framework {@code SslErrorHandler} and the production
 * {@code SavageWebViewClient} are all exercised. "Fail-closed" is observable without reaching into
 * framework internals: had the app called {@code proceed()}, the page would have rendered - the
 * test therefore asserts the page never renders and the user is told the connection was not secure.
 * ({@code SslDecision} has exactly one possible outcome, {@code CANCEL}, which the JVM test
 * {@code SslDecisionTest} pins down, and a repository check forbids {@code proceed(} anywhere in the
 * application sources.)
 *
 * <p>Requires network access from the emulator, like any real TLS failure.
 */
@RunWith(AndroidJUnit4.class)
public class SslPolicyTest {

    /** Serves a self-signed certificate: an unmodifiable TLS failure with a stable host name. */
    private static final String BAD_CERT_HOST = "https://self-signed.badssl.com/";

    @Before
    public void clearLocalState() {
        TestSupport.wipeLocalData();
    }

    @After
    public void restoreRadios() {
        TestSupport.setNetworkEnabled(true);
    }

    @Test
    public void tlsFailureIsRefusedAndVisible() {
        try (ActivityScenario<MainActivity> scenario = TestSupport.launchBrowser()) {
            TestSupport.on(scenario, activity -> {
                EditText omnibox = activity.findViewById(R.id.edit_omnibox);
                omnibox.setText(BAD_CERT_HOST);
                omnibox.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
            });

            TestSupport.waitUntil("the certificate error to be reported as TLS refusal", () ->
                    TestSupport.onResult(scenario, MainActivity::currentViewState) == ViewState.TLS_ERROR);

            TestSupport.on(scenario, activity -> {
                assertEquals("the refusal replaces the page",
                        View.VISIBLE, TestSupport.visibilityOf(activity, R.id.state_overlay));
                assertEquals(activity.getString(R.string.state_ssl_title),
                        TestSupport.textOf(activity, R.id.state_title));
                assertEquals(activity.getString(R.string.state_ssl_message),
                        TestSupport.textOf(activity, R.id.state_message));
                assertEquals("a TLS refusal can be retried",
                        View.VISIBLE, TestSupport.visibilityOf(activity, R.id.state_retry));

                WebView webView = TestSupport.webViewOf(activity);
                assertNotNull("the WebView still exists for the next navigation", webView);
                assertFalse("the browser must not report a loaded page for a refused connection",
                        activity.hasLoadedPage());
                assertFalse("the refusal state is not a content state",
                        activity.currentViewState() == ViewState.CONTENT);
                String title = webView.getTitle();
                assertTrue("the bad certificate page must never render its own content",
                        title == null || !title.toLowerCase(java.util.Locale.ROOT)
                                .contains("badssl"));
            });
        }
    }
}
