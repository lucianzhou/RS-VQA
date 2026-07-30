package com.rsvqa.gateway;

import java.util.UUID;

interface ProviderAdmissionStore {

    Admission acquire(
            UUID userId,
            String providerId,
            ProviderWorkload workload,
            int units,
            int tokenReservation,
            ProviderPolicyProperties.Limits limits
    );

    interface Admission {
        void complete(Integer actualTokens);
    }
}
