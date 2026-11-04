package com.example.booking.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full HTTP flow through security, validation, controllers, services and the H2 database. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    private static final String AUTH = "Authorization";
    private static final String PASSWORD = "secret123";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // ---------- authentication / authorization ----------

    @Test
    void requestsWithoutToken_areRejectedWith401() throws Exception {
        mockMvc.perform(get("/slots"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mockMvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidToken_isRejectedWith401() throws Exception {
        mockMvc.perform(get("/slots").header(AUTH, "Bearer not.a.valid.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void user_cannotCreateSlots_butAdminCan() throws Exception {
        String user = registerAndLogin();
        String admin = adminToken();
        String body = slotJson(LocalDateTime.now().plusDays(2));

        mockMvc.perform(post("/slots").header(AUTH, bearer(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mockMvc.perform(post("/slots").header(AUTH, bearer(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void admin_cannotBookSlots() throws Exception {
        String admin = adminToken();
        long slotId = createSlot(admin);

        mockMvc.perform(post("/bookings").header(AUTH, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":" + slotId + "}"))
                .andExpect(status().isForbidden());
    }

    // ---------- booking lifecycle ----------

    @Test
    void fullLifecycle_book_conflict_cancel_rebook() throws Exception {
        String admin = adminToken();
        String alice = registerAndLogin();
        String bob = registerAndLogin();
        long slotId = createSlot(admin);

        // alice books
        long bookingId = book(alice, slotId);
        mockMvc.perform(get("/slots?status=BOOKED").header(AUTH, bearer(bob)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + slotId + ")].status").value("BOOKED"));

        // bob tries the same slot -> clear 409
        mockMvc.perform(post("/bookings").header(AUTH, bearer(bob))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":" + slotId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Slot " + slotId + " is already booked"));

        // bob cannot cancel alice's booking
        mockMvc.perform(post("/bookings/" + bookingId + "/cancel").header(AUTH, bearer(bob)))
                .andExpect(status().isForbidden());

        // alice cancels; second cancel -> 409
        mockMvc.perform(post("/bookings/" + bookingId + "/cancel").header(AUTH, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/bookings/" + bookingId + "/cancel").header(AUTH, bearer(alice)))
                .andExpect(status().isConflict());

        // slot is available again and bob can now book it
        mockMvc.perform(get("/slots?status=AVAILABLE").header(AUTH, bearer(bob)))
                .andExpect(jsonPath("$[?(@.id==" + slotId + ")].status").value("AVAILABLE"));
        book(bob, slotId);
    }

    @Test
    void admin_canCancelAnyBooking() throws Exception {
        String admin = adminToken();
        String alice = registerAndLogin();
        long slotId = createSlot(admin);
        long bookingId = book(alice, slotId);

        mockMvc.perform(post("/admin/bookings/" + bookingId + "/cancel").header(AUTH, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(post("/admin/bookings/" + bookingId + "/cancel").header(AUTH, bearer(alice)))
                .andExpect(status().isForbidden());
    }

    @Test
    void notFoundResources_return404() throws Exception {
        String user = registerAndLogin();

        mockMvc.perform(post("/bookings").header(AUTH, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":999999}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/bookings/999999/cancel").header(AUTH, bearer(user)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/no-such-endpoint").header(AUTH, bearer(user)))
                .andExpect(status().isNotFound());
    }

    // ---------- validation ----------

    @Test
    void createSlot_withInvalidPayload_returns400WithFieldErrors() throws Exception {
        String admin = adminToken();

        mockMvc.perform(post("/slots").header(AUTH, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.startTime").exists())
                .andExpect(jsonPath("$.validationErrors.endTime").exists());

        mockMvc.perform(post("/slots").header(AUTH, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotJson(LocalDateTime.now().minusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.startTime").value("startTime must be in the future"));

        LocalDateTime start = LocalDateTime.now().plusDays(1);
        mockMvc.perform(post("/slots").header(AUTH, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("startTime", start.toString(), "endTime", start.minusHours(1).toString()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("endTime must be after startTime"));
    }

    @Test
    void booking_withMissingSlotId_orMalformedJson_returns400() throws Exception {
        String user = registerAndLogin();

        mockMvc.perform(post("/bookings").header(AUTH, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.slotId").exists());
        mockMvc.perform(post("/bookings").header(AUTH, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidStatusFilter_returns400_andWrongMethodReturns405() throws Exception {
        String user = registerAndLogin();

        mockMvc.perform(get("/slots?status=BROKEN").header(AUTH, bearer(user)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/slots").header(AUTH, bearer(user)))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---------- auth endpoints ----------

    @Test
    void register_validation_duplicate_and_loginFailures() throws Exception {
        String username = "user-" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", "123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").exists());

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("ROLE_USER"));

        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "nobody-here", "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "", "password", ""))))
                .andExpect(status().isBadRequest());
    }

    // ---------- helpers ----------

    private String registerAndLogin() throws Exception {
        String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", PASSWORD))))
                .andExpect(status().isCreated());
        return login(username, PASSWORD);
    }

    private String adminToken() throws Exception {
        return login("admin", "admin123");
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).get("token").asText();
    }

    private long createSlot(String adminToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/slots").header(AUTH, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(slotJson(LocalDateTime.now().plusDays(1))))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).get("id").asLong();
    }

    private long book(String userToken, long slotId) throws Exception {
        MvcResult result = mockMvc.perform(post("/bookings").header(AUTH, bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":" + slotId + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
        return read(result).get("id").asLong();
    }

    private String slotJson(LocalDateTime start) throws Exception {
        return json(Map.of("startTime", start.toString(), "endTime", start.plusHours(1).toString()));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
