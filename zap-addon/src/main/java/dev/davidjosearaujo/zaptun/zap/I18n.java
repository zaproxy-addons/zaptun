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

import org.parosproxy.paros.Constant;

/** Looks up the add-on's messages: Messages.properties keys are all prefixed with "zaptun.". */
final class I18n {

    private I18n() {}

    static String msg(String key, Object... args) {
        String k = ExtensionZaptun.PREFIX + "." + key;
        return args.length == 0
                ? Constant.messages.getString(k)
                : Constant.messages.getString(k, args);
    }
}
