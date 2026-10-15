package com.savage.soulbrowser.browser;

/**
 * TLS policy of the WebView client.
 *
 * <p>Fail-closed by construction: the only possible outcome is {@link Action#CANCEL}. There is
 * deliberately no "proceed" branch to accidentally select, so no code path can ignore a
 * certificate error. {@code SavageWebViewClient} calls {@code handler.cancel()} unconditionally
 * for this action.
 */
public final class SslDecision {

    public enum Action {
        /** Abort the connection; nothing is sent to the server. */
        CANCEL
    }

    private SslDecision() {
    }

    /** @return always {@link Action#CANCEL}, for every host and every SSL error code. */
    public static Action decide(String host, int primaryError) {
        return Action.CANCEL;
    }

    /** Human-readable description used for the user-visible state and for the log line. */
    public static String describe(int primaryError) {
        switch (primaryError) {
            case 0:
                return "certificate not yet valid";
            case 1:
                return "certificate expired";
            case 2:
                return "hostname mismatch";
            case 3:
                return "certificate is not trusted";
            case 4:
                return "certificate date invalid";
            case 5:
                return "certificate has been revoked";
            default:
                return "TLS error " + primaryError;
        }
    }
}
