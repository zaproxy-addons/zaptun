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

import java.util.List;
import org.junit.Test;

public class AdbTest {

    @Test
    public void parsesDevicesLong() {
        String out =
                "* daemon not running; starting now at tcp:5037\n"
                        + "* daemon started successfully\n"
                        + "List of devices attached\n"
                        + "emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64xa transport_id:1\n"
                        + "R58M12ABCDE            unauthorized usb:1-1 transport_id:2\n"
                        + "\n";
        List<Adb.DeviceEntry> devices = Adb.parseDevices(out);
        assertEquals(2, devices.size());
        assertEquals("emulator-5554", devices.get(0).serial());
        assertEquals("device", devices.get(0).state());
        assertEquals("sdk_gphone64_x86_64", devices.get(0).props().get("model"));
        assertEquals("unauthorized", devices.get(1).state());
    }
}
