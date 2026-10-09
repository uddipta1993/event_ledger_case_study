package com.example.gateway;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(classes=GatewayApplication.class)
@AutoConfigureMockMvc
class EventValidationMvcTest {
    @Autowired private MockMvc mvc;
    @MockBean private RestTemplate restTemplate;

    @Test void rejectsMissingAndNonPositiveAmounts() throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"missing-amount\",\"accountId\":\"a1\",\"type\":\"CREDIT\",\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:02:11Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"zero\",\"accountId\":\"a1\",\"type\":\"CREDIT\",\"amount\":0,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:02:11Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"negative\",\"accountId\":\"a1\",\"type\":\"CREDIT\",\"amount\":-1,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:02:11Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventId\":\"unknown-type\",\"accountId\":\"a1\",\"type\":\"REFUND\",\"amount\":1,\"currency\":\"USD\",\"eventTimestamp\":\"2026-05-15T14:02:11Z\"}"))
                .andExpect(status().isBadRequest());
    }
}
