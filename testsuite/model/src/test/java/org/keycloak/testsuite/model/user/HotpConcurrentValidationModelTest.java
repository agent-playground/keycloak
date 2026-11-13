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

package org.keycloak.testsuite.model.user;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.credential.CredentialModel;
import org.keycloak.credential.OTPCredentialProvider;
import org.keycloak.models.AbstractKeycloakTransaction;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OTPPolicy;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.SubjectCredentialManager;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.cache.CachedUserModel;
import org.keycloak.models.cache.UserCache;
import org.keycloak.models.credential.OTPCredentialModel;
import org.keycloak.models.credential.dto.OTPCredentialData;
import org.keycloak.models.jpa.entities.CredentialEntity;
import org.keycloak.models.utils.HmacOTP;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.UserModelDelegate;
import org.keycloak.testsuite.model.KeycloakModelTest;
import org.keycloak.testsuite.model.RequireProvider;
import org.keycloak.util.JsonSerialization;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/**
 * CVE-2026-118030（https://github.com/keycloak/keycloak/issues/54414）的真實儲存層重現。
 *
 * <p>與 {@code HOTPConcurrentValidationTest}（services 模組的 stub 測試）不同，這裡使用真實的
 * {@link OTPCredentialProvider}、Infinispan 使用者快取、JPA 與資料庫，也會真的 commit 交易。
 * 測試只替換 {@code credentialManager()} 的外層，以便在「讀取 snapshot」與「寫回 counter」之間
 * 暫停；被包裝的快取與儲存邏輯都不變。</p>
 *
 * <p>{@code repro*} 是 characterization test，斷言目前有弱點的行為。修補後這些測試應該失敗，
 * 屆時改為斷言「同一個 code 只能成功一次」。</p>
 *
 * <p>執行方式：
 * {@code ./mvnw -pl testsuite/model test -Pjpa+infinispan -Dtest=HotpConcurrentValidationModelTest}。
 * 需要快取的情境在 {@code -Pjpa} 下會被 skip。</p>
 */
@RequireProvider(UserProvider.class)
@RequireProvider(RealmProvider.class)
public class HotpConcurrentValidationModelTest extends KeycloakModelTest {

    private static final String SECRET = "12345678901234567890";
    private static final int DIGITS = 6;
    private static final int LOOK_AHEAD_WINDOW = 1;
    private static final int TIMEOUT_SECONDS = 30;
    private static final HmacOTP TOKENS = new HmacOTP(DIGITS, HmacOTP.HMAC_SHA1, 0);

    private String realmId;
    private String userId;
    private String credentialId;
    private ExecutorService executor;

    @Override
    protected boolean isUseSameKeycloakSessionFactoryForAllThreads() {
        return true;
    }

    @Override
    public void createEnvironment(KeycloakSession s) {
        RealmModel realm = createRealm(s, "hotp-realm");
        s.getContext().setRealm(realm);
        realm.setDefaultRole(s.roles().addRealmRole(realm, Constants.DEFAULT_ROLES_ROLE_PREFIX + "-" + realm.getName()));
        realm.setOTPPolicy(new OTPPolicy(OTPCredentialModel.HOTP, HmacOTP.HMAC_SHA1, 0, DIGITS, LOOK_AHEAD_WINDOW, 0));
        realmId = realm.getId();

        UserModel user = s.users().addUser(realm, "hotp-user");
        userId = user.getId();
        credentialId = user.credentialManager()
                .createStoredCredential(OTPCredentialModel.createHOTP(SECRET, DIGITS, 0, HmacOTP.HMAC_SHA1))
                .getId();

        executor = Executors.newFixedThreadPool(2);
    }

    @Override
    public void cleanEnvironment(KeycloakSession s) {
        if (executor != null) {
            executor.shutdownNow();
        }
        RealmModel realm = s.realms().getRealm(realmId);
        s.getContext().setRealm(realm);
        s.realms().removeRealm(realmId);
    }

    /** 對照組：循序重送同一個 code 會被拒絕，證明這個 harness 看得出正確行為。 */
    @Test
    public void sequentialReuseOfSameCodeIsRejected() {
        warmUserCache();

        Attempt first = validate(1, Hooks.none());
        Attempt second = validate(1, Hooks.none());

        Assert.assertTrue(first.toString(), first.accepted());
        Assert.assertFalse(second.toString(), second.valid);
        Assert.assertEquals(2, persistedCounter());
    }

    /**
     * 對應 {@code specs/quint/traces/inv1-default.itf.json}：B 從快取讀到 snapshot 0，A 完整
     * commit，B 才繼續。B 的 {@code em.find} 拿到 A commit 後的 version，所以不會有版本衝突。
     */
    @Test
    public void reproSameCodeAcceptedTwiceWhenWriterCommitsDuringValidation() throws Exception {
        assumeUserCacheEnabled();
        warmUserCache();

        CountDownLatch bHasRead = new CountDownLatch(1);
        CountDownLatch aFinished = new CountDownLatch(1);

        Future<Attempt> b = executor.submit(() -> validate(1, Hooks.none().afterRead(() -> {
            bHasRead.countDown();
            await(aFinished);
        })));
        await(bHasRead);
        Attempt a = validate(1, Hooks.none());
        aFinished.countDown();
        Attempt stale = get(b);

        Assert.assertTrue("B 必須走快取路徑：" + stale, stale.servedFromCache);
        Assert.assertEquals("B 必須持有舊 snapshot：" + stale, 0, stale.snapshot);
        Assert.assertTrue("characterization：A 成功 " + a, a.accepted());
        Assert.assertTrue("characterization：B 也以同一個 code 成功 " + stale, stale.accepted());
        Assert.assertEquals("兩次成功，counter 只前進一次", 2, persistedCounter());
    }

    /**
     * B 自身完全不暫停：A 的 JPA 已 commit，但快取失效要到 {@code afterCompletion} 才執行。
     * B 在這段空窗讀到舊 snapshot，因此不需要人為拉長 B 的讀寫間隔。
     */
    @Test
    public void reproSameCodeAcceptedTwiceInWindowBeforeCacheInvalidation() throws Exception {
        assumeUserCacheEnabled();
        warmUserCache();

        CountDownLatch aDbCommitted = new CountDownLatch(1);
        CountDownLatch bFinished = new CountDownLatch(1);

        Future<Attempt> a = executor.submit(() -> validate(1, Hooks.none().onTransactionStart(session ->
                // 在 UserCacheSession 建立前 enlist，確保這個 hook 排在快取失效之前
                session.getTransactionManager().enlistAfterCompletion(new AbstractKeycloakTransaction() {
                    @Override
                    protected void commitImpl() {
                        aDbCommitted.countDown();
                        await(bFinished);
                    }

                    @Override
                    protected void rollbackImpl() {
                        aDbCommitted.countDown();
                    }
                }))));
        await(aDbCommitted);

        int counterDuringWindow = persistedCounter();
        Attempt stale = validate(1, Hooks.none());
        bFinished.countDown();
        Attempt first = get(a);

        Assert.assertEquals("B 開始前，A 必須已寫入資料庫", 2, counterDuringWindow);
        Assert.assertTrue("B 必須走快取路徑：" + stale, stale.servedFromCache);
        Assert.assertEquals("資料庫已是 2，B 仍讀到快取裡的 0：" + stale, 0, stale.snapshot);
        Assert.assertTrue("characterization：A 成功 " + first, first.accepted());
        Assert.assertTrue("characterization：B 也以同一個 code 成功 " + stale, stale.accepted());
        Assert.assertEquals(2, persistedCounter());
    }

    /**
     * 兩邊都在對方 commit 前完成 {@code em.find}：{@code @Version} 會擋下後 commit 者，
     * 即使它的 {@code isValid} 已回傳 true，交易也會失敗。
     */
    @Test
    public void versionColumnAbortsSecondCommitWhenBothLoadedSameVersion() throws Exception {
        warmUserCache();

        CountDownLatch aValidated = new CountDownLatch(1);
        CountDownLatch bValidated = new CountDownLatch(1);
        CountDownLatch aFinished = new CountDownLatch(1);

        Future<Attempt> a = executor.submit(() -> {
            try {
                return validate(1, Hooks.none().beforeCommit(() -> {
                    aValidated.countDown();
                    await(bValidated);
                }));
            } finally {
                aFinished.countDown();
            }
        });
        Future<Attempt> b = executor.submit(() -> validate(1, Hooks.none().beforeCommit(() -> {
            await(aValidated);
            bValidated.countDown();
            await(aFinished);
        })));

        Attempt first = get(a);
        Attempt second = get(b);

        Assert.assertTrue(first.toString(), first.accepted());
        Assert.assertTrue("isValid 已回傳 true：" + second, second.valid);
        Assert.assertFalse("後 commit 者必須失敗：" + second, second.committed);
        Assert.assertEquals(2, persistedCounter());
    }

    /**
     * 快取未命中（或未啟用快取）時，讀取會把 entity 載入 persistence context，version 從讀取時
     * 就固定下來，因此與第一個 repro 相同的交錯會被 {@code @Version} 擋下。
     */
    @Test
    public void versionColumnAbortsStaleWriterWhenReadLoadedEntity() throws Exception {
        evictUser();

        CountDownLatch bHasRead = new CountDownLatch(1);
        CountDownLatch aFinished = new CountDownLatch(1);

        Future<Attempt> b = executor.submit(() -> validate(1, Hooks.none().afterRead(() -> {
            bHasRead.countDown();
            await(aFinished);
        })));
        await(bHasRead);
        Attempt a = validate(1, Hooks.none());
        aFinished.countDown();
        Attempt stale = get(b);

        Assert.assertEquals("B 必須持有舊 snapshot：" + stale, 0, stale.snapshot);
        Assert.assertTrue(a.toString(), a.accepted());
        Assert.assertTrue("isValid 已回傳 true：" + stale, stale.valid);
        Assert.assertFalse("B 的 commit 必須失敗：" + stale, stale.committed);
        Assert.assertEquals(2, persistedCounter());
    }

    /**
     * 對應 {@code specs/quint/traces/inv3-default.itf.json} 並往後多走一步：counter 被舊 snapshot
     * 寫回 1 之後，已經用過的 counter 1 code 可以再次循序登入。
     */
    @Test
    public void reproCounterRollbackAllowsLaterReplayOfUsedCode() throws Exception {
        assumeUserCacheEnabled();
        warmUserCache();

        CountDownLatch bHasRead = new CountDownLatch(1);
        CountDownLatch aFinished = new CountDownLatch(1);

        Future<Attempt> b = executor.submit(() -> validate(0, Hooks.none().afterRead(() -> {
            bHasRead.countDown();
            await(aFinished);
        })));
        await(bHasRead);
        Attempt a = validate(1, Hooks.none());
        int counterAfterA = persistedCounter();
        aFinished.countDown();
        Attempt stale = get(b);
        int counterAfterStale = persistedCounter();
        Attempt replay = validate(1, Hooks.none());

        Assert.assertTrue(a.toString(), a.accepted());
        Assert.assertTrue(stale.toString(), stale.accepted());
        Assert.assertEquals(2, counterAfterA);
        Assert.assertEquals("characterization：counter 倒退", 1, counterAfterStale);
        Assert.assertTrue("characterization：用過的 code 可重放 " + replay, replay.accepted());
    }

    private void assumeUserCacheEnabled() {
        Assume.assumeTrue("需要 Infinispan 使用者快取（-Pjpa+infinispan）",
                inComittedTransaction(session -> session.getProvider(UserCache.class) != null));
    }

    /** 讓使用者與憑證清單都進入快取；第一次讀取只建立快取項目，所以讀兩次。 */
    private void warmUserCache() {
        for (int i = 0; i < 2; i++) {
            inComittedTransaction(session -> {
                RealmModel realm = session.realms().getRealm(realmId);
                session.getContext().setRealm(realm);
                session.users().getUserById(realm, userId).credentialManager().getStoredCredentialsStream().count();
            });
        }
    }

    private void evictUser() {
        inComittedTransaction(session -> {
            UserCache cache = session.getProvider(UserCache.class);
            if (cache != null) {
                RealmModel realm = session.realms().getRealm(realmId);
                session.getContext().setRealm(realm);
                cache.evict(realm, session.users().getUserById(realm, userId));
            }
        });
    }

    /** 直接查資料庫，不經過使用者快取。 */
    private int persistedCounter() {
        return inComittedTransaction(session -> {
            CredentialEntity entity = session.getProvider(JpaConnectionProvider.class).getEntityManager()
                    .find(CredentialEntity.class, credentialId);
            try {
                return JsonSerialization.readValue(entity.getCredentialData(), OTPCredentialData.class).getCounter();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** 以一筆獨立交易執行真實的 isValid；交易的 commit 發生在 session 關閉時。 */
    private Attempt validate(int tokenCounter, Hooks hooks) {
        Attempt attempt = new Attempt();
        try {
            KeycloakModelUtils.runJobInTransactionWithResult(getFactory(), session -> {
                hooks.onTransactionStart.accept(session);
                RealmModel realm = session.realms().getRealm(realmId);
                session.getContext().setRealm(realm);
                UserModel user = session.users().getUserById(realm, userId);
                attempt.servedFromCache = user instanceof CachedUserModel;

                UserCredentialModel input = new UserCredentialModel(credentialId, OTPCredentialModel.HOTP,
                        TOKENS.generateHOTP(SECRET, tokenCounter));
                attempt.valid = new OTPCredentialProvider(session)
                        .isValid(realm, observing(user, attempt, hooks.afterRead), input);
                hooks.beforeCommit.run();
                return null;
            });
            attempt.committed = true;
        } catch (RuntimeException | AssertionError e) {
            attempt.failure = e;
        }
        return attempt;
    }

    /** 包住真實的 credential manager，記錄讀到的 snapshot 並在讀取後執行 hook；其餘呼叫原樣轉送。 */
    private static UserModel observing(UserModel user, Attempt attempt, Runnable afterRead) {
        return new UserModelDelegate(user) {
            @Override
            public SubjectCredentialManager credentialManager() {
                SubjectCredentialManager real = user.credentialManager();
                return (SubjectCredentialManager) Proxy.newProxyInstance(
                        SubjectCredentialManager.class.getClassLoader(),
                        new Class<?>[]{SubjectCredentialManager.class},
                        (proxy, method, args) -> {
                            Object result;
                            try {
                                result = method.invoke(real, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                            if ("getStoredCredentialById".equals(method.getName())) {
                                attempt.snapshot = OTPCredentialModel.createFromCredentialModel((CredentialModel) result)
                                        .getOTPCredentialData().getCounter();
                                afterRead.run();
                            }
                            return result;
                        });
            }
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("等待並行請求逾時");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static Attempt get(Future<Attempt> future) throws Exception {
        return future.get(2L * TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static final class Hooks {
        private Consumer<KeycloakSession> onTransactionStart = session -> { };
        private Runnable afterRead = () -> { };
        private Runnable beforeCommit = () -> { };

        static Hooks none() {
            return new Hooks();
        }

        Hooks onTransactionStart(Consumer<KeycloakSession> hook) {
            onTransactionStart = hook;
            return this;
        }

        Hooks afterRead(Runnable hook) {
            afterRead = hook;
            return this;
        }

        Hooks beforeCommit(Runnable hook) {
            beforeCommit = hook;
            return this;
        }
    }

    private static final class Attempt {
        private boolean servedFromCache;
        private int snapshot = -1;
        private boolean valid;
        private boolean committed;
        private Throwable failure;

        /** 請求真正成功：isValid 回傳 true，而且交易 commit 成功。 */
        boolean accepted() {
            return valid && committed;
        }

        @Override
        public String toString() {
            return "Attempt{servedFromCache=" + servedFromCache + ", snapshot=" + snapshot + ", valid=" + valid
                    + ", committed=" + committed + ", failure=" + failure + "}";
        }
    }
}
