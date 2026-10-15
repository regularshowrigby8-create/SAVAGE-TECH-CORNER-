package com.savage.soulbrowser.browser;

import android.os.Message;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.savage.soulbrowser.domain.PermissionPolicy;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Chrome client: progress, titles, fail-closed permissions and popup handling (new tab). */
public final class SavageWebChromeClient extends WebChromeClient {

    /** Callbacks into the browser screen. */
    public interface Host {
        void onProgress(int progress);

        void onTitleReceived(String title);

        void onWebPermissionDenied(String origin, Set<String> resources);

        /**
         * Creates (and attaches) a WebView for a {@code window.open()} popup.
         *
         * @return the new WebView, or {@code null} when the tab limit is reached
         */
        WebView onCreateTabWebView();
    }

    private final Host host;

    public SavageWebChromeClient(Host host) {
        this.host = host;
    }

    @Override
    public void onProgressChanged(WebView view, int newProgress) {
        host.onProgress(newProgress);
    }

    @Override
    public void onReceivedTitle(WebView view, String title) {
        host.onTitleReceived(title);
    }

    @Override
    public void onPermissionRequest(PermissionRequest request) {
        if (request == null) {
            return;
        }
        String origin = request.getOrigin() == null ? null : request.getOrigin().toString();
        Set<String> resources = new HashSet<>(Arrays.asList(request.getResources()));
        PermissionPolicy.Decision decision = PermissionPolicy.evaluate(origin, resources);
        if (decision == PermissionPolicy.Decision.GRANT) {
            request.grant(request.getResources());
        } else {
            request.deny();
            host.onWebPermissionDenied(origin, resources);
        }
    }

    @Override
    public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
        callback.invoke(origin, false, false);
        host.onWebPermissionDenied(origin,
                Collections.singleton("android.webkit.resource.GEOLOCATION"));
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
            return false;
        }
        WebView newTab = host.onCreateTabWebView();
        if (newTab == null) {
            return false;
        }
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(newTab);
        resultMsg.sendToTarget();
        return true;
    }
}
