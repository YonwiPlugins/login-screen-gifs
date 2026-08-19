package com.yonwiplugins.loginscreengifs;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SpritePixels;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
    name = "Login Screen GIFs",
    description = "Upload and cycle animated GIFs behind the login screen",
    tags = {"login", "gif", "animated", "background", "wallpaper"}
)
public class LoginScreenGifsPlugin extends Plugin
{
    private static final Logger log = LoggerFactory.getLogger(LoginScreenGifsPlugin.class);
    private static final long FRAME_CHECK_INTERVAL_MILLIS = 16L;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ConfigManager configManager;

    @Inject
    private LoginScreenGifsConfig config;

    @Inject
    private GifLibrary library;

    @Inject
    private GifDecoder decoder;

    private final AtomicBoolean frameUpdateQueued = new AtomicBoolean();
    private LoginFlowTracker flowTracker;
    private ExecutorService libraryExecutor;
    private ScheduledExecutorService animationExecutor;
    private ScheduledFuture<?> animationTask;
    private LoginScreenGifsPanel panel;
    private NavigationButton navigationButton;
    private List<File> gifFiles = Collections.emptyList();
    private final ArrayDeque<Integer> shuffleQueue = new ArrayDeque<>();
    private File currentGif;
    private int currentGifIndex = -1;
    private SpritePixels currentSprite;
    private long nextFrameAtNanos;
    private long nextRotationAtNanos = Long.MAX_VALUE;
    private boolean decoderRunning;
    private boolean loginScreenApplied;
    private boolean backgroundVisible;
    private boolean authenticatorActive;
    private boolean sessionRotationPending;
    private boolean loginRotationPending;
    private volatile boolean running;
    private volatile boolean framePumpEnabled;

    @Override
    protected void startUp()
    {
        running = true;
        flowTracker = new LoginFlowTracker();
        sessionRotationPending = config.cycleTrigger() == CycleTrigger.SESSION;
        loginRotationPending = false;
        resetPlayback();

        libraryExecutor = Executors.newSingleThreadExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "login-screen-gifs-library");
            thread.setDaemon(true);
            return thread;
        });
        animationExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "login-screen-gifs-animator");
            thread.setDaemon(true);
            return thread;
        });

        createSidePanel();
        refreshLibrary(null, false);
        clientThread.invoke(() -> handleGameState(client.getGameState()));
        animationTask = animationExecutor.scheduleAtFixedRate(
            this::queueFrameUpdate,
            0L,
            FRAME_CHECK_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS);
    }

    @Override
    protected void shutDown()
    {
        running = false;
        framePumpEnabled = false;
        frameUpdateQueued.set(false);

        if (animationTask != null)
        {
            animationTask.cancel(false);
            animationTask = null;
        }
        if (animationExecutor != null)
        {
            animationExecutor.shutdownNow();
            animationExecutor = null;
        }
        if (libraryExecutor != null)
        {
            libraryExecutor.shutdownNow();
            libraryExecutor = null;
        }

        decoder.stop();
        decoderRunning = false;
        resetPlayback();

        NavigationButton button = navigationButton;
        navigationButton = null;
        panel = null;
        if (button != null)
        {
            SwingUtilities.invokeLater(() -> clientToolbar.removeNavigation(button));
        }

        clientThread.invoke(() ->
        {
            client.setLoginScreen(null);
            client.setShouldRenderLoginScreenFire(true);
        });
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        handleGameState(event.getGameState());
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!LoginScreenGifsConfig.GROUP.equals(event.getGroup()))
        {
            return;
        }

        LoginScreenGifsPanel currentPanel = panel;
        if (currentPanel != null)
        {
            currentPanel.syncConfig(config);
        }

        if (LoginScreenGifsConfig.KEY_SCALE_MODE.equals(event.getKey()))
        {
            restartDecoder();
        }
        if (LoginScreenGifsConfig.KEY_CYCLE_ORDER.equals(event.getKey()))
        {
            shuffleQueue.clear();
        }
        if (LoginScreenGifsConfig.KEY_CYCLE_TRIGGER.equals(event.getKey())
            || LoginScreenGifsConfig.KEY_CYCLE_ORDER.equals(event.getKey())
            || LoginScreenGifsConfig.KEY_CYCLE_INTERVAL.equals(event.getKey()))
        {
            scheduleNextRotation(System.nanoTime());
        }
    }

    void importFromPanel(List<File> entries)
    {
        ExecutorService executor = libraryExecutor;
        if (!running || executor == null)
        {
            return;
        }

        List<File> safeEntries = new ArrayList<>(entries);
        executor.submit(() ->
        {
            try
            {
                GifLibrary.ImportSummary summary = library.importEntries(safeEntries);
                List<File> files = library.listGifs();
                String preferred = summary.getImported().isEmpty()
                    ? null
                    : summary.getImported().get(summary.getImported().size() - 1).getName();
                clientThread.invokeLater(() ->
                {
                    if (!running)
                    {
                        return;
                    }
                    applyLibrary(files, preferred);
                    showImportSummary(summary);
                });
            }
            catch (IOException | RuntimeException ex)
            {
                log.warn("Unable to import GIFs", ex);
                showPanelStatus("Could not add GIFs: " + ex.getMessage(), true);
            }
        });
    }

    void refreshFromPanel()
    {
        refreshLibrary(null, true);
    }

    void selectFromPanel(File selected)
    {
        clientThread.invokeLater(() ->
        {
            int index = gifFiles.indexOf(selected);
            if (index >= 0)
            {
                selectIndex(index, true, true);
            }
        });
    }

    void previousFromPanel()
    {
        clientThread.invokeLater(() -> rotateManual(-1));
    }

    void nextFromPanel()
    {
        clientThread.invokeLater(() -> rotateManual(1));
    }

    private void createSidePanel()
    {
        SwingUtilities.invokeLater(() ->
        {
            if (!running)
            {
                return;
            }
            panel = new LoginScreenGifsPanel(this, configManager, config, library.getDirectory());
            panel.updateFiles(gifFiles, currentGif);
            navigationButton = NavigationButton.builder()
                .tooltip("Login Screen GIFs")
                .icon(createPanelIcon())
                .priority(5)
                .panel(panel)
                .build();
            clientToolbar.addNavigation(navigationButton);
        });
    }

    private void refreshLibrary(String preferredName, boolean showSuccess)
    {
        ExecutorService executor = libraryExecutor;
        if (!running || executor == null)
        {
            return;
        }
        executor.submit(() ->
        {
            try
            {
                List<File> files = library.listGifs();
                clientThread.invokeLater(() ->
                {
                    if (!running)
                    {
                        return;
                    }
                    applyLibrary(files, preferredName);
                    if (showSuccess)
                    {
                        showPanelStatus("Library refreshed", false);
                    }
                });
            }
            catch (IOException | RuntimeException ex)
            {
                log.warn("Unable to refresh GIF library {}", library.getDirectory(), ex);
                showPanelStatus("Could not refresh the library", true);
            }
        });
    }

    private void applyLibrary(List<File> discovered, String preferredName)
    {
        File previous = currentGif;
        gifFiles = discovered;
        shuffleQueue.clear();
        if (gifFiles.isEmpty())
        {
            currentGif = null;
            currentGifIndex = -1;
            stopForNoGif();
            updatePanel();
            return;
        }

        String desiredName = preferredName;
        if (desiredName == null && previous != null)
        {
            desiredName = previous.getName();
        }
        if (desiredName == null)
        {
            desiredName = configManager.getConfiguration(
                LoginScreenGifsConfig.GROUP,
                LoginScreenGifsConfig.KEY_SELECTED_GIF);
        }

        int index = GifSelection.findByName(gifFiles, desiredName);
        if (index < 0)
        {
            index = 0;
        }

        if (preferredName != null)
        {
            sessionRotationPending = false;
            loginRotationPending = false;
        }
        else if (sessionRotationPending)
        {
            index = chooseAutomaticIndex(index);
            sessionRotationPending = false;
        }
        else if (loginRotationPending)
        {
            index = chooseAutomaticIndex(index);
            loginRotationPending = false;
        }

        boolean changed = previous == null || !previous.equals(gifFiles.get(index));
        selectIndex(index, changed, false);
        if (backgroundVisible && !decoderRunning)
        {
            startDecoder();
        }
    }

    private void handleGameState(GameState gameState)
    {
        if (!running)
        {
            return;
        }

        LoginFlowTracker.Transition transition = flowTracker.accept(gameState);
        authenticatorActive = gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR;
        if (transition.shouldRestoreBackground())
        {
            stopAndRestore();
            return;
        }

        backgroundVisible = transition.isBackgroundVisible();
        if (!backgroundVisible)
        {
            framePumpEnabled = false;
            return;
        }

        if (transition.isLoginStarted())
        {
            rotateForLoginIfNeeded();
        }
        startDecoder();
        framePumpEnabled = currentGif != null
            && (transition.isFrameUpdatesAllowed() || !loginScreenApplied);
    }

    private void rotateForLoginIfNeeded()
    {
        if (config.cycleTrigger() != CycleTrigger.LOGIN_SCREEN)
        {
            loginRotationPending = false;
            return;
        }
        if (gifFiles.isEmpty())
        {
            loginRotationPending = true;
            return;
        }
        rotateAutomatic();
    }

    private void queueFrameUpdate()
    {
        if (!running || !framePumpEnabled || !frameUpdateQueued.compareAndSet(false, true))
        {
            return;
        }
        clientThread.invokeLater(() ->
        {
            try
            {
                if (running && framePumpEnabled)
                {
                    applyNextFrame();
                }
            }
            finally
            {
                frameUpdateQueued.set(false);
            }
        });
    }

    private void applyNextFrame()
    {
        if (!backgroundVisible || currentGif == null)
        {
            return;
        }

        long now = System.nanoTime();
        if (!authenticatorActive && config.cycleTrigger() == CycleTrigger.TIMER && now >= nextRotationAtNanos)
        {
            rotateAutomatic();
            now = System.nanoTime();
        }
        if (currentSprite != null && now < nextFrameAtNanos)
        {
            return;
        }

        GifDecoder.DecodedFrame frame = decoder.poll();
        if (frame == null)
        {
            return;
        }
        if (frame.isLoopBoundary())
        {
            frame.release();
            if (!authenticatorActive && config.cycleTrigger() == CycleTrigger.LOOP)
            {
                rotateAutomatic();
            }
            return;
        }
        try
        {
            currentSprite = ImageUtil.getImageSpritePixels(frame.getImage(), client);
            client.setLoginScreen(currentSprite);
            client.setShouldRenderLoginScreenFire(false);
            loginScreenApplied = true;
            nextFrameAtNanos = now + TimeUnit.MILLISECONDS.toNanos(frame.getDurationMillis());
            if (authenticatorActive)
            {
                framePumpEnabled = false;
            }
        }
        finally
        {
            frame.release();
        }
    }

    private void rotateManual(int offset)
    {
        if (gifFiles.isEmpty())
        {
            return;
        }
        int index = GifSelection.next(gifFiles.size(), currentGifIndex, offset);
        selectIndex(index, index != currentGifIndex, true);
    }

    private void rotateAutomatic()
    {
        if (gifFiles.isEmpty())
        {
            return;
        }
        int index = chooseAutomaticIndex(currentGifIndex);
        selectIndex(index, index != currentGifIndex, false);
    }

    private int chooseAutomaticIndex(int currentIndex)
    {
        if (config.cycleOrder() == CycleOrder.RANDOM)
        {
            return GifSelection.random(gifFiles.size());
        }
        if (config.cycleOrder() == CycleOrder.SHUFFLE)
        {
            if (shuffleQueue.isEmpty())
            {
                List<Integer> candidates = new ArrayList<>();
                for (int index = 0; index < gifFiles.size(); index++)
                {
                    if (gifFiles.size() == 1 || index != currentIndex)
                    {
                        candidates.add(index);
                    }
                }
                Collections.shuffle(candidates);
                shuffleQueue.addAll(candidates);
            }
            return shuffleQueue.isEmpty() ? currentIndex : shuffleQueue.removeFirst();
        }
        return GifSelection.next(gifFiles.size(), currentIndex, 1);
    }

    private void selectIndex(int index, boolean restart, boolean resetShuffle)
    {
        if (index < 0 || index >= gifFiles.size())
        {
            return;
        }
        currentGifIndex = index;
        currentGif = gifFiles.get(index);
        if (resetShuffle)
        {
            shuffleQueue.clear();
        }
        configManager.setConfiguration(
            LoginScreenGifsConfig.GROUP,
            LoginScreenGifsConfig.KEY_SELECTED_GIF,
            currentGif.getName());
        updatePanel();
        scheduleNextRotation(System.nanoTime());
        if (restart)
        {
            restartDecoder();
        }
    }

    private void scheduleNextRotation(long now)
    {
        if (config.cycleTrigger() == CycleTrigger.TIMER && gifFiles.size() > 1)
        {
            long seconds = Math.max(5, config.cycleIntervalSeconds());
            nextRotationAtNanos = now + TimeUnit.SECONDS.toNanos(seconds);
        }
        else
        {
            nextRotationAtNanos = Long.MAX_VALUE;
        }
    }

    private void startDecoder()
    {
        if (!decoderRunning && backgroundVisible && currentGif != null)
        {
            decoder.start(currentGif);
            decoderRunning = true;
        }
    }

    private void restartDecoder()
    {
        if (decoderRunning)
        {
            decoder.stop();
            decoderRunning = false;
        }
        currentSprite = null;
        nextFrameAtNanos = 0L;
        if (backgroundVisible && currentGif != null)
        {
            startDecoder();
            framePumpEnabled = !authenticatorActive || !loginScreenApplied;
        }
    }

    private void stopAndRestore()
    {
        backgroundVisible = false;
        authenticatorActive = false;
        framePumpEnabled = false;
        if (decoderRunning)
        {
            decoder.stop();
            decoderRunning = false;
        }
        if (loginScreenApplied)
        {
            client.setLoginScreen(null);
            client.setShouldRenderLoginScreenFire(true);
        }
        resetPlayback();
    }

    private void stopForNoGif()
    {
        framePumpEnabled = false;
        if (decoderRunning)
        {
            decoder.stop();
            decoderRunning = false;
        }
        if (loginScreenApplied)
        {
            client.setLoginScreen(null);
            client.setShouldRenderLoginScreenFire(true);
        }
        resetPlayback();
    }

    private void resetPlayback()
    {
        currentSprite = null;
        nextFrameAtNanos = 0L;
        nextRotationAtNanos = Long.MAX_VALUE;
        loginScreenApplied = false;
    }

    private void updatePanel()
    {
        LoginScreenGifsPanel currentPanel = panel;
        if (currentPanel != null)
        {
            currentPanel.updateFiles(gifFiles, currentGif);
        }
    }

    private void showImportSummary(GifLibrary.ImportSummary summary)
    {
        int added = summary.getImported().size();
        int skipped = summary.getErrors().size();
        if (summary.getEmptyFolders() > 0)
        {
            skipped += summary.getEmptyFolders();
        }

        if (added == 0)
        {
            showPanelStatus(skipped == 0 ? "No GIFs selected" : "No GIFs added; skipped " + skipped, true);
        }
        else if (skipped > 0)
        {
            showPanelStatus("Added " + added + "; skipped " + skipped, true);
        }
        else
        {
            showPanelStatus("Added " + added + (added == 1 ? " GIF" : " GIFs"), false);
        }

        for (String error : summary.getErrors())
        {
            log.warn("Skipped GIF import: {}", error);
        }
    }

    private void showPanelStatus(String message, boolean error)
    {
        LoginScreenGifsPanel currentPanel = panel;
        if (currentPanel != null)
        {
            currentPanel.showImportResult(message, error);
        }
    }

    private static BufferedImage createPanelIcon()
    {
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(38, 31, 22));
            graphics.fillRoundRect(0, 1, 16, 14, 4, 4);
            graphics.setColor(new Color(214, 166, 64));
            graphics.drawRoundRect(1, 2, 13, 11, 3, 3);
            graphics.fillOval(4, 5, 3, 3);
            graphics.drawLine(3, 11, 7, 8);
            graphics.drawLine(7, 8, 12, 12);
        }
        finally
        {
            graphics.dispose();
        }
        return icon;
    }

    @Provides
    LoginScreenGifsConfig provideConfig(ConfigManager manager)
    {
        return manager.getConfig(LoginScreenGifsConfig.class);
    }
}
