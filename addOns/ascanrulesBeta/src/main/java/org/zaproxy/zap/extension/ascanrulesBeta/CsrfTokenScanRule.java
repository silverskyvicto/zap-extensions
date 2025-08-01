/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2012 The ZAP Development Team
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
package org.zaproxy.zap.extension.ascanrulesBeta;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.htmlparser.jericho.Attribute;
import net.htmlparser.jericho.Element;
import net.htmlparser.jericho.HTMLElementName;
import net.htmlparser.jericho.Source;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.core.scanner.AbstractAppPlugin;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.core.scanner.Category;
import org.parosproxy.paros.model.Model;
import org.parosproxy.paros.model.OptionsParam;
import org.parosproxy.paros.network.HtmlParameter;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.zaproxy.addon.commonlib.CommonAlertTag;
import org.zaproxy.addon.commonlib.PolicyTag;
import org.zaproxy.addon.commonlib.vulnerabilities.Vulnerabilities;
import org.zaproxy.addon.commonlib.vulnerabilities.Vulnerability;
import org.zaproxy.zap.extension.httpsessions.HttpSessionsParam;
import org.zaproxy.zap.extension.ruleconfig.RuleConfigParam;

/**
 * CsrfTokenScanRule is an effort to improve the anti-CSRF token detection of ZAP It is based on
 * previous plugins such as csrfcountermeasuresscan and sessionfixation
 */
public class CsrfTokenScanRule extends AbstractAppPlugin implements CommonActiveScanRuleInfo {

    private static final String MESSAGE_PREFIX = "ascanbeta.csrftoken.";
    private static final int PLUGIN_ID = 20012;
    private static final Map<String, String> ALERT_TAGS;

    static {
        Map<String, String> alertTags =
                new HashMap<>(
                        CommonAlertTag.toMap(
                                CommonAlertTag.OWASP_2021_A05_SEC_MISCONFIG,
                                CommonAlertTag.OWASP_2017_A06_SEC_MISCONFIG,
                                CommonAlertTag.WSTG_V42_SESS_05_CSRF));
        alertTags.put(PolicyTag.QA_STD.getTag(), "");
        alertTags.put(PolicyTag.QA_FULL.getTag(), "");
        alertTags.put(PolicyTag.PENTEST.getTag(), "");
        ALERT_TAGS = Collections.unmodifiableMap(alertTags);
    }

    private List<String> ignoreList = new ArrayList<>();
    private String ignoreAttName;
    private String ignoreAttValue;

    // WASC Threat Classification (WASC-9)
    private static final Vulnerability VULN = Vulnerabilities.getDefault().get("wasc_9");

    private static final Logger LOGGER = LogManager.getLogger(CsrfTokenScanRule.class);

    @Override
    public int getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName() {
        return Constant.messages.getString(MESSAGE_PREFIX + "name");
    }

    @Override
    public String getDescription() {
        return VULN.getDescription();
    }

    @Override
    public int getCategory() {
        return Category.SERVER;
    }

    @Override
    public String getSolution() {
        return VULN.getSolution();
    }

    @Override
    public String getReference() {
        return VULN.getReferencesAsString();
    }

    @Override
    public void init() {
        String ignoreConf = getConfig().getString(RuleConfigParam.RULE_CSRF_IGNORE_LIST);
        if (ignoreConf != null && ignoreConf.length() > 0) {
            LOGGER.debug("Using ignore list: {}", ignoreConf);
            for (String str : ignoreConf.split(",")) {
                String strTrim = str.trim();
                if (strTrim.length() > 0) {
                    ignoreList.add(strTrim);
                }
            }
        }
        ignoreAttName = getConfig().getString(RuleConfigParam.RULE_CSRF_IGNORE_ATT_NAME);
        ignoreAttValue = getConfig().getString(RuleConfigParam.RULE_CSRF_IGNORE_ATT_VALUE);
    }

    /**
     * Main method of the class. It is executed for each page. Determined whether the page in
     * vulnerable to CSRF or not.
     */
    @Override
    public void scan() {
        if ((AlertThreshold.HIGH.equals(getAlertThreshold()) && !getBaseMsg().isInScope())
                || !getBaseMsg().getResponseHeader().isHtml()) {
            return;
        }

        if (!AlertThreshold.LOW.equals(getAlertThreshold())
                && HttpRequestHeader.GET.equals(getBaseMsg().getRequestHeader().getMethod())) {
            return;
        }

        boolean vuln = false;
        Map<String, String> tagsMap = new HashMap<>();
        Source s1;
        try {
            // We parse the HTML of the response
            s1 = new Source(getBaseMsg().getResponseBody().toString());

            List<Element> formElements = s1.getAllElements(HTMLElementName.FORM);

            int formIdx = 0;
            for (Element formElement : formElements) {

                if (formOnIgnoreList(formElement)) {
                    continue;
                }

                // Assume the worst
                vuln = true;
                List<Element> inputElements = formElement.getAllElements(HTMLElementName.INPUT);
                for (Element inputElement : inputElements) {
                    if (isHiddenInputElement(inputElement) && hasNameAttribute(inputElement)) {
                        final String name = inputElement.getAttributeValue("name");
                        final String value = getNonNullValueAttribute(inputElement);
                        tagsMap.put(name, value);
                        LOGGER.debug("Input Tag: {}, {}", name, value);
                    }
                }

                // We keep only the "flagged as session" cookies, and perform again the request
                // Get HTTP session names from config
                OptionsParam options = Model.getSingleton().getOptionsParam();
                HttpSessionsParam sessionOptions = options.getParamSet(HttpSessionsParam.class);

                List<String> sessionIds;
                if (sessionOptions != null) {
                    // extension is enabled
                    sessionIds = sessionOptions.getDefaultTokensEnabled();
                } else {
                    // extension is disabled
                    sessionIds = Collections.emptyList();
                }

                HttpMessage newMsg = getNewMsg();
                TreeSet<HtmlParameter> newCookies = new TreeSet<>();

                // Loop the original cookies to keep only the session ones
                for (HtmlParameter cookie : newMsg.getCookieParams()) {
                    // if sessionIds contains cookie ignoring the case
                    // lambda would have been ==> if (sessionIds.stream().anyMatch(s ->
                    // s.equalsIgnoreCase(cookie.getName())))
                    for (String id : sessionIds) {
                        if (id.equalsIgnoreCase(cookie.getName())) {
                            LOGGER.debug("Keeping {} to be authenticated", cookie.getName());
                            newCookies.add(cookie);
                            break; // avoids looping over sessionIds if already found
                        }
                    }
                }
                newMsg.setCookieParams(newCookies);
                sendAndReceive(newMsg);

                // We parse the HTML of the response
                Source s2 = new Source(newMsg.getResponseBody().toString());
                List<Element> form2Elements = s2.getAllElements(HTMLElementName.FORM);
                if (form2Elements.size() > formIdx) {

                    List<Element> iElements =
                            form2Elements.get(formIdx).getAllElements(HTMLElementName.INPUT);

                    // We store the hidden input fields in a hash map.
                    for (Element element2 : iElements) {
                        if (isHiddenInputElement(element2) && hasNameAttribute(element2)) {
                            final String name = element2.getAttributeValue("name");
                            final String newValue = getNonNullValueAttribute(element2);
                            final String oldValue = tagsMap.get(name);
                            if (oldValue != null && !newValue.equals(oldValue)) {
                                LOGGER.debug("Found Anti-CSRF token: {}, {}", name, newValue);
                                vuln = false;
                            }
                        }
                    }
                    // If vulnerable, generates the alert
                    if (vuln) {
                        int risk = Alert.RISK_MEDIUM;
                        String evidence = formElement.getFirstElement().getStartTag().toString();
                        String otherInfo = "";

                        if (formHasSecurityAnnotation(formElement)) {
                            risk = Alert.RISK_INFO;
                            otherInfo =
                                    Constant.messages.getString(
                                            MESSAGE_PREFIX + "extrainfo.annotation");
                        }
                        buildAlert(risk, otherInfo, evidence).setMessage(getBaseMsg()).raise();
                    }
                }

                formIdx++;
            }
        } catch (IOException e) {
            LOGGER.debug(e, e);
        }
    }

    private AlertBuilder buildAlert(int risk, String otherInfo, String evidence) {
        return newAlert()
                .setRisk(risk)
                .setConfidence(Alert.CONFIDENCE_MEDIUM)
                .setOtherInfo(otherInfo)
                .setEvidence(evidence);
    }

    private boolean formOnIgnoreList(Element formElement) {
        String id = formElement.getAttributeValue("id");
        String name = formElement.getAttributeValue("name");
        for (String ignore : ignoreList) {
            if (ignore.equals(id)) {
                LOGGER.debug("Ignoring form with id = {}", id);
                return true;
            } else if (ignore.equals(name)) {
                LOGGER.debug("Ignoring form with name = {}", name);
                return true;
            }
        }
        return false;
    }

    private boolean formHasSecurityAnnotation(Element formElement) {
        if (!StringUtils.isEmpty(this.ignoreAttName)) {
            Attribute att = formElement.getAttributes().get(this.ignoreAttName);
            if (att != null) {
                if (StringUtils.isEmpty(this.ignoreAttValue)
                        || this.ignoreAttValue.equals(att.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isHiddenInputElement(Element inputElement) {
        return "hidden".equalsIgnoreCase(inputElement.getAttributeValue("type"));
    }

    private static boolean hasNameAttribute(Element element) {
        return element.getAttributeValue("name") != null;
    }

    private static String getNonNullValueAttribute(Element element) {
        final String value = element.getAttributeValue("value");

        if (value == null) {
            return "";
        }
        return value;
    }

    @Override
    public int getRisk() {
        return Alert.RISK_MEDIUM;
    }

    @Override
    public int getCweId() {
        return 352;
    }

    @Override
    public int getWascId() {
        return 9;
    }

    @Override
    public Map<String, String> getAlertTags() {
        return ALERT_TAGS;
    }

    @Override
    public List<Alert> getExampleAlerts() {
        return List.of(
                buildAlert(
                                Alert.RISK_MEDIUM,
                                "",
                                "<input type=\"hidden\" name=\"firstName\" value=\"\">")
                        .build());
    }
}
