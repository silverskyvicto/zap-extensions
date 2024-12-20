/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2017 The ZAP Development Team
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
package org.zaproxy.zap.extension.openapi.generators;

import java.util.Collections;
import java.util.HashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.zaproxy.addon.commonlib.ValueProvider;

/** A wrapper around the core ValueGenerator class */
public class ValueGenerator {

    private static final Logger LOGGER = LogManager.getLogger(ValueGenerator.class);

    private ValueProvider valueProvider;

    public ValueGenerator(ValueProvider valueProvider) {
        this.valueProvider = valueProvider;
    }

    public String getValue(String name, String type, String defaultValue) {
        if (defaultValue == null) {
            defaultValue = "";
        }
        if (valueProvider == null) {
            LOGGER.debug(
                    "Name : {} Type : {} Default : {} Returning default value",
                    name,
                    type,
                    defaultValue);
            return defaultValue;
        }

        HashMap<String, String> fieldAtts = new HashMap<>();
        fieldAtts.put("Control Type", type == null ? "" : type);
        String value =
                valueProvider.getValue(
                        null,
                        null,
                        name,
                        defaultValue,
                        Collections.<String>emptyList(),
                        Collections.<String, String>emptyMap(),
                        fieldAtts);

        LOGGER.debug(
                "Name : {} Type : {} Default : {} Returning : {}", name, type, defaultValue, value);

        return value;
    }
}
