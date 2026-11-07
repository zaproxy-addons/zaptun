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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import javax.imageio.ImageIO;
import javax.swing.JLabel;
import javax.swing.JTextField;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.model.OptionsParam;
import org.zaproxy.zap.utils.I18N;
import org.zaproxy.zap.utils.ZapXmlConfiguration;

public class ZaptunOptionsPanelTest {

    /**
     * ZAP's home for these tests. Loading OptionsParam sets ZAP's Constant up, which by default
     * uses the real ~/.ZAP: it could change the user's own ZAP config, and while ZAP is running it
     * finds that home locked and exits the test JVM. Constant is set up once per JVM, so this lives
     * for the whole class.
     */
    @ClassRule public static TemporaryFolder zapHome = new TemporaryFolder();

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void useATemporaryZapHome() {
        Constant.setZapHome(zapHome.getRoot().getAbsolutePath());
    }

    private Path sdk;
    private ZaptunParam param;
    private OptionsParam options;

    @Before
    public void setUp() throws IOException {
        System.setProperty("java.awt.headless", "true");
        // Swing needs fonts to lay text out; machines without any (bare CI images) skip these
        // tests.
        try {
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        } catch (Throwable e) {
            Assume.assumeNoException("no fonts available", e);
        }
        Constant.messages = new I18N(Locale.ENGLISH);
        Constant.messages.addMessageBundle(
                "zaptun",
                ResourceBundle.getBundle("dev.davidjosearaujo.zaptun.zap.resources.Messages"));

        sdk = tmp.getRoot().toPath().resolve("sdk");
        Path tools = Files.createDirectories(sdk.resolve("platform-tools"));
        Path adb = tools.resolve("adb");
        Files.writeString(adb, "#!/bin/sh\n");
        adb.toFile().setExecutable(true);

        param = new ZaptunParam();
        param.load(new ZapXmlConfiguration());
        options = new OptionsParam();
        options.load(new ZapXmlConfiguration());
        options.addParamSet(param);
    }

    private ZaptunOptionsPanel panel() {
        return new ZaptunOptionsPanel(
                new AdbLocator(Map.of("ANDROID_HOME", sdk.toString())::get, "/nonexistent", false));
    }

    @Test
    public void fillsEmptyFieldsFromTheEnvironment() throws Exception {
        ZaptunOptionsPanel p = panel();
        p.initParam(options);

        List<JTextField> fields = find(p, JTextField.class);
        assertEquals(sdk.toString(), fields.get(0).getText());
        assertEquals(sdk.resolve("platform-tools/adb").toString(), fields.get(1).getText());
        assertTrue(texts(p).contains("Found via ANDROID_HOME"));

        p.saveParam(options);
        assertEquals(sdk.toString(), param.getSdkPath());

        render(p, tmp.getRoot().toPath().resolve("zaptun-options.png").toFile());
    }

    @Test
    public void keepsSavedValues() throws Exception {
        param.setSdkPath("/my/sdk");
        param.setAdbPath("/my/adb");
        ZaptunOptionsPanel p = panel();
        p.initParam(options);

        List<JTextField> fields = find(p, JTextField.class);
        assertEquals("/my/sdk", fields.get(0).getText());
        assertEquals("/my/adb", fields.get(1).getText());
    }

    @Test
    public void rejectsMissingAdb() {
        param.setAdbPath("/no/such/adb");
        ZaptunOptionsPanel p = panel();
        p.initParam(options);
        try {
            p.validateParam(options);
            fail("expected an error");
        } catch (Exception e) {
            assertEquals("adb isn't an executable file: /no/such/adb", e.getMessage());
        }
    }

    private static List<String> texts(Container c) {
        return find(c, JLabel.class).stream().map(JLabel::getText).toList();
    }

    private static <T> List<T> find(Container c, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : c.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container sub) {
                found.addAll(find(sub, type));
            }
        }
        return found;
    }

    /** Lays the panel out at a typical options-dialog size and saves a picture of it. */
    private static void render(ZaptunOptionsPanel p, File file) throws IOException {
        p.setSize(640, 300);
        layout(p);
        BufferedImage img = new BufferedImage(640, 300, BufferedImage.TYPE_INT_RGB);
        p.printAll(img.getGraphics());
        ImageIO.write(img, "png", file);
    }

    private static void layout(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container sub) {
                layout(sub);
            }
        }
    }
}
