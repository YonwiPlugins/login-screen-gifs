package com.yonwiplugins.loginscreengifs;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(LoginScreenGifsConfig.GROUP)
public interface LoginScreenGifsConfig extends Config
{
    String GROUP = "loginScreenGifs";
    String KEY_CYCLE_TRIGGER = "cycleTrigger";
    String KEY_CYCLE_ORDER = "cycleOrder";
    String KEY_CYCLE_INTERVAL = "cycleIntervalSeconds";
    String KEY_SCALE_MODE = "scaleMode";
    String KEY_SELECTED_GIF = "selectedGif";

    @ConfigItem(
        position = 0,
        keyName = KEY_CYCLE_TRIGGER,
        name = "Cycle when",
        description = "Choose when the plugin moves to another GIF"
    )
    default CycleTrigger cycleTrigger()
    {
        return CycleTrigger.OFF;
    }

    @ConfigItem(
        position = 1,
        keyName = KEY_CYCLE_ORDER,
        name = "Cycle order",
        description = "Choose how the next GIF is picked"
    )
    default CycleOrder cycleOrder()
    {
        return CycleOrder.IN_ORDER;
    }

    @Range(min = 5, max = 3600)
    @ConfigItem(
        position = 2,
        keyName = KEY_CYCLE_INTERVAL,
        name = "Timer interval",
        description = "Seconds between changes when cycling on a timer"
    )
    default int cycleIntervalSeconds()
    {
        return 60;
    }

    @ConfigItem(
        position = 3,
        keyName = KEY_SCALE_MODE,
        name = "Sizing",
        description = "Choose how GIF frames fit the login screen"
    )
    default ScaleMode scaleMode()
    {
        return ScaleMode.COVER;
    }
}
