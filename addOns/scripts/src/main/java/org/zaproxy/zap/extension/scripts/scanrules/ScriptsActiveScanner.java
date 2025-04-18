/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2023 The ZAP Development Team
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
package org.zaproxy.zap.extension.scripts.scanrules;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.core.scanner.Category;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.addon.commonlib.PolicyTag;
import org.zaproxy.addon.commonlib.scanrules.ScanRuleMetadataProvider;
import org.zaproxy.zap.extension.ascan.ExtensionActiveScan;
import org.zaproxy.zap.extension.script.ExtensionScript;
import org.zaproxy.zap.extension.script.ScriptWrapper;
import org.zaproxy.zap.extension.script.ScriptsCache;
import org.zaproxy.zap.extension.script.ScriptsCache.CachedScript;
import org.zaproxy.zap.extension.script.ScriptsCache.Configuration;
import org.zaproxy.zap.extension.script.ScriptsCache.InterfaceProvider;

public class ScriptsActiveScanner extends ActiveScriptHelper {

    private ExtensionScript extension = null;
    private ScriptsCache<ActiveScript> cachedScripts;

    private static final Logger LOGGER = LogManager.getLogger(ScriptsActiveScanner.class);
    private static final Map<String, String> POLICY_ALERT_TAGS =
            Stream.of(PolicyTag.values())
                    .collect(Collectors.toUnmodifiableMap(k -> k.getTag(), v -> ""));

    /**
     * A {@code Set} containing the scripts that do not implement {@code ActiveScript2}, to show an
     * error if those scripts do not implement {@code ActiveScript} (thus not implementing any of
     * the required interfaces).
     *
     * @see #scan()
     * @see #scan(HttpMessage, String, String)
     */
    private Set<ScriptWrapper> scriptsNoInterface = new HashSet<>();

    @Override
    public int getId() {
        return 50000;
    }

    @Override
    public String getName() {
        return Constant.messages.getString("scripts.scanRules.ascan.name");
    }

    @Override
    public String[] getDependency() {
        return null;
    }

    @Override
    public String getDescription() {
        return "";
    }

    @Override
    public int getCategory() {
        return Category.MISC;
    }

    @Override
    public String getSolution() {
        return "";
    }

    @Override
    public String getReference() {
        return "";
    }

    @Override
    public void init() {
        if (shouldSkipScan()) {
            getParent()
                    .pluginSkipped(
                            this,
                            Constant.messages.getString("scripts.scanRules.ascan.skipReason"));
        }
    }

    /**
     * Tells whether the scanner should be skipped. The scanner should be skipped when the {@code
     * ExtensionScript} is not enabled, when there are no scripts, or if there are none is enabled.
     *
     * @return {@code true} if the scanner should be skipped, {@code false} otherwise
     */
    private boolean shouldSkipScan() {
        if (this.getExtension() == null) {
            return true;
        }

        List<ScriptWrapper> scripts = getActiveScripts();
        if (scripts.isEmpty()) {
            return true;
        }

        for (ScriptWrapper script : scripts) {
            if (script.isEnabled()) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns the scripts of active script type.
     *
     * <p><strong>Note:</strong> this method should be called only when {@code getExtension()}
     * returns non-{@code null}.
     *
     * @return a {@code List} containing the scripts with active script type, never {@code null}
     * @see #getExtension()
     * @see ExtensionActiveScan#SCRIPT_TYPE_ACTIVE
     */
    private List<ScriptWrapper> getActiveScripts() {
        return this.getExtension().getScripts(ExtensionActiveScan.SCRIPT_TYPE_ACTIVE);
    }

    private ExtensionScript getExtension() {
        if (extension == null) {
            extension =
                    Control.getSingleton().getExtensionLoader().getExtension(ExtensionScript.class);
        }
        return extension;
    }

    @Override
    public void scan() {
        List<ScriptWrapper> scripts = this.getActiveScripts();

        for (Iterator<ScriptWrapper> it = scripts.iterator(); it.hasNext() && !isStop(); ) {
            ScriptWrapper script = it.next();
            try {
                if (script.isEnabled()) {

                    if (ScriptSynchronizerUtils.providesMetadata(script)) {
                        continue;
                    }

                    ActiveScript2 s = extension.getInterface(script, ActiveScript2.class);

                    if (s != null) {
                        HttpMessage msg = this.getNewMsg();
                        LOGGER.debug(
                                "Calling script {} scanNode for {}",
                                script.getName(),
                                msg.getRequestHeader().getURI());
                        s.scanNode(this, msg);
                    } else {
                        scriptsNoInterface.add(script);
                    }
                }

            } catch (Exception e) {
                extension.handleScriptException(script, e);
            }
        }

        if (!isStop()) {
            InterfaceProvider<ActiveScript> interfaceProvider =
                    (scriptWrapper, targetInterface) -> {
                        if (extension.getInterface(scriptWrapper, ScanRuleMetadataProvider.class)
                                != null) {
                            return null;
                        }
                        ActiveScript s = extension.getInterface(scriptWrapper, targetInterface);
                        if (s != null) {
                            return s;
                        }
                        if (scriptsNoInterface.contains(scriptWrapper)) {
                            extension.handleFailedScriptInterface(
                                    scriptWrapper,
                                    Constant.messages.getString(
                                            "scripts.scanRules.ascan.interfaceError",
                                            scriptWrapper.getName()));
                        }
                        return null;
                    };
            cachedScripts =
                    getExtension()
                            .createScriptsCache(
                                    Configuration.<ActiveScript>builder()
                                            .setScriptType(ExtensionActiveScan.SCRIPT_TYPE_ACTIVE)
                                            .setTargetInterface(ActiveScript.class)
                                            .setInterfaceProvider(interfaceProvider)
                                            .build());
            super.scan();
        }
        scriptsNoInterface.clear();
    }

    @Override
    public void scan(HttpMessage msg, String param, String value) {
        cachedScripts.refresh();

        for (CachedScript<ActiveScript> cachedScript : cachedScripts.getCachedScripts()) {
            if (isStop()) {
                return;
            }

            ScriptWrapper script = cachedScript.getScriptWrapper();
            try {
                LOGGER.debug(
                        "Calling script {} scan for {} param={} value={}",
                        script.getName(),
                        msg.getRequestHeader().getURI(),
                        param,
                        value);
                cachedScript.getScript().scan(this, msg, param, value);

            } catch (Exception e) {
                extension.handleScriptException(script, e);
            }
        }
    }

    @Override
    public int getRisk() {
        return Alert.RISK_INFO;
    }

    @Override
    public int getCweId() {
        return 0;
    }

    @Override
    public int getWascId() {
        return 0;
    }

    @Override
    public Map<String, String> getAlertTags() {
        return POLICY_ALERT_TAGS;
    }
}
