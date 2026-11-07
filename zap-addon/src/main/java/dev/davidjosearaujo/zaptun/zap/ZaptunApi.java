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

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sf.json.JSONObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zaproxy.zap.extension.api.ApiAction;
import org.zaproxy.zap.extension.api.ApiException;
import org.zaproxy.zap.extension.api.ApiImplementor;
import org.zaproxy.zap.extension.api.ApiResponse;
import org.zaproxy.zap.extension.api.ApiResponseElement;
import org.zaproxy.zap.extension.api.ApiResponseList;
import org.zaproxy.zap.extension.api.ApiResponseSet;
import org.zaproxy.zap.extension.api.ApiView;

/**
 * ZAP API for scripts and CI, e.g. {@code /JSON/zaptun/action/intercept/?serial=emulator-5554}.
 * Without a serial, the only connected device is used. zaptun/view/adb shows which adb runs, and
 * zaptun/action/useDetectedAdb clears the configured paths so the environment is used again.
 * Progress and failure reasons go to ZAP's log (ZAP only puts error details in API responses if
 * that's enabled in its API options).
 */
public class ZaptunApi extends ApiImplementor {

    private static final Logger LOGGER = LogManager.getLogger(ZaptunApi.class);
    private static final String PREFIX = "zaptun";
    private static final String SERIAL = "serial";
    private static final String APPS = "apps";

    private final DeviceManager devices;
    private final ZaptunParam param;
    private final Adb adb;

    @SuppressWarnings("this-escape")
    public ZaptunApi(DeviceManager devices, ZaptunParam param, Adb adb) {
        this.devices = devices;
        this.param = param;
        this.adb = adb;
        // zaptun/view/optionSdkPath, zaptun/action/setOptionAdbPath, ... for headless ZAP.
        addApiOptions(param);
        addApiView(new ApiView("adb"));
        addApiAction(new ApiAction("useDetectedAdb"));
        addApiView(new ApiView("devices"));
        addApiView(new ApiView("packages", null, new String[] {SERIAL}));
        addApiAction(new ApiAction("intercept", null, new String[] {SERIAL, APPS}));
        addApiAction(new ApiAction("stop", null, new String[] {SERIAL}));
    }

    @Override
    public String getPrefix() {
        return PREFIX;
    }

    @Override
    public ApiResponse handleApiView(String name, JSONObject params) throws ApiException {
        try {
            switch (name) {
                case "adb":
                    return new ApiResponseElement(name, adb.executable());
                case "devices":
                    ApiResponseList list = new ApiResponseList(name);
                    for (DeviceManager.Device d : devices.devices()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("serial", d.serial());
                        m.put("state", d.state());
                        m.put("model", d.model());
                        m.put("android", d.androidVersion());
                        m.put("sdk", d.sdk());
                        m.put("root", d.root().name().toLowerCase());
                        m.put("installed", d.installed());
                        m.put("intercepting", d.intercepting());
                        list.addItem(new ApiResponseSet<>("device", m));
                    }
                    return list;
                case "packages":
                    ApiResponseList packages = new ApiResponseList(name);
                    for (String p : devices.userPackages(serial(params))) {
                        packages.addItem(new ApiResponseElement("package", p));
                    }
                    return packages;
                default:
                    throw new ApiException(ApiException.Type.BAD_VIEW);
            }
        } catch (IOException e) {
            throw failed(name, e);
        }
    }

    @Override
    public ApiResponse handleApiAction(String name, JSONObject params) throws ApiException {
        try {
            switch (name) {
                case "useDetectedAdb":
                    // The generated setOption* actions can't set an empty value, so this clears
                    // both.
                    param.setAdbPath("");
                    param.setSdkPath("");
                    break;
                case "intercept":
                    List<String> apps = ExtensionZaptun.parseApps(params.optString(APPS, ""));
                    if (!apps.stream().allMatch(DeviceManager::isPackageName)) {
                        throw new ApiException(ApiException.Type.ILLEGAL_PARAMETER, APPS);
                    }
                    devices.intercept(serial(params), apps, l -> LOGGER.info("zaptun: {}", l));
                    break;
                case "stop":
                    devices.stop(serial(params), l -> LOGGER.info("zaptun: {}", l));
                    break;
                default:
                    throw new ApiException(ApiException.Type.BAD_ACTION);
            }
            return ApiResponseElement.OK;
        } catch (IOException e) {
            throw failed(name, e);
        }
    }

    private static ApiException failed(String name, IOException e) {
        LOGGER.warn("zaptun {}: {}", name, e.getMessage());
        return new ApiException(ApiException.Type.BAD_STATE, e.getMessage());
    }

    private String serial(JSONObject params) throws IOException {
        String serial = params.optString(SERIAL, "").trim();
        return serial.isEmpty() ? devices.defaultSerial() : serial;
    }
}
