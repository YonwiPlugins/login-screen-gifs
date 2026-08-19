package com.yonwiplugins.loginscreengifs;

import lombok.AllArgsConstructor;

/**
 * When the plugin should move on to the next GIF in the library.
 * Manual cycling from the side panel works no matter which of these is chosen.
 */
@AllArgsConstructor
public enum CycleTrigger
{
	OFF("Never (manual only)"),
	SESSION("Once per client start"),
	LOGIN_SCREEN("Every login screen"),
	LOOP("Every full GIF loop"),
	TIMER("On a timer");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
