# Login Screen GIFs

A RuneLite plugin by YonwiPlugins for putting your own animated GIFs behind the Old School RuneScape login screen.

## Improvements

- Any filename works. Your GIF no longer needs to be called `login.gif`.
- Add one GIF, many GIFs, or a whole folder straight from the RuneLite side panel.
- Drag and drop GIFs or folders onto the panel, or open the library folder and manage it yourself.
- Cycle through up to three trillion GIFs, because one is simply not enough.
- Choose one GIF, move through the library in order, or pick randomly.
- Change GIF on RuneLite start, on each real login, after a full GIF loop, or on a configurable timer.
- The old Fallback FPS checkbox is gone. GIFs normally say how long each frame should stay on screen, and if one does not, the plugin picks a sensible backup automatically.
- World hopping no longer restarts the login background during the hop.

## Side panel

Open `Login Screen GIFs` in the RuneLite sidebar to:

- Add one or several GIF files
- Import every GIF inside a folder and its subfolders
- Drag and drop files or folders
- Select a GIF directly
- Move to the previous or next GIF
- Refresh or open the managed library
- Configure cycling, order, timing, and screen sizing

The managed library lives at:

```text
%USERPROFILE%\.runelite\login-screen-gifs\
```

The side panel is the normal way to add and change GIFs; the folder is there when you want it.

## Cycling

Choose when to change GIF:

- Never (manual only)
- Once per RuneLite start
- Every real login screen
- Every full GIF loop
- On a configurable timer

Then choose how the next GIF is picked:

- In order
- Random
- Shuffle with no repeats

Previous and Next always work, whichever mode is selected.

Sizing can fill and crop, stretch to fit, or fit inside with letterboxing.

## Safety and performance

GIF decoding happens on a background thread. Only the active GIF is decoded, and the player keeps at most two prefetched frames plus the visible frame in memory. Source frames over the 32 MiB decoded-image limit are rejected.

Files are checked as real GIFs before import. Matching filenames are kept by adding `(2)`, `(3)`, and so on instead of overwriting an existing file.

The plugin does not register a keyboard listener or read login details.
