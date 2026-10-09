package com.example.gateway;

import static org.junit.jupiter.api.Assertions.*;
import com.example.account.AccountApplication;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(classes=AccountApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayAccountIntegrationTest {
    @LocalServerPort private int accountPort;

    @Test void appliesGatewayEventToRunningAccountServiceOverHttp() {
        EventController gateway=new EventController(new RestTemplate(),"http://127.0.0.1:"+accountPort);
        EventController.Event e=new EventController.Event(); e.eventId="evt-integration"; e.accountId="a1"; e.type="CREDIT"; e.amount=new BigDecimal("8.25"); e.currency="USD"; e.eventTimestamp="2026-05-15T14:02:11Z";
        assertEquals(HttpStatus.CREATED,gateway.submit(e,"trace-integration-1").getStatusCode());
        org.springframework.http.ResponseEntity<?> response=gateway.balance("a1","trace-integration-1");
        assertEquals(HttpStatus.OK,response.getStatusCode());
        assertEquals(new BigDecimal("8.25"),new BigDecimal(String.valueOf(((Map<?,?>)response.getBody()).get("balance"))));
    }
}
