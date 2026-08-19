package com.yonwiplugins.loginscreengifs;

import lombok.AllArgsConstructor;

/**
 * How a decoded frame is fitted to the login screen canvas.
 */
@AllArgsConstructor
public enum ScaleMode
{
	COVER("Fill (crop edges)"),
	STRETCH("Stretch to fit"),
	CONTAIN("Fit inside (letterbox)");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
