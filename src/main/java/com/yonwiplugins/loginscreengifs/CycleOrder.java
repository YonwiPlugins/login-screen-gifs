package com.yonwiplugins.loginscreengifs;

import lombok.AllArgsConstructor;

/**
 * How the next GIF is picked when a {@link CycleTrigger} fires.
 */
@AllArgsConstructor
public enum CycleOrder
{
	IN_ORDER("In order"),
	RANDOM("Random"),
	SHUFFLE("Shuffle (no repeats)");

	private final String label;

	@Override
	public String toString()
	{
		return label;
	}
}
