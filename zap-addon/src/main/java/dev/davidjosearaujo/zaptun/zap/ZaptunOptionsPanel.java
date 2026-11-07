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

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import org.parosproxy.paros.model.OptionsParam;
import org.parosproxy.paros.view.AbstractParamPanel;

/**
 * Tools > Options > Android (zaptun): where the Android SDK and adb are. Empty fields are filled in
 * from ANDROID_HOME, ANDROID_SDK_ROOT, the default SDK folders or PATH, with a note saying where
 * each value came from.
 */
class ZaptunOptionsPanel extends AbstractParamPanel {

    private static final long serialVersionUID = 1L;

    private final transient AdbLocator locator;
    private final JTextField sdkPath = new JTextField();
    private final JTextField adbPath = new JTextField();
    private final JLabel sdkSource = note();
    private final JLabel adbSource = note();
    private final JLabel testResult = note();

    ZaptunOptionsPanel(AdbLocator locator) {
        this.locator = locator;
        setName(msg("options.title"));
        setLayout(new GridBagLayout());
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        int row = 0;
        add(wrapping(msg("options.intro")), span(row++));
        addField(row, msg("options.sdk"), sdkPath, browse(sdkPath, JFileChooser.DIRECTORIES_ONLY));
        add(sdkSource, below(++row));
        row++;
        addField(row, msg("options.adb"), adbPath, browse(adbPath, JFileChooser.FILES_ONLY));
        add(adbSource, below(++row));
        row++;

        JPanel buttons = new JPanel();
        JButton detect = new JButton(msg("options.detect"));
        detect.setToolTipText(msg("options.detect.tooltip"));
        detect.addActionListener(e -> detect(true));
        JButton test = new JButton(msg("options.test"));
        test.addActionListener(e -> testAdb());
        buttons.add(detect);
        buttons.add(test);
        GridBagConstraints c = span(row++);
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.WEST;
        add(buttons, c);
        add(testResult, span(row++));

        // Push everything to the top.
        GridBagConstraints filler = span(row);
        filler.weighty = 1;
        add(new JPanel(), filler);

        // When the SDK folder changes, keep the adb path in step if it was the SDK's adb.
        sdkPath.addActionListener(e -> followSdk());
    }

    @Override
    public void initParam(Object obj) {
        ZaptunParam param = ((OptionsParam) obj).getParamSet(ZaptunParam.class);
        sdkPath.setText(param.getSdkPath());
        adbPath.setText(param.getAdbPath());
        sdkSource.setText(" ");
        adbSource.setText(" ");
        testResult.setText(" ");
        detect(false);
    }

    @Override
    public void validateParam(Object obj) throws Exception {
        String adb = adbPath.getText().trim();
        if (!adb.isEmpty() && !Files.isExecutable(Paths.get(adb))) {
            adbPath.requestFocusInWindow();
            throw new IllegalArgumentException(msg("options.error.adb", adb));
        }
        String sdk = sdkPath.getText().trim();
        if (!sdk.isEmpty() && !Files.isDirectory(Paths.get(sdk))) {
            sdkPath.requestFocusInWindow();
            throw new IllegalArgumentException(msg("options.error.sdk", sdk));
        }
    }

    @Override
    public String getHelpIndex() {
        return ExtensionZaptun.HELP_INDEX + ".options";
    }

    @Override
    public void saveParam(Object obj) throws Exception {
        ZaptunParam param = ((OptionsParam) obj).getParamSet(ZaptunParam.class);
        param.setSdkPath(sdkPath.getText());
        param.setAdbPath(adbPath.getText());
    }

    /**
     * Fills in the fields from the environment: only empty ones, or all of them when [overwrite].
     */
    private void detect(boolean overwrite) {
        Optional<AdbLocator.Found> sdk = locator.sdk();
        if (overwrite || sdkPath.getText().isBlank()) {
            sdkPath.setText(sdk.map(f -> f.path().toString()).orElse(""));
            sdkSource.setText(
                    sdk.map(f -> msg("options.source", f.source()))
                            .orElse(msg("options.notfound.sdk")));
        }
        Optional<AdbLocator.Found> adb = locator.adb();
        if (overwrite || adbPath.getText().isBlank()) {
            adbPath.setText(adb.map(f -> f.path().toString()).orElse(""));
            adbSource.setText(
                    adb.map(f -> msg("options.source", f.source()))
                            .orElse(msg("options.notfound.adb")));
        }
    }

    private void followSdk() {
        String sdk = sdkPath.getText().trim();
        if (sdk.isEmpty()) {
            return;
        }
        Path adb = locator.adbIn(Paths.get(sdk));
        if (Files.isExecutable(adb)) {
            adbPath.setText(adb.toString());
            adbSource.setText(msg("options.source.sdk"));
        }
    }

    /** Runs "adb version" with what's in the fields, without saving them. */
    private void testAdb() {
        Adb adb = new Adb(() -> new Adb.Config(adbPath.getText(), sdkPath.getText()), locator);
        testResult.setText(msg("options.test.running"));
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                try {
                    Adb.Result r = adb.run(null, Duration.ofSeconds(10), List.of("version"));
                    String first = r.text().lines().findFirst().orElse("");
                    return r.ok()
                            ? msg("options.test.ok", adb.executable(), first)
                            : msg("options.test.failed", r.text());
                } catch (Exception e) {
                    return msg("options.test.failed", e.getMessage());
                }
            }

            @Override
            protected void done() {
                try {
                    testResult.setText(get());
                } catch (Exception e) {
                    testResult.setText(msg("options.test.failed", e.getMessage()));
                }
            }
        }.execute();
    }

    private JButton browse(JTextField field, int mode) {
        JButton b = new JButton(msg("dialog.browse"));
        b.addActionListener(
                e -> {
                    JFileChooser chooser = new JFileChooser(field.getText());
                    chooser.setFileSelectionMode(mode);
                    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                        field.setText(chooser.getSelectedFile().getAbsolutePath());
                        if (field == sdkPath) {
                            followSdk();
                            sdkSource.setText(" ");
                        } else {
                            adbSource.setText(" ");
                        }
                    }
                });
        return b;
    }

    private void addField(int row, String label, JTextField field, JButton button) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.insets = new Insets(8, 2, 0, 2);
        c.anchor = GridBagConstraints.WEST;
        add(new JLabel(label), c);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        add(field, c);
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        add(button, c);
    }

    private static GridBagConstraints span(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        c.insets = new Insets(4, 2, 4, 2);
        return c;
    }

    /** The source note under a field, aligned with the field. */
    private static GridBagConstraints below(int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 1;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 2, 0, 2);
        return c;
    }

    /**
     * A label that wraps. Plain JLabels cut long text off with "...", and a JTextArea gets its
     * height wrong in a GridBagLayout; HTML with a fixed width does neither.
     */
    private static JLabel wrapping(String text) {
        return new JLabel("<html><body style='width: 420px'>" + text + "</body></html>");
    }

    private static JLabel note() {
        JLabel l = new JLabel(" ");
        l.setFont(l.getFont().deriveFont(l.getFont().getSize2D() - 1f));
        l.setEnabled(false); // greyed out, like a hint
        return l;
    }
}
