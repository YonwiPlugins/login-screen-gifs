package com.yonwiplugins.loginscreengifs;

import net.runelite.api.GameState;

final class LoginFlowTracker
{
    private boolean loginFlowActive;
    private boolean worldHopActive;

    Transition accept(GameState gameState)
    {
        if (gameState == GameState.HOPPING)
        {
            worldHopActive = true;
            loginFlowActive = false;
            return Transition.restore();
        }

        if (gameState == GameState.LOGGED_IN)
        {
            worldHopActive = false;
            loginFlowActive = false;
            return Transition.restore();
        }

        if (gameState == GameState.LOADING)
        {
            if (worldHopActive)
            {
                return Transition.restore();
            }
            return new Transition(false, loginFlowActive, loginFlowActive, false);
        }

        if (gameState == GameState.LOGIN_SCREEN
            || gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR
            || gameState == GameState.LOGGING_IN)
        {
            boolean loginStarted = !loginFlowActive;
            worldHopActive = false;
            loginFlowActive = true;
            boolean frameUpdatesAllowed = gameState != GameState.LOGIN_SCREEN_AUTHENTICATOR;
            return new Transition(loginStarted, true, frameUpdatesAllowed, false);
        }

        return new Transition(false, loginFlowActive, false, false);
    }

    boolean isLoginFlowActive()
    {
        return loginFlowActive;
    }

    boolean isWorldHopActive()
    {
        return worldHopActive;
    }

    static final class Transition
    {
        private final boolean loginStarted;
        private final boolean backgroundVisible;
        private final boolean frameUpdatesAllowed;
        private final boolean restoreBackground;

        private Transition(
            boolean loginStarted,
            boolean backgroundVisible,
            boolean frameUpdatesAllowed,
            boolean restoreBackground)
        {
            this.loginStarted = loginStarted;
            this.backgroundVisible = backgroundVisible;
            this.frameUpdatesAllowed = frameUpdatesAllowed;
            this.restoreBackground = restoreBackground;
        }

        static Transition restore()
        {
            return new Transition(false, false, false, true);
        }

        boolean isLoginStarted()
        {
            return loginStarted;
        }

        boolean isBackgroundVisible()
        {
            return backgroundVisible;
        }

        boolean isFrameUpdatesAllowed()
        {
            return frameUpdatesAllowed;
        }

        boolean shouldRestoreBackground()
        {
            return restoreBackground;
        }
    }
}
