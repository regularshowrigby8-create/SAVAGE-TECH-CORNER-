package com.savage.soulbrowser.browser;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

/**
 * Connectivity probe used to distinguish "you are offline" from "this page failed".
 *
 * <p>Only a network that reports both INTERNET and VALIDATED capability counts as online, because
 * a captive portal is exactly the situation in which the browser would otherwise show a confusing
 * WebView error instead of an actionable message.
 */
public final class NetworkStatus {

    private final Context appContext;

    public NetworkStatus(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public boolean isOnline() {
        ConnectivityManager manager =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            // The platform always provides this service; if it is missing there is no way to prove
            // connectivity, so the request is allowed to proceed and the WebView reports failure.
            return true;
        }
        Network active = manager.getActiveNetwork();
        if (active == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(active);
        if (capabilities == null) {
            return false;
        }
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
