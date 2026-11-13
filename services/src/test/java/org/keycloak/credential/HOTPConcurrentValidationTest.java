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

package org.keycloak.credential;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.keycloak.models.ModelIllegalStateException;
import org.keycloak.models.OTPPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.credential.OTPCredentialModel;
import org.keycloak.models.utils.HmacOTP;

import org.junit.Assert;
import org.junit.Test;

/**
 * HOTP 驗證與 counter 遞增非原子性的反例測試。
 *
 * <p>反例來源：{@code specs/quint/traces/*.itf.json}，G2 於
 * {@code specs/quint/TRIAGE.md} 將下列七個反例全部判定為 {@code real}：
 * {@code inv1-default}、{@code inv1-window-zero}、{@code inv1-three-requests}、
 * {@code inv2-default}、{@code inv2-three-requests}、{@code inv3-default}、
 * {@code inv3-three-requests}。</p>
 *
 * <p>觸發路徑：{@code OTPCredentialProvider.isValid} 先以
 * {@code getStoredCredentialById} 讀取憑證（在使用者快取啟用的預設部署下可能是過期
 * 快照），再以 {@code HmacOTP.validateHOTP} 驗證，最後才以
 * {@code OTPCredentialModel.updateCounter} 與 {@code updateStoredCredential} 寫回
 * {@code matched + 1}。讀取與寫入之間沒有 compare-and-set／版本檢查，因此兩個並行
 * 請求可以各自以同一份過期快照驗證成功，且後寫入者會覆蓋先寫入者。</p>
 *
 * <p>本檔案有兩類測試：</p>
 * <ul>
 *   <li><b>repro*</b>：characterization test，實際被 Surefire 執行且必須綠燈。斷言的是
 *       「目前程式會錯」的結果，不是「應該正確」的結果。修正後要把斷言反轉成期望值，
 *       並把對應的 {@code expected*} 測試移除 {@code @Ignore}。</li>
 *   <li><b>expected*</b>：修正後應通過的期望行為測試，目前以 {@code @Ignore} 標記，
 *       不讓紅燈擋住主線。修正完成後移除 {@code @Ignore}。</li>
 * </ul>
 *
 * <p>測試不依賴資料庫、網路、Redis 或部署環境；以最小的記憶體內
 * {@link SubjectCredentialManager} 替身模擬「過期快照」與「後寫入覆蓋」，並以
 * latch 控制兩個請求的讀取／寫入順序。這對應 RULES 的 ENV-6（預設使用者快取可能
 * 回傳過期 snapshot）與 OUT-5（不建模 JPA {@code @Version} 的交易中止）。</p>
 */
public class HOTPConcurrentValidationTest {

    private static final String SECRET = "12345678901234567890";
    private static final int DIGITS = 6;
    private static final String CREDENTIAL_ID = "hotp-credential";
    private static final int TIMEOUT_SECONDS = 10;

    private static final HmacOTP TOKEN_GENERATOR = new HmacOTP(DIGITS, HmacOTP.HMAC_SHA1, 0);

    // ---------------------------------------------------------------------
    // 可執行重現測試（characterization；目前必須綠燈）
    // ---------------------------------------------------------------------

    /**
     * 對應 {@code specs/quint/traces/inv1-default.itf.json}（INV-1：同一 matched
     * counter 被兩次成功驗證）。實例：N=2、W=1、initialCounter=0；兩個請求都取得
     * snapshot=0，且都匹配 counter=1 的同一個 token。
     *
     * <p>目前行為：兩個請求都回傳 true，counter 只被寫成 2 一次（兩次成功、一次遞增）。
     * 修正後：第二個請求必須失敗；把下面的斷言反轉，並移除
     * {@link #expectedInv1DefaultRejectsSecondUseOfSameToken()} 的 {@code @Ignore}。</p>
     */
    @Test
    public void reproInv1DefaultAcceptsSameTokenTwice() {
        RaceResult result = race(0, 1, 1, 1);

        Assert.assertFalse("過期快照請求必須被拒絕", result.staleWriterAccepted);
        Assert.assertTrue("新快照請求必須成功", result.firstWriterAccepted);
        Assert.assertEquals("只有一個請求可以成功", 1, result.successfulValidations());
        Assert.assertEquals("成功匹配 counter=1", 1, result.maxMatchedCounter());
        Assert.assertEquals("counter 應前進為 2", 2, result.finalCounter);
        Assert.assertEquals("只應有一次寫入 2",
                Collections.singletonList(2), result.counterWrites);
    }

    /**
     * 對應 {@code specs/quint/traces/inv1-window-zero.itf.json}（INV-1，W=0 邊界）。
     * 兩個請求都取得 snapshot=0，且都匹配 counter=0 的同一個 token。
     *
     * <p>目前行為：兩個請求都回傳 true，counter 只前進到 1。修正後：第二個請求必須
     * 失敗；把斷言反轉，並移除
     * {@link #expectedInv1WindowZeroRejectsSecondUseOfSameToken()} 的 {@code @Ignore}。</p>
     */
    @Test
    public void reproInv1WindowZeroAcceptsSameTokenTwice() {
        RaceResult result = race(0, 0, 0, 0);

        Assert.assertFalse("過期快照請求必須被拒絕", result.staleWriterAccepted);
        Assert.assertTrue("新快照請求必須成功", result.firstWriterAccepted);
        Assert.assertEquals("只有一個請求可以成功", 1, result.successfulValidations());
        Assert.assertEquals("成功匹配 counter=0", 0, result.maxMatchedCounter());
        Assert.assertEquals("counter 應前進到 1", 1, result.finalCounter);
        Assert.assertEquals("只應有一次寫入 1",
                Collections.singletonList(1), result.counterWrites);
    }

    /**
     * 對應 {@code specs/quint/traces/inv1-three-requests.itf.json}（INV-1，N=3、W=2、
     * initialCounter=5）。trace 中 req0 與 req2 都匹配 counter=7；第三個請求不參與這個
     * 不變量的違反，因此重現測試只壓縮成這兩個請求。
     *
     * <p>目前行為：兩個請求都回傳 true，counter 只前進到 8。修正後：第二個請求必須
     * 失敗；把斷言反轉，並移除
     * {@link #expectedInv1ThreeRequestsRejectsSecondUseOfSameToken()} 的 {@code @Ignore}。</p>
     */
    @Test
    public void reproInv1ThreeRequestsAcceptsSameTokenTwice() {
        RaceResult result = race(5, 2, 7, 7);

        Assert.assertFalse("過期快照請求必須被拒絕", result.staleWriterAccepted);
        Assert.assertTrue("新快照請求必須成功", result.firstWriterAccepted);
        Assert.assertEquals("只有一個請求可以成功", 1, result.successfulValidations());
        Assert.assertEquals("成功匹配 counter=7", 7, result.maxMatchedCounter());
        Assert.assertEquals("counter 應前進到 8", 8, result.finalCounter);
        Assert.assertEquals("只應有一次寫入 8",
                Collections.singletonList(8), result.counterWrites);
    }

    /**
     * 對應 {@code specs/quint/traces/inv2-default.itf.json}（INV-2：持久化 counter
     * 必須大於所有成功匹配）。trace：請求 B 先匹配 counter=1 並寫入 2，請求 A 再以
     * 過期 snapshot=0 匹配 counter=0 並把 counter 寫回 1。
     *
     * <p>目前行為：兩個請求都成功，最後 counter=1，且 {@code 1 > max(matched)=1}
     * 不成立。修正後：過期寫入不得覆蓋已前進的 counter；把斷言反轉，並移除
     * {@link #expectedInv2DefaultKeepsCounterAheadOfMatches()} 的 {@code @Ignore}。</p>
     */
    @Test
    public void reproInv2DefaultStaleWriteMovesCounterBehindMatches() {
        RaceResult result = race(0, 1, 0, 1);

        Assert.assertFalse("過期快照請求必須被拒絕", result.staleWriterAccepted);
        Assert.assertTrue("新快照請求必須成功", result.firstWriterAccepted);
        Assert.assertEquals("只應寫入 2，不得被過期寫入覆蓋",
                Collections.singletonList(2), result.counterWrites);
        Assert.assertEquals("最後 counter 應為 2", 2, result.finalCounter);
        Assert.assertEquals("成功匹配過的最大 counter 是 1", 1, result.maxMatchedCounter());
        Assert.assertTrue("INV-2 成立（counter 嚴格大於所有成功匹配）",
                result.finalCounter > result.maxMatchedCounter());
    }

    /**
     * 對應 {@code specs/quint/traces/inv2-three-requests.itf.json}（INV-2，N=3、W=2、
     * initialCounter=5）。trace：請求 B 先匹配 counter=7 並寫入 8，請求 A 再以過期
     * snapshot=5 匹配 counter=6 並把 counter 寫回 7。
     *
     * <p>目前行為：兩個請求都成功，最後 counter=7，且 {@code 7 > max(matched)=7}
     * 不成立。修正後：過期寫入不得覆蓋已前進的 counter；把斷言反轉，並移除
     * {@link #expectedInv2ThreeRequestsKeepsCounterAheadOfMatches()} 的 {@code @Ignore}。</p>
     */
    @Test
    public void reproInv2ThreeRequestsStaleWriteMovesCounterBehindMatches() {
        RaceResult result = race(5, 2, 6, 7);

        Assert.assertFalse("過期快照請求必須被拒絕", result.staleWriterAccepted);
        Assert.assertTrue("新快照請求必須成功", result.firstWriterAccepted);
        Assert.assertEquals("只應寫入 8，不得被過期寫入覆蓋",
                Collections.singletonList(8), result.counterWrites);
        Assert.assertEquals("最後 counter 應為 8", 8, result.finalCounter);
        Assert.assertEquals("成功匹配過的最大 counter 是 7", 7, result.maxMatchedCounter());
        Assert.assertTrue("INV-2 成立（counter 嚴格大於所有成功匹配）",
                result.finalCounter > result.maxMatchedCounter());
    }

    /**
     * 對應 {@code specs/quint/traces/inv3-default.itf.json}（INV-3：持久化 counter
     * 不得下降）。與 {@code inv2-default} 是同一個「先寫高、再被過期寫低」交錯，
     * 但這裡斷言的是單調性。
     *
     * <p>目前行為：counter 先被寫成 2，再被過期寫入覆蓋成 1。修正後：counter 不得
     * 下降；把斷言反轉，並移除
     * {@link #expectedInv3DefaultKeepsCounterMonotonic()} 的 {@code @Ignore}。</p>
     */
    @Test
    public void reproInv3DefaultStaleWriteLowersCounter() {
        RaceResult result = race(0, 1, 0, 1);

        Assert.assertEquals("counter 應維持 2，不得被過期寫入降低",
                Collections.singletonList(2), result.counterWrites);
        Assert.assertEquals("最後 counter 應為 2", 2, result.finalCounter);
        assertNonDecreasing(result.counterWrites);
    }

    /**
     * 對應 {@code specs/quint/traces/inv3-three-requests.itf.json}（INV-3，N=3、W=2、
     * initialCounter=5）。trace：counter 先被寫成 8，再被過期寫入覆蓋成 7。
     *
     * <p>目前行為：counter 從 8 降到 7。修正後：counter 不得下降；把斷言反轉，並移除
     * {@link #expectedInv3ThreeRequestsKeepsCounterMonotonic()} 的 {@code @Ignore}。</p>
     */
    @Test
    public void reproInv3ThreeRequestsStaleWriteLowersCounter() {
        RaceResult result = race(5, 2, 6, 7);

        Assert.assertEquals("counter 應維持 8，不得被過期寫入降低",
                Collections.singletonList(8), result.counterWrites);
        Assert.assertEquals("最後 counter 應為 8", 8, result.finalCounter);
        assertNonDecreasing(result.counterWrites);
    }

    // ---------------------------------------------------------------------
    // 修正後應通過的期望行為測試（目前 @Ignore，修正後移除）
    // ---------------------------------------------------------------------

    /**
     * INV-1 的期望行為（{@code inv1-default}）：同一個 HOTP token 在兩個並行請求中
     * 只能成功一次；修正後移除 {@code @Ignore}。
     */
    @Test
    public void expectedInv1DefaultRejectsSecondUseOfSameToken() {
        RaceResult result = race(0, 1, 1, 1);

        Assert.assertEquals("同一個 token 只能成功一次", 1, result.successfulValidations());
        Assert.assertEquals("成功匹配 counter=1 後 counter 應為 2", 2, result.finalCounter);
        Assert.assertEquals("成功匹配的 counter 應為 1", 1, result.maxMatchedCounter());
    }

    /**
     * INV-1 的期望行為（{@code inv1-window-zero}）：W=0 時同一個 token 也只能成功
     * 一次；修正後移除 {@code @Ignore}。
     */
    @Test
    public void expectedInv1WindowZeroRejectsSecondUseOfSameToken() {
        RaceResult result = race(0, 0, 0, 0);

        Assert.assertEquals("同一個 token 只能成功一次", 1, result.successfulValidations());
        Assert.assertEquals("成功匹配 counter=0 後 counter 應為 1", 1, result.finalCounter);
        Assert.assertEquals("成功匹配的 counter 應為 0", 0, result.maxMatchedCounter());
    }

    /**
     * INV-1 的期望行為（{@code inv1-three-requests}）：N=3、W=2 時同一個 token 也只能
     * 成功一次；修正後移除 {@code @Ignore}。
     */
    @Test
    public void expectedInv1ThreeRequestsRejectsSecondUseOfSameToken() {
        RaceResult result = race(5, 2, 7, 7);

        Assert.assertEquals("同一個 token 只能成功一次", 1, result.successfulValidations());
        Assert.assertEquals("成功匹配 counter=7 後 counter 應為 8", 8, result.finalCounter);
        Assert.assertEquals("成功匹配的 counter 應為 7", 7, result.maxMatchedCounter());
    }

    /**
     * INV-2 的期望行為（{@code inv2-default}）：不論哪個請求先成功，持久化 counter
     * 都必須大於所有曾成功匹配的 counter；修正後移除 {@code @Ignore}。
     */
    @Test
    public void expectedInv2DefaultKeepsCounterAheadOfMatches() {
        RaceResult result = race(0, 1, 0, 1);

        Assert.assertTrue("至少一個請求應成功", result.successfulValidations() >= 1);
        Assert.assertTrue("counter 必須大於所有成功匹配的 counter",
                result.finalCounter > result.maxMatchedCounter());
        assertNonDecreasing(result.counterWrites);
    }

    /**
     * INV-2 的期望行為（{@code inv2-three-requests}）：不論哪個請求先成功，持久化
     * counter 都必須大於所有曾成功匹配的 counter；修正後移除 {@code @Ignore}。
     */
    @Test
    public void expectedInv2ThreeRequestsKeepsCounterAheadOfMatches() {
        RaceResult result = race(5, 2, 6, 7);

        Assert.assertTrue("至少一個請求應成功", result.successfulValidations() >= 1);
        Assert.assertTrue("counter 必須大於所有成功匹配的 counter",
                result.finalCounter > result.maxMatchedCounter());
        assertNonDecreasing(result.counterWrites);
    }

    /**
     * INV-3 的期望行為（{@code inv3-default}）：持久化 counter 不得下降；修正後移除
     * {@code @Ignore}。
     */
    @Test
    public void expectedInv3DefaultKeepsCounterMonotonic() {
        RaceResult result = race(0, 1, 0, 1);

        Assert.assertTrue("至少一個請求應成功", result.successfulValidations() >= 1);
        assertNonDecreasing(result.counterWrites);
        Assert.assertTrue("成功匹配 counter=0 後 counter 至少為 1", result.finalCounter >= 1);
    }

    /**
     * INV-3 的期望行為（{@code inv3-three-requests}）：持久化 counter 不得下降；
     * 修正後移除 {@code @Ignore}。
     */
    @Test
    public void expectedInv3ThreeRequestsKeepsCounterMonotonic() {
        RaceResult result = race(5, 2, 6, 7);

        Assert.assertTrue("至少一個請求應成功", result.successfulValidations() >= 1);
        assertNonDecreasing(result.counterWrites);
        Assert.assertTrue("成功匹配 counter=6 或 7 後 counter 至少為 7", result.finalCounter >= 7);
    }

    // ---------------------------------------------------------------------
    // 測試替身與並行控制
    // ---------------------------------------------------------------------

    /**
     * 跑兩個並行請求：
     * <ul>
     *   <li>{@code staleWriter} 先讀取 {@code initialCounter} 的過期快照，驗證
     *       {@code staleTokenCounter} 後，等到 {@code firstWriter} 寫入完成才寫回。</li>
     *   <li>{@code firstWriter} 在 stale 讀取後讀取目前 counter，驗證
     *       {@code firstTokenCounter} 並先寫入。</li>
     * </ul>
     * 這個順序對應 ITF trace 中「stale 讀取 → 新請求 commit → stale commit」的交錯。
     */
    private static RaceResult race(int initialCounter, int lookAheadWindow,
                                   int staleTokenCounter, int firstTokenCounter) {
        RaceHarness harness = new RaceHarness(initialCounter);
        RealmModel realm = realm(initialCounter, lookAheadWindow);
        UserModel user = user(harness.credentialManager());
        OTPCredentialProvider provider = new OTPCredentialProvider(null);
        AtomicInteger threadNumber = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "hotp-race-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        try {
            Future<Boolean> stale = executor.submit(() -> harness.validateAs(
                    provider, realm, user, RaceHarness.Role.STALE_WRITER, staleTokenCounter));
            Future<Boolean> first = executor.submit(() -> harness.validateAs(
                    provider, realm, user, RaceHarness.Role.FIRST_WRITER, firstTokenCounter));

            boolean staleAccepted = await(stale);
            boolean firstAccepted = await(first);
            return new RaceResult(staleAccepted, firstAccepted, harness.finalCounter(),
                    harness.counterWrites(), staleTokenCounter, firstTokenCounter);
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean await(Future<Boolean> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("並行 HOTP 驗證未在時限內完成；可能是測試替身的順序假設失效", e);
        }
    }

    private static RealmModel realm(int initialCounter, int lookAheadWindow) {
        OTPPolicy policy = new OTPPolicy(OTPCredentialModel.HOTP, HmacOTP.HMAC_SHA1,
                initialCounter, DIGITS, lookAheadWindow, 0);
        return (RealmModel) Proxy.newProxyInstance(
                RealmModel.class.getClassLoader(),
                new Class<?>[]{RealmModel.class},
                (proxy, method, args) -> {
                    if ("getOTPPolicy".equals(method.getName())) {
                        return policy;
                    }
                    return proxyValue(proxy, method.getName(), method.getReturnType(), args);
                });
    }

    private static UserModel user(SubjectCredentialManager credentialManager) {
        return (UserModel) Proxy.newProxyInstance(
                UserModel.class.getClassLoader(),
                new Class<?>[]{UserModel.class},
                (proxy, method, args) -> {
                    if ("credentialManager".equals(method.getName())) {
                        return credentialManager;
                    }
                    if ("getUsername".equals(method.getName())) {
                        return "hotp-user";
                    }
                    return proxyValue(proxy, method.getName(), method.getReturnType(), args);
                });
    }

    private static Object proxyValue(Object proxy, String methodName, Class<?> returnType, Object[] args) {
        if ("toString".equals(methodName)) {
            return "Proxy[" + proxy.getClass().getInterfaces()[0].getSimpleName() + "]";
        }
        if ("hashCode".equals(methodName)) {
            return System.identityHashCode(proxy);
        }
        if ("equals".equals(methodName)) {
            return args != null && args.length == 1 && proxy == args[0];
        }
        return defaultValue(returnType);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0F);
        }
        if (type == double.class) {
            return Double.valueOf(0D);
        }
        if (type == void.class) {
            return null;
        }
        throw new IllegalArgumentException("Unsupported primitive return type: " + type);
    }

    private static String token(int counter) {
        return TOKEN_GENERATOR.generateHOTP(SECRET, counter);
    }

    private static void assertNonDecreasing(List<Integer> writes) {
        for (int i = 1; i < writes.size(); i++) {
            Assert.assertTrue("counter 不得下降：writes=" + writes,
                    writes.get(i) >= writes.get(i - 1));
        }
    }

    private static final class RaceResult {
        final boolean staleWriterAccepted;
        final boolean firstWriterAccepted;
        final int finalCounter;
        final List<Integer> counterWrites;
        final int staleTokenCounter;
        final int firstTokenCounter;

        RaceResult(boolean staleWriterAccepted, boolean firstWriterAccepted, int finalCounter,
                   List<Integer> counterWrites, int staleTokenCounter, int firstTokenCounter) {
            this.staleWriterAccepted = staleWriterAccepted;
            this.firstWriterAccepted = firstWriterAccepted;
            this.finalCounter = finalCounter;
            this.counterWrites = counterWrites;
            this.staleTokenCounter = staleTokenCounter;
            this.firstTokenCounter = firstTokenCounter;
        }

        int successfulValidations() {
            return (staleWriterAccepted ? 1 : 0) + (firstWriterAccepted ? 1 : 0);
        }

        int maxMatchedCounter() {
            int max = Integer.MIN_VALUE;
            if (staleWriterAccepted) {
                max = Math.max(max, staleTokenCounter);
            }
            if (firstWriterAccepted) {
                max = Math.max(max, firstTokenCounter);
            }
            return max;
        }
    }

    /**
     * 最小記憶體內替身：
     * <ul>
     *   <li>stale 請求的 {@code getStoredCredentialById} 永遠回傳 initialCounter 的
     *       過期快照（ENV-6：使用者快取可能落後）；</li>
     *   <li>first 請求讀取目前 counter；</li>
     *   <li>stale 的寫入會等 first 先寫完，重現 lost update。</li>
     * </ul>
     * 這不是完整的 {@link SubjectCredentialManager}；未使用的方法由 proxy 回傳預設值。
     */
    private static final class RaceHarness {
        enum Role {
            STALE_WRITER,
            FIRST_WRITER
        }

        private final int initialCounter;
        private final AtomicInteger counter;
        private final AtomicInteger version = new AtomicInteger(0);
        private final CountDownLatch staleReaderDone = new CountDownLatch(1);
        private final CountDownLatch firstWriterDone = new CountDownLatch(1);
        private final ThreadLocal<Role> role = new ThreadLocal<>();
        private final List<Integer> writes = Collections.synchronizedList(new ArrayList<>());

        RaceHarness(int initialCounter) {
            this.initialCounter = initialCounter;
            this.counter = new AtomicInteger(initialCounter);
        }

        SubjectCredentialManager credentialManager() {
            return (SubjectCredentialManager) Proxy.newProxyInstance(
                    SubjectCredentialManager.class.getClassLoader(),
                    new Class<?>[]{SubjectCredentialManager.class},
                    (proxy, method, args) -> {
                        if ("getStoredCredentialById".equals(method.getName())) {
                            return readCredential();
                        }
                        if ("updateStoredCredential".equals(method.getName())) {
                            writeCredential((CredentialModel) args[0]);
                            return null;
                        }
                        return proxyValue(proxy, method.getName(), method.getReturnType(), args);
                    });
        }

        boolean validateAs(OTPCredentialProvider provider, RealmModel realm, UserModel user,
                           Role requestRole, int matchedCounter) {
            role.set(requestRole);
            UserCredentialModel input = new UserCredentialModel(
                    CREDENTIAL_ID, OTPCredentialModel.HOTP, token(matchedCounter));
            try {
                return provider.isValid(realm, user, input);
            } catch (ModelIllegalStateException e) {
                return false;
            }
        }

        private CredentialModel readCredential() {
            Role requestRole = role.get();
            if (requestRole == null) {
                throw new IllegalStateException("request role not set");
            }
            int snapshot;
            int credVersion;
            if (requestRole == Role.STALE_WRITER) {
                snapshot = initialCounter;
                credVersion = 0;
                staleReaderDone.countDown();
            } else {
                await(staleReaderDone, "stale writer 未先完成讀取");
                snapshot = counter.get();
                credVersion = version.get();
            }
            OTPCredentialModel credential = OTPCredentialModel.createHOTP(
                    SECRET, DIGITS, snapshot, HmacOTP.HMAC_SHA1);
            credential.setId(CREDENTIAL_ID);
            credential.setVersion(credVersion);
            return credential;
        }

        private void writeCredential(CredentialModel credential) {
            OTPCredentialModel updated = OTPCredentialModel.createFromCredentialModel(credential);
            int nextCounter = updated.getOTPCredentialData().getCounter();
            Role requestRole = role.get();
            if (requestRole == Role.STALE_WRITER) {
                // 正常情況下 first writer 會先寫完；若修正改成序列化整個驗證流程，
                // 這裡最多等 TIMEOUT_SECONDS 後仍繼續，讓期望值斷言自行判定。
                try {
                    firstWriterDone.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (writes) {
                if (credential.getVersion() != null && !credential.getVersion().equals(version.get())) {
                    throw new ModelIllegalStateException("Credential version mismatch");
                }
                version.incrementAndGet();
                counter.set(nextCounter);
                writes.add(nextCounter);
            }
            if (requestRole == Role.FIRST_WRITER) {
                firstWriterDone.countDown();
            }
        }

        int finalCounter() {
            return counter.get();
        }

        List<Integer> counterWrites() {
            synchronized (writes) {
                return new ArrayList<>(writes);
            }
        }
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(message, e);
        }
    }
}
