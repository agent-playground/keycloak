/*
 * Copyright 2026 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.tests.login;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.keycloak.models.credential.OTPCredentialModel;
import org.keycloak.models.utils.HmacOTP;
import org.keycloak.testframework.annotations.InjectRealm;
import org.keycloak.testframework.annotations.KeycloakIntegrationTest;
import org.keycloak.testframework.oauth.OAuthClient;
import org.keycloak.testframework.oauth.annotations.InjectOAuthClient;
import org.keycloak.testframework.realm.ClientBuilder;
import org.keycloak.testframework.realm.ManagedRealm;
import org.keycloak.testframework.realm.RealmBuilder;
import org.keycloak.testframework.realm.RealmConfig;
import org.keycloak.testframework.realm.UserBuilder;
import org.keycloak.util.JsonSerialization;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CVE-2026-118030（https://github.com/keycloak/keycloak/issues/54414）的端到端重現：對完整伺服器
 * 以同一個 HOTP code 並行送出 direct grant，檢查是否核發多個 session 的 token。
 *
 * <p>這是不控制交錯的黑箱測試，結果取決於實際排程，因此每輪換新 code 並跑多輪，驗證修補後
 * 每輪只能有一個請求成功，不可對同一個 code 核發多個 session。</p>
 */
@KeycloakIntegrationTest
public class HotpConcurrentLoginTest {

    private static final String SECRET = "hotpSecret";
    private static final String USERNAME = "hotp-user";
    private static final String PASSWORD = "password";
    private static final String CLIENT_ID = "hotp-client";
    private static final String CLIENT_SECRET = "secret1";
    private static final int ROUNDS = 100;
    private static final int PARALLEL_REQUESTS = 8;

    @InjectRealm(config = HotpRealmConfig.class)
    ManagedRealm realm;

    @InjectOAuthClient
    OAuthClient oauth;

    public static class HotpRealmConfig implements RealmConfig {
        @Override
        public RealmBuilder configure(RealmBuilder realm) {
            realm.otpType(OTPCredentialModel.HOTP);
            realm.otpAlgorithm(HmacOTP.DEFAULT_ALGORITHM);
            realm.otpDigits(6);
            realm.clients(ClientBuilder.create(CLIENT_ID)
                    .secret(CLIENT_SECRET)
                    .directAccessGrantsEnabled(true));
            realm.users(UserBuilder.create(USERNAME)
                    .name("Hotp", "User")
                    .email("hotp-user@localhost")
                    .emailVerified(true)
                    .password(PASSWORD)
                    .hotpSecret(SECRET));
            return realm;
        }
    }

    @Test
    public void reproSameCodeIssuesTokensForMultipleSessions() throws Exception {
        HmacOTP tokens = new HmacOTP(6, HmacOTP.DEFAULT_ALGORITHM, 0);
        URI tokenEndpoint = URI.create(oauth.getEndpoints().getToken());
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS);

        // 對照組：確認 OTP 真的有被檢查，且循序重送同一個 code 會被拒絕
        assertEquals(400, send(http, tokenEndpoint, "000000x").statusCode(), "錯誤的 code 必須被拒絕");
        assertEquals(200, send(http, tokenEndpoint, tokens.generateHOTP(SECRET, 0)).statusCode(), "有效的 code 必須成功");
        assertEquals(400, send(http, tokenEndpoint, tokens.generateHOTP(SECRET, 0)).statusCode(), "循序重送必須被拒絕");

        int roundsWithReuse = 0;
        int maxSessionsForOneCode = 0;
        int distinctSessions = 1;
        Map<Integer, Integer> statuses = new TreeMap<>();

        try {
            for (int counter = 1; counter <= ROUNDS; counter++) {
                String code = tokens.generateHOTP(SECRET, counter);
                List<HttpResponse<String>> responses = sendConcurrently(http, executor, tokenEndpoint, code);

                Set<String> sessions = new HashSet<>();
                for (HttpResponse<String> response : responses) {
                    statuses.merge(response.statusCode(), 1, Integer::sum);
                    if (response.statusCode() == 200) {
                        JsonNode body = JsonSerialization.readValue(response.body(), JsonNode.class);
                        sessions.add(body.get("session_state").asText());
                    }
                }
                assertEquals(1, sessions.size(), "每輪的有效 code 只能成功一次：" + statuses);
                distinctSessions += sessions.size();
                if (sessions.size() > 1) {
                    roundsWithReuse++;
                    maxSessionsForOneCode = Math.max(maxSessionsForOneCode, sessions.size());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        // 伺服器端實際存在的 session 數，不只相信回應內容
        String userId = realm.admin().users().search(USERNAME, true).get(0).getId();
        int serverSessions = realm.admin().users().get(userId).getUserSessions().size();

        String summary = "rounds=" + ROUNDS + ", parallel=" + PARALLEL_REQUESTS + ", roundsWithReuse=" + roundsWithReuse
                + ", maxSessionsForOneCode=" + maxSessionsForOneCode + ", distinctSessions=" + distinctSessions
                + ", serverSessions=" + serverSessions + ", statuses=" + statuses;
        System.out.println("HOTP-RACE-RESULT " + summary);
        assertEquals(distinctSessions, serverSessions, "回應中的 session 必須都真實存在於伺服器：" + summary);
        assertEquals(0, roundsWithReuse, "同一個 code 不得核發多個 session；" + summary);
    }

    private static HttpResponse<String> send(HttpClient http, URI tokenEndpoint, String code) throws Exception {
        return http.send(HttpRequest.newBuilder(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of(
                        "grant_type", "password",
                        "client_id", CLIENT_ID,
                        "client_secret", CLIENT_SECRET,
                        "username", USERNAME,
                        "password", PASSWORD,
                        "otp", code))))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<HttpResponse<String>> sendConcurrently(HttpClient http, ExecutorService executor,
                                                               URI tokenEndpoint, String code) throws Exception {
        CyclicBarrier start = new CyclicBarrier(PARALLEL_REQUESTS);
        Callable<HttpResponse<String>> call = () -> {
            start.await(30, TimeUnit.SECONDS);
            return send(http, tokenEndpoint, code);
        };

        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < PARALLEL_REQUESTS; i++) {
            futures.add(executor.submit(call));
        }
        List<HttpResponse<String>> responses = new ArrayList<>();
        for (Future<HttpResponse<String>> future : futures) {
            responses.add(future.get(60, TimeUnit.SECONDS));
        }
        return responses;
    }

    private static String form(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b)
                .orElseThrow();
    }
}
