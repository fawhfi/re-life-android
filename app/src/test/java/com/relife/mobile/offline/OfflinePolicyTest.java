package com.relife.mobile.offline;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OfflinePolicyTest {
    @Test
    public void onlyIdempotentOrLocallyIdentifiedMutationsCanBeQueued() {
        assertTrue(OfflinePolicy.canQueue("POST", "/api/records"));
        assertTrue(OfflinePolicy.canQueue("PATCH", "/api/users/me"));
        assertTrue(OfflinePolicy.canQueue("DELETE", "/api/records/record-42"));

        assertFalse(OfflinePolicy.canQueue("POST", "/api/auth/login"));
        assertFalse(OfflinePolicy.canQueue("POST", "/api/rewards/redeem"));
        assertFalse(OfflinePolicy.canQueue("POST", "/api/agent/messages"));
    }

    @Test
    public void retriesOnlyTemporaryFailures() {
        assertTrue(OfflinePolicy.shouldRetry(408));
        assertTrue(OfflinePolicy.shouldRetry(429));
        assertTrue(OfflinePolicy.shouldRetry(503));
        assertFalse(OfflinePolicy.shouldRetry(400));
        assertFalse(OfflinePolicy.shouldRetry(401));
        assertFalse(OfflinePolicy.shouldRetry(404));
    }
}
