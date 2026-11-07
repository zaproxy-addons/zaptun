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

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.extension.Extension;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.zaproxy.addon.network.ExtensionNetwork;
import org.zaproxy.addon.network.server.ServerInfo;
import org.zaproxy.zap.view.ZapMenuItem;

/**
 * Adds "Android Devices..." to ZAP: one click installs zaptun on a device and captures its traffic.
 */
public class ExtensionZaptun extends ExtensionAdaptor {

    public static final String NAME = "ExtensionZaptun";
    static final String PREFIX = "zaptun";

    /** The help page's ID, from the add-on's JavaHelp map (map.jhm). */
    static final String HELP_INDEX = "addon.zaptun";

    private static final List<Class<? extends Extension>> DEPENDENCIES =
            List.of(ExtensionNetwork.class);

    private final ZaptunParam param = new ZaptunParam();
    private DeviceManager devices;
    private DevicesDialog dialog;

    public ExtensionZaptun() {
        super(NAME);
    }

    /** Messages.properties keys start with "zaptun.", not the package's last segment ("zap"). */
    @Override
    public String getI18nPrefix() {
        return PREFIX;
    }

    @Override
    public String getUIName() {
        return msg("name");
    }

    @Override
    public String getDescription() {
        return msg("desc");
    }

    @Override
    public List<Class<? extends Extension>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void hook(ExtensionHook hook) {
        super.hook(hook);
        hook.addOptionsParamSet(param);
        AdbLocator locator = new AdbLocator();
        Adb adb = new Adb(() -> new Adb.Config(param.getAdbPath(), param.getSdkPath()), locator);
        devices = new DeviceManager(adb.runner(), new NetworkZap());
        hook.addApiImplementor(new ZaptunApi(devices, param, adb));

        if (hasView()) {
            hook.getHookView().addOptionPanel(new ZaptunOptionsPanel(locator));

            ZapMenuItem menu = new ZapMenuItem(PREFIX + ".menu");
            menu.setIcon(icon());
            menu.addActionListener(e -> showDialog());
            hook.getHookMenu().addToolsMenuItem(menu);

            JButton button = new JButton(icon());
            button.setToolTipText(msg("toolbar.tooltip"));
            button.addActionListener(e -> showDialog());
            hook.getHookView().addMainToolBarComponent(button);
        }
    }

    @Override
    public boolean canUnload() {
        return true;
    }

    @Override
    public void unload() {
        if (dialog != null) {
            dialog.dispose();
            dialog = null;
        }
        super.unload();
    }

    ZaptunParam getParam() {
        return param;
    }

    private void showDialog() {
        if (dialog == null) {
            dialog = new DevicesDialog(getView().getMainFrame(), devices, param);
        }
        dialog.showAndRefresh();
    }

    static List<String> parseApps(String s) {
        return Arrays.stream(s.split("[,\\s]+"))
                .map(String::trim)
                .filter(a -> !a.isEmpty())
                .toList();
    }

    /** A phone with a lightning bolt, drawn so the add-on needs no image files. */
    static ImageIcon icon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x3D, 0xDC, 0x84)); // Android green
        g.fillRoundRect(3, 0, 10, 16, 3, 3);
        g.setColor(Color.WHITE);
        g.fillRect(4, 2, 8, 11);
        g.setColor(new Color(0x00, 0x54, 0x9E)); // ZAP blue
        g.fillPolygon(new int[] {9, 5, 8, 7, 11, 8}, new int[] {3, 8, 8, 12, 7, 7}, 6);
        g.dispose();
        return new ImageIcon(img);
    }

    /** ZAP's side, read from the network add-on. */
    private static class NetworkZap implements DeviceManager.Zap {
        private static ExtensionNetwork network() {
            return Control.getSingleton().getExtensionLoader().getExtension(ExtensionNetwork.class);
        }

        @Override
        public void writeRootCaPem(Path file) throws IOException {
            network().writeRootCaCertAsPem(file);
        }

        @Override
        public int proxyPort() {
            return network().getMainProxyServerInfo().getPort();
        }

        @Override
        public String proxyAddress() {
            ServerInfo info = network().getMainProxyServerInfo();
            return info.getAddress();
        }
    }
}
