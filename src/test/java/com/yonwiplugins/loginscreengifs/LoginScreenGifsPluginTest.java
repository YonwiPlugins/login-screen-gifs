package com.yonwiplugins.loginscreengifs;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LoginScreenGifsPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(LoginScreenGifsPlugin.class);
        RuneLite.main(args);
    }
}
