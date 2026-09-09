package com.nexxserve.cavgomain;

import com.nexxserve.cavgomain.security.NexxauthConfigValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class CavgomainApplicationTests {

    // Replaced with a mock so the live Nexxauth reachability check is skipped in tests.
    @MockitoBean
    private NexxauthConfigValidator nexxauthConfigValidator;

    @Test
    void contextLoads() {
    }

}
