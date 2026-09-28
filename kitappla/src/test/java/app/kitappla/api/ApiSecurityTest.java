package app.kitappla.api;

import app.kitappla.domain.User;
import app.kitappla.repo.UserRepository;
import app.kitappla.security.AppUserDetails;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    private User normalUser;
    private User adminUser;

    @BeforeEach
    void setup() {
        normalUser = new User();
        normalUser.setName("Normal Üye");
        normalUser.setEmail("norm-" + UUID.randomUUID() + "@test.local");
        normalUser.setPasswordHash(encoder.encode("password"));
        normalUser.setAdmin(false);
        normalUser = users.save(normalUser);

        adminUser = new User();
        adminUser.setName("Admin Üye");
        adminUser.setEmail("admin-" + UUID.randomUUID() + "@test.local");
        adminUser.setPasswordHash(encoder.encode("password"));
        adminUser.setAdmin(true);
        adminUser = users.save(adminUser);
    }

    private AppUserDetails as(User u) {
        return new AppUserDetails(u);
    }

    @Test
    void unauthenticated_request_to_protected_api_returns_401() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/my/donations"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"login", "register", "logout", "forgot-password", "reset-password"})
    void public_auth_mutations_require_csrf(String endpoint) throws Exception {
        mvc.perform(post("/api/v1/auth/" + endpoint)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/" + endpoint)
                        .header("X-CSRF-TOKEN", "invalid-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void authenticated_unsafe_methods_require_csrf_even_without_a_body(String method) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), "/api/v1/notifications/read-all")
                        .with(user(as(normalUser))))
                .andExpect(status().isForbidden());
        mvc.perform(request(HttpMethod.valueOf(method), "/api/v1/notifications/read-all")
                        .with(user(as(normalUser))).header("X-CSRF-TOKEN", "invalid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void public_csrf_token_is_session_bound_and_supports_headers_and_parameters() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).isNotNull();
        JsonNode token = mapper.readTree(result.getResponse().getContentAsString());

        mvc.perform(post("/api/v1/notifications/read-all").session(new MockHttpSession())
                        .with(user(as(normalUser)))
                        .header(token.get("headerName").asText(), token.get("token").asText()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/notifications/read-all").session(session)
                        .header(token.get("headerName").asText(), token.get("token").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/read-all").session(session)
                        .with(user(as(normalUser)))
                        .header(token.get("headerName").asText(), token.get("token").asText()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/notifications/read-all").session(session)
                        .param(token.get("parameterName").asText(), token.get("token").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void web_login_csrf_and_api_session_compatibility_are_preserved() throws Exception {
        mvc.perform(post("/login").param("email", normalUser.getEmail()).param("password", "password"))
                .andExpect(status().isForbidden());
        MvcResult result = mvc.perform(get("/login")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        CsrfToken token = (CsrfToken) result.getRequest().getAttribute("_csrf");
        mvc.perform(post("/login").session(session)
                        .param(token.getParameterName(), token.getToken())
                        .param("email", normalUser.getEmail()).param("password", "password"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/notifications/read-all").session(session)
                        .header(token.getHeaderName(), token.getToken()))
                .andExpect(status().isForbidden());
        JsonNode refreshed = mapper.readTree(mvc.perform(get("/api/v1/auth/csrf").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/notifications/read-all").session(session)
                        .header(refreshed.get("headerName").asText(), refreshed.get("token").asText()))
                .andExpect(status().isOk());
    }

    @Test
    void admin_endpoint_forbidden_for_normal_user_returns_403() throws Exception {
        mvc.perform(get("/api/v1/admin/stats").with(user(as(normalUser))))
                .andExpect(status().isForbidden());
    }

    @Test
    void admin_endpoint_accessible_for_admin_user_returns_200() throws Exception {
        mvc.perform(get("/api/v1/admin/stats").with(user(as(adminUser))))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticated_controller_check_returns_401() throws Exception {
        mvc.perform(get("/api/v1/my/claims"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
