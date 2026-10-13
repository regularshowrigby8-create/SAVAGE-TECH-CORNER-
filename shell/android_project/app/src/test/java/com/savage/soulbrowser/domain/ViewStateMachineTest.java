package com.savage.soulbrowser.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** AC-009, AC-019, AC-020, AC-036: deterministic loading/error/offline/blocked behaviour. */
public class ViewStateMachineTest {

    @Test
    public void freshMachineShowsLoadingOverlay() {
        ViewStateMachine machine = new ViewStateMachine();
        assertEquals(ViewState.LOADING, machine.state());
        assertTrue(machine.showsOverlay());
        assertFalse(machine.showsRetry());
    }

    @Test
    public void progressShowsAndHidesLoading() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onNavigationStarted();
        machine.onProgress(10);
        assertEquals(ViewState.LOADING, machine.state());
        assertTrue(machine.showsOverlay());
        assertFalse(machine.showsRetry());

        machine.onProgress(100);
        assertEquals(ViewState.CONTENT, machine.state());
        assertFalse(machine.showsOverlay());
        assertFalse(machine.showsRetry());
    }

    @Test
    public void loadingAnAlreadyLoadedPageKeepsContentVisible() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onContentReady();
        machine.onNavigationStarted();
        machine.onProgress(30);
        assertEquals(ViewState.CONTENT, machine.state());
        assertFalse("a second load must not blank the current page", machine.showsOverlay());
    }

    @Test
    public void errorShowsRetryAndMessage() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onLoadError();
        assertEquals(ViewState.ERROR, machine.state());
        assertTrue(machine.showsOverlay());
        assertTrue(machine.showsRetry());
    }

    @Test
    public void offlineShowsRetry() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onOffline();
        assertEquals(ViewState.OFFLINE, machine.state());
        assertTrue(machine.showsOverlay());
        assertTrue(machine.showsRetry());
    }

    @Test
    public void blockedKeepsItsMessageAndOffersNoRetry() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onBlocked();
        assertEquals(ViewState.BLOCKED, machine.state());
        // A blocked address was refused by a static rule, so a retry cannot succeed; the message is
        // what the user gets. (AC-022: a refusal is explained, not retried.)
        assertFalse("a refusal can never succeed, so it must not promise a retry",
                machine.showsRetry());
        assertFalse("a refused navigation is not a queued retry",
                machine.requestRetry());
        // WebView still reports 100% after a refused/failed load; the message must stay visible.
        machine.onProgress(100);
        assertEquals(ViewState.BLOCKED, machine.state());
        assertTrue(machine.showsOverlay());
    }

    @Test
    public void tlsErrorShowsRetryAndIsNotSilentlySwallowed() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onContentReady();
        machine.onTlsError();
        assertEquals(ViewState.TLS_ERROR, machine.state());
        assertTrue(machine.showsRetry());
    }

    @Test
    public void doubleRetryIsCoalesced() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onOffline();
        assertTrue("first retry must start work", machine.requestRetry());
        assertFalse("second retry while pending must be ignored", machine.requestRetry());
        assertTrue(machine.isRetryPending());

        machine.onNavigationStarted();
        assertFalse(machine.isRetryPending());
        machine.onOffline();
        assertTrue("a new failure may be retried again", machine.requestRetry());
    }

    @Test
    public void retryIsNotOfferedWhileLoading() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onNavigationStarted();
        machine.onProgress(20);
        assertFalse(machine.requestRetry());
    }

    @Test
    public void progressBarOnlyWhileLoading() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onNavigationStarted();
        machine.onProgress(40);
        assertTrue(machine.showsProgressBar());
        machine.onProgress(100);
        assertFalse(machine.showsProgressBar());
    }

    @Test
    public void progressBarTracksTheLoadNotTheOverlay() {
        // A navigation that starts while a page is displayed keeps the page (no overlay) and still
        // shows the bar: the bar is the only sign the browser is working in that case.
        ViewStateMachine machine = new ViewStateMachine();
        machine.onContentReady();
        assertFalse("nothing is in flight once the page is ready", machine.showsProgressBar());

        machine.onNavigationStarted();
        assertEquals("the loaded page stays on screen", ViewState.CONTENT, machine.state());
        assertFalse("the overlay must not cover a page the user is reading", machine.showsOverlay());
        assertTrue("a load in flight shows the bar", machine.showsProgressBar());

        machine.onProgress(60);
        assertTrue(machine.showsProgressBar());
        machine.onContentReady();
        assertFalse(machine.showsProgressBar());
    }

    @Test
    public void progressBarIsHiddenForAFailure() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onNavigationStarted();
        machine.onLoadError();
        assertFalse("the bar must not animate over a failure message", machine.showsProgressBar());
        // WebView still reports 100% after a failed load; that must not restart the bar either.
        machine.onProgress(100);
        assertFalse(machine.showsProgressBar());
        assertEquals("the failure message stays on screen", ViewState.ERROR, machine.state());
    }

    @Test
    public void progressIsClamped() {
        ViewStateMachine machine = new ViewStateMachine();
        machine.onProgress(-10);
        assertEquals(0, machine.progress());
        machine.onProgress(600);
        assertEquals(100, machine.progress());
        assertEquals(ViewState.CONTENT, machine.state());
    }
}
