package com.jobfinder.core.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jobfinder.core.identity.AuthTestSupport;

class AdminSeederTests extends AuthTestSupport {

    @Autowired
    private AdminSeeder seeder;

    @Test
    void createsAVerifiedAdminWhoCanLogIn() throws Exception {
        String email = newEmail();

        seeder.seed(email.toUpperCase(), PASSWORD);

        assertThat(count("select count(*) from users where email = ? and role = 'ADMIN' "
                + "and email_verified_at is not null", email)).isEqualTo(1);
        Session session = login(email, PASSWORD, newIp());
        getMe(session.accessToken()).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void isIdempotent() {
        String email = newEmail();

        seeder.seed(email, PASSWORD);
        seeder.seed(email, PASSWORD);

        assertThat(count("select count(*) from users where email = ?", email)).isEqualTo(1);
    }

    @Test
    void promotesAnExistingUserWithoutChangingTheirPassword() throws Exception {
        String email = registerVerifiedUser();

        seeder.seed(email, "some-other-password");

        assertThat(count("select count(*) from users where email = ? and role = 'ADMIN'", email)).isEqualTo(1);
        login(email, PASSWORD, newIp());
    }

    @Test
    void doesNothingWithoutAnEmailAndDoesNotCreateAPasswordlessAdmin() {
        String email = newEmail();

        seeder.seed(null, PASSWORD);
        seeder.seed("  ", PASSWORD);
        seeder.seed(email, null);

        assertThat(count("select count(*) from users where email = ?", email)).isZero();
    }
}
