package com.yonwiplugins.loginscreengifs;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LoginScreenGifsPanel extends PluginPanel
{
    private static final Logger log = LoggerFactory.getLogger(LoginScreenGifsPanel.class);

    private final LoginScreenGifsPlugin plugin;
    private final ConfigManager configManager;
    private final File libraryDirectory;
    private final JLabel activeGif = new JLabel("No GIF selected");
    private final JLabel gifCount = new JLabel("0 GIFs in library");
    private final JLabel importStatus = new JLabel(" ");
    private final DefaultListModel<File> gifModel = new DefaultListModel<>();
    private final JList<File> gifList = new JList<>(gifModel);
    private final JComboBox<CycleTrigger> cycleTrigger = new JComboBox<>(CycleTrigger.values());
    private final JComboBox<CycleOrder> cycleOrder = new JComboBox<>(CycleOrder.values());
    private final JSpinner rotationInterval = new JSpinner(new SpinnerNumberModel(60, 5, 3600, 5));
    private final JComboBox<ScaleMode> scaleMode = new JComboBox<>(ScaleMode.values());
    private final JButton addFiles = new JButton("Add GIFs");
    private final JButton addFolder = new JButton("Add folder");
    private final JButton previous = new JButton("Previous");
    private final JButton next = new JButton("Next");
    private boolean updating;

    LoginScreenGifsPanel(
        LoginScreenGifsPlugin plugin,
        ConfigManager configManager,
        LoginScreenGifsConfig config,
        File libraryDirectory)
    {
        this.plugin = plugin;
        this.configManager = configManager;
        this.libraryDirectory = libraryDirectory;

        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel title = new JLabel("Login Screen GIFs", SwingConstants.LEFT);
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setAlignmentX(LEFT_ALIGNMENT);
        content.add(title);
        content.add(Box.createVerticalStrut(12));

        content.add(sectionHeading("Add GIFs"));
        content.add(Box.createVerticalStrut(5));
        JPanel addControls = transparentGrid(1, 2);
        addFiles.setToolTipText("Choose one or more GIF files");
        addFolder.setToolTipText("Import every GIF inside a folder and its subfolders");
        addFiles.addActionListener(event -> chooseFiles());
        addFolder.addActionListener(event -> chooseFolder());
        addControls.add(addFiles);
        addControls.add(addFolder);
        content.add(addControls);
        content.add(Box.createVerticalStrut(6));

        JLabel dropHint = new JLabel("Drop GIFs or folders here", SwingConstants.CENTER);
        dropHint.setAlignmentX(LEFT_ALIGNMENT);
        dropHint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        dropHint.setBorder(BorderFactory.createDashedBorder(ColorScheme.MEDIUM_GRAY_COLOR));
        dropHint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        content.add(dropHint);
        content.add(Box.createVerticalStrut(6));

        JPanel folderControls = transparentGrid(1, 2);
        JButton refresh = new JButton("Refresh");
        JButton openFolder = new JButton("Open library");
        refresh.addActionListener(event -> plugin.refreshFromPanel());
        openFolder.setEnabled(Desktop.isDesktopSupported());
        openFolder.addActionListener(event -> openLibrary());
        folderControls.add(refresh);
        folderControls.add(openFolder);
        content.add(folderControls);

        importStatus.setAlignmentX(LEFT_ALIGNMENT);
        importStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        content.add(importStatus);
        content.add(Box.createVerticalStrut(8));

        content.add(sectionHeading("GIF library"));
        content.add(Box.createVerticalStrut(5));
        activeGif.setAlignmentX(LEFT_ALIGNMENT);
        activeGif.setToolTipText("The GIF currently selected for the login screen");
        gifCount.setAlignmentX(LEFT_ALIGNMENT);
        gifCount.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        content.add(activeGif);
        content.add(gifCount);
        content.add(Box.createVerticalStrut(6));

        gifList.setCellRenderer((list, value, index, isSelected, cellHasFocus) ->
        {
            DefaultListCellRenderer renderer = new DefaultListCellRenderer();
            return renderer.getListCellRendererComponent(
                list,
                value == null ? "" : value.getName(),
                index,
                isSelected,
                cellHasFocus);
        });
        gifList.setVisibleRowCount(7);
        gifList.setToolTipText("Select a GIF to use immediately");
        gifList.addListSelectionListener(event ->
        {
            if (!event.getValueIsAdjusting() && !updating)
            {
                File selected = gifList.getSelectedValue();
                if (selected != null)
                {
                    plugin.selectFromPanel(selected);
                }
            }
        });
        JScrollPane gifScroll = new JScrollPane(gifList);
        gifScroll.setPreferredSize(new Dimension(0, 160));
        gifScroll.setAlignmentX(LEFT_ALIGNMENT);
        content.add(gifScroll);
        content.add(Box.createVerticalStrut(6));

        JPanel navigation = transparentGrid(1, 2);
        previous.addActionListener(event -> plugin.previousFromPanel());
        next.addActionListener(event -> plugin.nextFromPanel());
        navigation.add(previous);
        navigation.add(next);
        content.add(navigation);
        content.add(Box.createVerticalStrut(12));

        content.add(sectionHeading("Cycling"));
        content.add(Box.createVerticalStrut(5));
        content.add(setting("Change", cycleTrigger));
        content.add(Box.createVerticalStrut(6));
        content.add(setting("Order", cycleOrder));
        content.add(Box.createVerticalStrut(6));
        content.add(setting("Timer (seconds)", rotationInterval));
        content.add(Box.createVerticalStrut(6));
        content.add(setting("Sizing", scaleMode));

        cycleTrigger.addActionListener(event ->
        {
            if (!updating)
            {
                CycleTrigger selected = (CycleTrigger) cycleTrigger.getSelectedItem();
                configManager.setConfiguration(LoginScreenGifsConfig.GROUP, LoginScreenGifsConfig.KEY_CYCLE_TRIGGER, selected);
                updateIntervalEnabled();
            }
        });
        cycleOrder.addActionListener(event ->
        {
            if (!updating)
            {
                configManager.setConfiguration(
                    LoginScreenGifsConfig.GROUP,
                    LoginScreenGifsConfig.KEY_CYCLE_ORDER,
                    cycleOrder.getSelectedItem());
            }
        });
        rotationInterval.addChangeListener(event ->
        {
            if (!updating)
            {
                configManager.setConfiguration(
                    LoginScreenGifsConfig.GROUP,
                    LoginScreenGifsConfig.KEY_CYCLE_INTERVAL,
                    rotationInterval.getValue());
            }
        });
        scaleMode.addActionListener(event ->
        {
            if (!updating)
            {
                configManager.setConfiguration(
                    LoginScreenGifsConfig.GROUP,
                    LoginScreenGifsConfig.KEY_SCALE_MODE,
                    scaleMode.getSelectedItem());
            }
        });

        TransferHandler dropHandler = createDropHandler();
        setTransferHandler(dropHandler);
        content.setTransferHandler(dropHandler);
        dropHint.setTransferHandler(dropHandler);
        gifList.setTransferHandler(dropHandler);
        gifScroll.setTransferHandler(dropHandler);

        add(content, BorderLayout.NORTH);
        syncConfig(config);
    }

    void updateFiles(List<File> files, File selected)
    {
        SwingUtilities.invokeLater(() ->
        {
            updating = true;
            try
            {
                gifModel.clear();
                for (File file : files)
                {
                    gifModel.addElement(file);
                }
                if (selected == null)
                {
                    gifList.clearSelection();
                    activeGif.setText("No GIF selected");
                }
                else
                {
                    gifList.setSelectedValue(selected, true);
                    activeGif.setText("Active: " + selected.getName());
                }
                gifCount.setText(files.size() + (files.size() == 1 ? " GIF in library" : " GIFs in library"));
                previous.setEnabled(files.size() > 1);
                next.setEnabled(files.size() > 1);
            }
            finally
            {
                updating = false;
            }
        });
    }

    void syncConfig(LoginScreenGifsConfig config)
    {
        SwingUtilities.invokeLater(() ->
        {
            updating = true;
            try
            {
                cycleTrigger.setSelectedItem(config.cycleTrigger());
                cycleOrder.setSelectedItem(config.cycleOrder());
                rotationInterval.setValue(config.cycleIntervalSeconds());
                scaleMode.setSelectedItem(config.scaleMode());
                updateIntervalEnabled();
            }
            finally
            {
                updating = false;
            }
        });
    }

    void showImportResult(String message, boolean error)
    {
        SwingUtilities.invokeLater(() ->
        {
            addFiles.setEnabled(true);
            addFolder.setEnabled(true);
            importStatus.setForeground(error ? ColorScheme.PROGRESS_ERROR_COLOR : ColorScheme.LIGHT_GRAY_COLOR);
            importStatus.setText(message == null || message.isEmpty() ? " " : message);
        });
    }

    private void chooseFiles()
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Add GIFs to Login Screen GIFs");
        chooser.setMultiSelectionEnabled(true);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("GIF images (*.gif)", "gif"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
        {
            return;
        }
        File[] selected = chooser.getSelectedFiles();
        if (selected.length == 0 && chooser.getSelectedFile() != null)
        {
            selected = new File[] {chooser.getSelectedFile()};
        }
        importEntries(Arrays.asList(selected));
    }

    private void chooseFolder()
    {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Add a folder of GIFs");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION)
        {
            return;
        }
        File[] selected = chooser.getSelectedFiles();
        if (selected.length == 0 && chooser.getSelectedFile() != null)
        {
            selected = new File[] {chooser.getSelectedFile()};
        }
        importEntries(Arrays.asList(selected));
    }

    private void importEntries(List<File> entries)
    {
        if (entries.isEmpty())
        {
            return;
        }
        addFiles.setEnabled(false);
        addFolder.setEnabled(false);
        importStatus.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        importStatus.setText("Adding GIFs...");
        plugin.importFromPanel(entries);
    }

    private TransferHandler createDropHandler()
    {
        return new TransferHandler()
        {
            @Override
            public boolean canImport(TransferSupport support)
            {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support)
            {
                if (!canImport(support))
                {
                    return false;
                }
                try
                {
                    List<File> entries = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                    importEntries(entries);
                    return true;
                }
                catch (UnsupportedFlavorException | IOException ex)
                {
                    log.warn("Unable to read dropped GIF files", ex);
                    showImportResult("Could not read dropped files", true);
                    return false;
                }
            }
        };
    }

    private void openLibrary()
    {
        try
        {
            if (!libraryDirectory.exists() && !libraryDirectory.mkdirs())
            {
                throw new IOException("Unable to create " + libraryDirectory);
            }
            Desktop.getDesktop().open(libraryDirectory);
        }
        catch (IOException | UnsupportedOperationException ex)
        {
            log.warn("Unable to open GIF library {}", libraryDirectory, ex);
            showImportResult("Could not open the library folder", true);
        }
    }

    private void updateIntervalEnabled()
    {
        rotationInterval.setEnabled(cycleTrigger.getSelectedItem() == CycleTrigger.TIMER);
    }

    private static JLabel sectionHeading(String text)
    {
        JLabel label = new JLabel(text);
        label.setFont(FontManager.getRunescapeBoldFont());
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JComponent setting(String text, JComponent control)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setOpaque(false);
        row.add(new JLabel(text), BorderLayout.WEST);
        row.add(control, BorderLayout.CENTER);
        return row;
    }

    private static JPanel transparentGrid(int rows, int columns)
    {
        JPanel panel = new JPanel(new GridLayout(rows, columns, 6, 0));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setOpaque(false);
        return panel;
    }
}
