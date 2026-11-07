/*
 * zaptun: capture all traffic of Android devices in ZAP.
 *
 * Copyright 2026 David Araújo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.davidjosearaujo.zaptun.zap;

import static dev.davidjosearaujo.zaptun.zap.I18n.msg;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import org.parosproxy.paros.control.Control;
import org.zaproxy.zap.extension.help.ExtensionHelp;

/** Lists adb devices and intercepts or releases the selected one. */
class DevicesDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient DeviceManager manager;
    private final transient ZaptunParam param;
    private final transient ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "zaptun-devices");
                        t.setDaemon(true);
                        return t;
                    });

    private final DeviceTableModel model = new DeviceTableModel();
    private final JTable table = new JTable(model);
    private final JTextField apps = new JTextField();
    private final JTextArea log = new JTextArea();
    private final transient List<JButton> actions = new ArrayList<>();

    DevicesDialog(Frame owner, DeviceManager manager, ZaptunParam param) {
        super(owner, msg("dialog.title"), false);
        this.manager = manager;
        this.param = param;

        apps.setText(param.getApps());
        apps.setToolTipText(msg("dialog.apps.tooltip"));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setPreferredScrollableViewportSize(new Dimension(640, 120));

        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, log.getFont().getSize()));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        addRow(form, 0, msg("dialog.apps"), apps, button("dialog.choose", this::chooseApps, true));

        JButton help = new JButton(ExtensionHelp.getHelpIcon());
        help.setToolTipText(msg("dialog.help"));
        help.addActionListener(e -> ExtensionHelp.showHelp(ExtensionZaptun.HELP_INDEX));
        ExtensionHelp.enableHelpKey(getRootPane(), ExtensionZaptun.HELP_INDEX);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(help);
        buttons.add(button("dialog.options", this::openOptions, false));
        buttons.add(button("dialog.refresh", this::refresh, false));
        buttons.add(button("dialog.stop", this::stopSelected, true));
        JButton intercept = button("dialog.intercept", this::interceptSelected, true);
        buttons.add(intercept);
        getRootPane().setDefaultButton(intercept);

        JPanel top = new JPanel(new BorderLayout());
        top.add(new JScrollPane(table), BorderLayout.CENTER);
        top.add(form, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, new JScrollPane(log));
        split.setResizeWeight(0.5);

        getContentPane().add(split, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        setSize(760, 520);
        setLocationRelativeTo(owner);
    }

    void showAndRefresh() {
        setVisible(true);
        toFront();
        refresh();
    }

    @Override
    public void dispose() {
        worker.shutdownNow();
        super.dispose();
    }

    private void refresh() {
        saveSettings();
        run(
                () -> {
                    List<DeviceManager.Device> devices = manager.devices();
                    SwingUtilities.invokeLater(
                            () -> {
                                String selected = selectedSerial();
                                model.set(devices);
                                selectSerial(selected != null ? selected : firstReady(devices));
                                if (devices.isEmpty()) {
                                    append(msg("dialog.nodevices"));
                                }
                            });
                },
                false);
    }

    private void interceptSelected() {
        String serial = requireSelection();
        if (serial == null) {
            return;
        }
        saveSettings();
        List<String> chosen = ExtensionZaptun.parseApps(apps.getText());
        run(() -> manager.intercept(serial, chosen, this::appendLater), true);
    }

    private void stopSelected() {
        String serial = requireSelection();
        if (serial != null) {
            run(() -> manager.stop(serial, this::appendLater), true);
        }
    }

    private void chooseApps() {
        String serial = requireSelection();
        if (serial == null) {
            return;
        }
        run(
                () -> {
                    List<String> packages = manager.userPackages(serial);
                    SwingUtilities.invokeLater(() -> showAppPicker(packages));
                },
                false);
    }

    private void showAppPicker(List<String> packages) {
        JList<String> list = new JList<>(packages.toArray(new String[0]));
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        List<String> current = ExtensionZaptun.parseApps(apps.getText());
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < packages.size(); i++) {
            if (current.contains(packages.get(i))) {
                selected.add(i);
            }
        }
        list.setSelectedIndices(selected.stream().mapToInt(Integer::intValue).toArray());
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(420, 300));
        int answer =
                JOptionPane.showConfirmDialog(
                        this,
                        new Object[] {msg("dialog.choose.hint"), scroll},
                        msg("dialog.choose.title"),
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            apps.setText(String.join(", ", list.getSelectedValuesList()));
            saveSettings();
        }
    }

    /** Opens Tools > Options at zaptun's panel (SDK and adb paths), then refreshes the devices. */
    private void openOptions() {
        Control.getSingleton().getMenuToolsControl().options(msg("options.title"));
        refresh();
    }

    private void saveSettings() {
        param.setApps(apps.getText());
    }

    /** Runs a device operation off the EDT, with the buttons disabled while it runs. */
    private void run(Task task, boolean refreshAfter) {
        setBusy(true);
        worker.execute(
                () -> {
                    try {
                        task.run();
                    } catch (Exception e) {
                        appendLater(msg("dialog.error") + " " + e.getMessage());
                    } finally {
                        SwingUtilities.invokeLater(
                                () -> {
                                    setBusy(false);
                                    if (refreshAfter) {
                                        refresh();
                                    }
                                });
                    }
                });
    }

    private void setBusy(boolean busy) {
        actions.forEach(b -> b.setEnabled(!busy));
    }

    private String requireSelection() {
        String serial = selectedSerial();
        if (serial == null) {
            append(msg("dialog.select"));
        }
        return serial;
    }

    private String selectedSerial() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.get(table.convertRowIndexToModel(row)).serial();
    }

    private void selectSerial(String serial) {
        for (int i = 0; i < model.getRowCount(); i++) {
            if (model.get(i).serial().equals(serial)) {
                int row = table.convertRowIndexToView(i);
                table.setRowSelectionInterval(row, row);
                return;
            }
        }
    }

    private static String firstReady(List<DeviceManager.Device> devices) {
        return devices.stream()
                .filter(DeviceManager.Device::ready)
                .map(DeviceManager.Device::serial)
                .findFirst()
                .orElse(null);
    }

    private void appendLater(String line) {
        SwingUtilities.invokeLater(() -> append(line));
    }

    private void append(String line) {
        log.append(line + "\n");
        log.setCaretPosition(log.getDocument().getLength());
    }

    private JButton button(String key, Runnable action, boolean disableWhileBusy) {
        JButton b = new JButton(msg(key));
        b.addActionListener(e -> action.run());
        if (disableWhileBusy) {
            actions.add(b);
        }
        return b;
    }

    private static void addRow(
            JPanel panel, int row, String label, JTextField field, JButton button) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(2, 2, 2, 2);
        c.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel(label), c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        panel.add(field, c);
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        panel.add(button, c);
    }

    @FunctionalInterface
    private interface Task {
        void run() throws Exception;
    }

    private static class DeviceTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private static final String[] COLUMNS = {"serial", "model", "android", "root", "status"};
        private transient List<DeviceManager.Device> devices = List.of();

        void set(List<DeviceManager.Device> devices) {
            this.devices = devices;
            fireTableDataChanged();
        }

        DeviceManager.Device get(int row) {
            return devices.get(row);
        }

        @Override
        public int getRowCount() {
            return devices.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return msg("dialog.column." + COLUMNS[column]);
        }

        @Override
        public Object getValueAt(int row, int column) {
            DeviceManager.Device d = devices.get(row);
            return switch (column) {
                case 0 -> d.serial();
                case 1 -> d.model();
                case 2 -> d.ready() ? d.androidVersion() + " (API " + d.sdk() + ")" : "";
                case 3 -> d.ready() ? msg("dialog.root." + d.root().name().toLowerCase()) : "";
                default -> status(d);
            };
        }

        private static String status(DeviceManager.Device d) {
            if (!d.ready()) {
                return d.state(); // unauthorized, offline, ...
            }
            if (d.intercepting()) {
                return msg("dialog.status.intercepting");
            }
            return msg(d.installed() ? "dialog.status.idle" : "dialog.status.notinstalled");
        }
    }
}
