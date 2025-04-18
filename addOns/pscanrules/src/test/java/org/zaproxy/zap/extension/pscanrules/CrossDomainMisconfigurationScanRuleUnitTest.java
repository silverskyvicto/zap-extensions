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
package org.zaproxy.zap.extension.pscanrules;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpMalformedHeaderException;
import org.parosproxy.paros.network.HttpMessage;
import org.zaproxy.addon.commonlib.CommonAlertTag;
import org.zaproxy.addon.commonlib.PolicyTag;

class CrossDomainMisconfigurationScanRuleUnitTest
        extends PassiveScannerTest<CrossDomainMisconfigurationScanRule> {

    private static final String URI = "http://example.com/";

    @Override
    protected CrossDomainMisconfigurationScanRule createScanner() {
        return new CrossDomainMisconfigurationScanRule();
    }

    @Test
    void shouldReturnExpectedMappings() {
        // Given / When
        Map<String, String> tags = rule.getAlertTags();
        // Then
        assertThat(tags.size(), is(equalTo(4)));
        assertThat(
                tags.containsKey(CommonAlertTag.OWASP_2021_A01_BROKEN_AC.getTag()),
                is(equalTo(true)));
        assertThat(
                tags.containsKey(CommonAlertTag.OWASP_2017_A05_BROKEN_AC.getTag()),
                is(equalTo(true)));
        assertThat(tags.containsKey(PolicyTag.PENTEST.getTag()), is(equalTo(true)));
        assertThat(tags.containsKey(PolicyTag.QA_STD.getTag()), is(equalTo(true)));
        assertThat(
                tags.get(CommonAlertTag.OWASP_2021_A01_BROKEN_AC.getTag()),
                is(equalTo(CommonAlertTag.OWASP_2021_A01_BROKEN_AC.getValue())));
        assertThat(
                tags.get(CommonAlertTag.OWASP_2017_A05_BROKEN_AC.getTag()),
                is(equalTo(CommonAlertTag.OWASP_2017_A05_BROKEN_AC.getValue())));
    }

    @Test
    void shouldHaveExpectedExampleAlert() {
        // Given / When
        List<Alert> alerts = rule.getExampleAlerts();
        // Then
        assertThat(alerts.size(), is(equalTo(1)));
    }

    @Test
    @Override
    public void shouldHaveValidReferences() {
        super.shouldHaveValidReferences();
    }

    @Test
    void shouldNotRaiseAlertIfCorsAllowOriginHeaderIsMissing() {
        // Given
        HttpMessage msg = createResponse(URI, null);
        // When
        scanHttpResponseReceive(msg);
        // Then
        assertThat(alertsRaised.size(), is(0));
    }

    @Test
    void shouldNotRaiseAlertIfCorsAllowOriginHeaderIsEmpty() {
        // Given
        HttpMessage msg = createResponse(URI, "");
        // When
        scanHttpResponseReceive(msg);
        // Then
        assertThat(alertsRaised.size(), is(0));
    }

    @Test
    void shouldNotRaiseAlertIfCorsAllowOriginHeaderContainsUnrecognisedValue() {
        // Given
        HttpMessage msg = createResponse(URI, "UnrecognisedValue");
        // When
        scanHttpResponseReceive(msg);
        // Then
        assertThat(alertsRaised.size(), is(0));
    }

    @Test
    void shouldRaiseAlertIfCorsAllowOriginHeaderIsTooPermissive() {
        // Given
        HttpMessage msg = createResponse(URI, "*");
        // When
        scanHttpResponseReceive(msg);
        // Then
        assertThat(alertsRaised.size(), is(1));
        assertThat(
                alertsRaised.get(0).getEvidence(),
                is(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN + ": *"));
        assertThat(alertsRaised.get(0).getOtherInfo(), containsString("CORS misconfiguration"));
        assertThat(alertsRaised.get(0).getRisk(), is(Alert.RISK_MEDIUM));
        assertThat(alertsRaised.get(0).getConfidence(), is(Alert.CONFIDENCE_MEDIUM));
    }

    @Test
    void shouldRaiseAlertIfCorsAllowOriginHeaderWithDifferentCaseIsTooPermissive() {
        // Given
        HttpMessage msg = createResponse(URI, null);
        msg.getResponseHeader()
                .addHeader(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN.toUpperCase(Locale.ROOT), "*");
        // When
        scanHttpResponseReceive(msg);
        // Then
        assertThat(alertsRaised.size(), is(1));
        assertThat(
                alertsRaised.get(0).getEvidence(),
                is(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN.toUpperCase(Locale.ROOT) + ": *"));
    }

    private static HttpMessage createResponse(String uri, String corsAllowOriginValue) {
        HttpMessage msg = new HttpMessage();
        try {
            msg.setRequestHeader("GET " + uri + " HTTP/1.1");
            StringBuilder responseBuilder = new StringBuilder(75);
            responseBuilder.append("HTTP/1.1 200 OK\r\n");
            if (corsAllowOriginValue != null) {
                responseBuilder
                        .append(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN)
                        .append(": ")
                        .append(corsAllowOriginValue);
            }
            msg.setResponseHeader(responseBuilder.toString());

        } catch (HttpMalformedHeaderException e) {
            throw new RuntimeException(e);
        }

        return msg;
    }
}
