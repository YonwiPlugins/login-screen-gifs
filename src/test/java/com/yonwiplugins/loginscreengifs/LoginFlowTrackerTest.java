package com.yonwiplugins.loginscreengifs;

import net.runelite.api.GameState;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LoginFlowTrackerTest
{
    @Test
    public void keepsNormalLoginFlowAnimatedUntilLoggedIn()
    {
        LoginFlowTracker tracker = new LoginFlowTracker();

        LoginFlowTracker.Transition login = tracker.accept(GameState.LOGIN_SCREEN);
        assertTrue(login.isLoginStarted());
        assertTrue(login.isBackgroundVisible());
        assertTrue(login.isFrameUpdatesAllowed());
        assertFalse(login.shouldRestoreBackground());

        LoginFlowTracker.Transition loggingIn = tracker.accept(GameState.LOGGING_IN);
        assertFalse(loggingIn.isLoginStarted());
        assertTrue(loggingIn.isBackgroundVisible());
        assertTrue(loggingIn.isFrameUpdatesAllowed());

        LoginFlowTracker.Transition loading = tracker.accept(GameState.LOADING);
        assertTrue(loading.isBackgroundVisible());
        assertTrue(loading.isFrameUpdatesAllowed());

        assertTrue(tracker.accept(GameState.LOGGED_IN).shouldRestoreBackground());
        assertFalse(tracker.isLoginFlowActive());
    }

    @Test
    public void neverTreatsWorldHopLoadingAsLogin()
    {
        LoginFlowTracker tracker = new LoginFlowTracker();
        tracker.accept(GameState.LOGGED_IN);

        assertTrue(tracker.accept(GameState.HOPPING).shouldRestoreBackground());
        assertTrue(tracker.isWorldHopActive());

        LoginFlowTracker.Transition loading = tracker.accept(GameState.LOADING);
        assertTrue(loading.shouldRestoreBackground());
        assertFalse(loading.isBackgroundVisible());
        assertFalse(loading.isFrameUpdatesAllowed());
        assertTrue(tracker.isWorldHopActive());

        assertTrue(tracker.accept(GameState.LOGGED_IN).shouldRestoreBackground());
        assertFalse(tracker.isWorldHopActive());
    }

    @Test
    public void keepsBackgroundStableWhileChangingLoginWorlds()
    {
        LoginFlowTracker tracker = new LoginFlowTracker();
        tracker.accept(GameState.LOGIN_SCREEN);

        LoginFlowTracker.Transition hopping = tracker.accept(GameState.HOPPING);
        assertFalse(hopping.shouldRestoreBackground());
        assertTrue(hopping.isBackgroundVisible());
        assertFalse(hopping.isFrameUpdatesAllowed());
        assertTrue(tracker.isLoginScreenWorldSwitchActive());

        LoginFlowTracker.Transition loading = tracker.accept(GameState.LOADING);
        assertFalse(loading.shouldRestoreBackground());
        assertTrue(loading.isBackgroundVisible());
        assertFalse(loading.isFrameUpdatesAllowed());

        LoginFlowTracker.Transition returned = tracker.accept(GameState.LOGIN_SCREEN);
        assertFalse(returned.isLoginStarted());
        assertTrue(returned.isBackgroundVisible());
        assertTrue(returned.isFrameUpdatesAllowed());
        assertFalse(tracker.isLoginScreenWorldSwitchActive());
    }

    @Test
    public void failedHopCanBecomeARealLoginScreen()
    {
        LoginFlowTracker tracker = new LoginFlowTracker();
        tracker.accept(GameState.HOPPING);

        LoginFlowTracker.Transition login = tracker.accept(GameState.LOGIN_SCREEN);
        assertTrue(login.isLoginStarted());
        assertTrue(login.isBackgroundVisible());
        assertTrue(login.isFrameUpdatesAllowed());
        assertFalse(tracker.isWorldHopActive());
    }

    @Test
    public void authenticatorKeepsBackgroundButPausesFrameSwaps()
    {
        LoginFlowTracker tracker = new LoginFlowTracker();
        tracker.accept(GameState.LOGIN_SCREEN);

        LoginFlowTracker.Transition authenticator = tracker.accept(GameState.LOGIN_SCREEN_AUTHENTICATOR);
        assertFalse(authenticator.isLoginStarted());
        assertTrue(authenticator.isBackgroundVisible());
        assertFalse(authenticator.isFrameUpdatesAllowed());
        assertFalse(authenticator.shouldRestoreBackground());
    }
}
