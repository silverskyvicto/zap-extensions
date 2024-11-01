/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2024 The ZAP Development Team
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
package org.zaproxy.addon.encoder.processors.predefined.utility;

import java.io.IOException;
import java.text.Normalizer;
import org.zaproxy.addon.encoder.processors.predefined.DefaultEncodeDecodeProcessor;

public class Ascify extends DefaultEncodeDecodeProcessor {

    private static final Ascify INSTANCE = new Ascify();

    @Override
    protected String processInternal(String value) throws IOException {
        // Normalize with compatible decomposition, then remove anything non-ASCII
        return Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("[^\\p{ASCII}]", "");
    }

    public static Ascify getSingleton() {
        return INSTANCE;
    }
}
