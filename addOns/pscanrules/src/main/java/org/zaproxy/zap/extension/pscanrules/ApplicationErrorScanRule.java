/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2013 The ZAP Development Team
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
package org.zaproxy.zap.extension.pscanrules;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.htmlparser.jericho.Source;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.core.scanner.Plugin.AlertThreshold;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.addon.commonlib.CommonAlertTag;
import org.zaproxy.addon.commonlib.PolicyTag;
import org.zaproxy.addon.commonlib.ResourceIdentificationUtils;
import org.zaproxy.zap.extension.pscan.PluginPassiveScanner;
import org.zaproxy.zap.utils.ContentMatcher;

/**
 * Plugin able to analyze the content for Application Error messages. The plugin find the first
 * occurrence of an exact match or a regex pattern matching according to an external file
 * definition. The vulnerability can be included inside the Information Leakage family (WASC-13)
 *
 * @author yhawke 2013
 */
public class ApplicationErrorScanRule extends PluginPassiveScanner
        implements CommonPassiveScanRuleInfo {

    /** Prefix for internationalised messages used by this rule */
    private static final String MESSAGE_PREFIX = "pscanrules.applicationerrors.";

    private static final Map<String, String> ALERT_TAGS;

    static {
        Map<String, String> alertTags =
                new HashMap<>(
                        CommonAlertTag.toMap(
                                CommonAlertTag.OWASP_2021_A05_SEC_MISCONFIG,
                                CommonAlertTag.OWASP_2017_A06_SEC_MISCONFIG,
                                CommonAlertTag.WSTG_V42_ERRH_01_ERR,
                                CommonAlertTag.WSTG_V42_ERRH_02_STACK));
        alertTags.put(PolicyTag.PENTEST.getTag(), "");
        alertTags.put(PolicyTag.QA_STD.getTag(), "");
        ALERT_TAGS = Collections.unmodifiableMap(alertTags);
    }

    private static final Logger LOGGER = LogManager.getLogger(ApplicationErrorScanRule.class);

    // Name of the file related to pattern's definition list
    private String APP_ERRORS_FILE =
            Constant.getZapHome()
                    + File.separator
                    + "xml"
                    + File.separator
                    + "application_errors.xml";

    public static final List<String> DEFAULT_ERRORS = Collections.emptyList();
    private static final Supplier<Iterable<String>> DEFAULT_PAYLOAD_PROVIDER = () -> DEFAULT_ERRORS;
    public static final String ERRORS_PAYLOAD_CATEGORY = "Application-Errors";

    private static Supplier<Iterable<String>> payloadProvider = DEFAULT_PAYLOAD_PROVIDER;

    // Inner Content Matcher component with pattern definitions
    private ContentMatcher matcher = null;

    private ContentMatcher getContentMatcher() {
        if (matcher == null) {
            Path path = Paths.get(APP_ERRORS_FILE);
            try (InputStream is = Files.newInputStream(path)) {
                matcher = ContentMatcher.getInstance(is);
            } catch (IOException | IllegalArgumentException e) {
                LOGGER.warn(
                        "Unable to read {} input file: {}. Falling back to ZAP archive.",
                        getName(),
                        APP_ERRORS_FILE);
                matcher =
                        ContentMatcher.getInstance(
                                ApplicationErrorScanRule.class.getResourceAsStream(
                                        "/xml/application_errors.xml"));
            }
        }
        return matcher;
    }

    /**
     * Get this plugin id
     *
     * @return the ZAP id
     */
    @Override
    public int getPluginId() {
        return 90022;
    }

    /**
     * Get the plugin name
     *
     * @return the plugin name
     */
    @Override
    public String getName() {
        return Constant.messages.getString(MESSAGE_PREFIX + "name");
    }

    @Override
    public Map<String, String> getAlertTags() {
        return ALERT_TAGS;
    }

    /**
     * Perform the passive scanning of application errors inside the response content
     *
     * @param msg the message that need to be checked
     * @param id the id of the session
     * @param source the source code of the response
     */
    @Override
    public void scanHttpResponseReceive(HttpMessage msg, int id, Source source) {
        if (ResourceIdentificationUtils.responseContainsControlChars(msg)) {
            return;
        }

        // First check if it's an INTERNAL SERVER ERROR
        if (getHelper().isPage500(msg)) {
            // We found it!
            // The AS raise an Internal Error
            // so a possible disclosure can be found
            if (AlertThreshold.HIGH.equals(this.getAlertThreshold())) {
                // No need to alert
                return;
            }
            buildAlert(msg, id, msg.getResponseHeader().getPrimeHeader())
                    .setRisk(Alert.RISK_LOW)
                    .raise();

        } else if (!getHelper().isPage404(msg)
                && !msg.getResponseHeader().hasContentType("application/wasm")) {

            if (!AlertThreshold.LOW.equals(this.getAlertThreshold())
                    && (ResourceIdentificationUtils.isJavaScript(msg)
                            || ResourceIdentificationUtils.isCss(msg))) {
                return;
            }
            String body = msg.getResponseBody().toString();
            for (String payload : getCustomPayloads().get()) {
                if (body.contains(payload)) {
                    raiseAlert(msg, id, payload);
                    return;
                }
            }
            String evidence = getContentMatcher().findInContent(body);
            if (evidence != null) {
                // We found it!
                // There exists a positive match of an
                // application error occurrence
                raiseAlert(msg, id, evidence);
            }
        }
    }

    // Internal service method for alert management
    private void raiseAlert(HttpMessage msg, int id, String evidence) {
        buildAlert(msg, id, evidence).raise();
    }

    private AlertBuilder buildAlert(HttpMessage msg, int id, String evidence) {
        return newAlert()
                .setRisk(Alert.RISK_MEDIUM)
                .setConfidence(Alert.CONFIDENCE_MEDIUM)
                .setDescription(Constant.messages.getString(MESSAGE_PREFIX + "desc"))
                .setSolution(Constant.messages.getString(MESSAGE_PREFIX + "soln"))
                .setEvidence(evidence)
                // CWE-550: Server-generated Error Message Containing Sensitive Information
                .setCweId(550)
                .setWascId(13);
    }

    @Override
    public List<Alert> getExampleAlerts() {
        List<Alert> alerts = new ArrayList<>();
        Alert example = buildAlert(null, 0, "ERROR: parser: parse error at or near").build();
        example.setTags(
                CommonAlertTag.mergeTags(example.getTags(), CommonAlertTag.CUSTOM_PAYLOADS));
        alerts.add(example);
        return alerts;
    }

    static Supplier<Iterable<String>> getCustomPayloads() {
        return payloadProvider;
    }

    public static void setPayloadProvider(Supplier<Iterable<String>> provider) {
        payloadProvider = provider == null ? DEFAULT_PAYLOAD_PROVIDER : provider;
    }
}
