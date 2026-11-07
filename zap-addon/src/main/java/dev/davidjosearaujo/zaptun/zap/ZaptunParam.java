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

import org.zaproxy.zap.common.VersionedAbstractParam;

/**
 * Saved settings: the Android SDK folder and adb binary (both optional, see {@link
 * Adb#executable()}) and the apps last chosen for capture. Also exposed on the ZAP API as
 * zaptun/view/optionAdbPath etc.
 */
public class ZaptunParam extends VersionedAbstractParam {

    private static final String BASE = "zaptun";
    private static final String CONFIG_VERSION_KEY = BASE + VERSION_ATTRIBUTE;
    private static final int CURRENT_VERSION = 1;
    private static final String ADB_PATH = BASE + ".adbPath";
    private static final String SDK_PATH = BASE + ".sdkPath";
    private static final String APPS = BASE + ".apps";

    private String adbPath = "";
    private String sdkPath = "";
    private String apps = "";

    @Override
    protected void parseImpl() {
        adbPath = getString(ADB_PATH, "");
        sdkPath = getString(SDK_PATH, "");
        apps = getString(APPS, "");
    }

    @Override
    protected String getConfigVersionKey() {
        return CONFIG_VERSION_KEY;
    }

    @Override
    protected int getCurrentVersion() {
        return CURRENT_VERSION;
    }

    @Override
    protected void updateConfigsImpl(int fileVersion) {}

    public String getAdbPath() {
        return adbPath;
    }

    public void setAdbPath(String adbPath) {
        this.adbPath = adbPath == null ? "" : adbPath.trim();
        getConfig().setProperty(ADB_PATH, this.adbPath);
    }

    public String getSdkPath() {
        return sdkPath;
    }

    public void setSdkPath(String sdkPath) {
        this.sdkPath = sdkPath == null ? "" : sdkPath.trim();
        getConfig().setProperty(SDK_PATH, this.sdkPath);
    }

    public String getApps() {
        return apps;
    }

    public void setApps(String apps) {
        this.apps = apps == null ? "" : apps.trim();
        getConfig().setProperty(APPS, this.apps);
    }
}
