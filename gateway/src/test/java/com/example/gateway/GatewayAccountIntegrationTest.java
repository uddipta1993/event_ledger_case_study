package com.example.gateway;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

class GatewayAccountIntegrationTest {
    @Test void submitsGatewayEventOverHttpAndPropagatesTraceToAccountEndpoint() throws Exception {
        final AtomicInteger calls=new AtomicInteger(); final String[] receivedTrace=new String[1]; final String[] receivedBody=new String[1];
        HttpServer account=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        account.createContext("/accounts/a1/transactions",exchange -> {
            calls.incrementAndGet(); receivedTrace[0]=exchange.getRequestHeaders().getFirst("X-Trace-Id");
            InputStream in=exchange.getRequestBody(); ByteArrayOutputStream body=new ByteArrayOutputStream(); byte[] buf=new byte[512]; int n;
            while((n=in.read(buf))!=-1) body.write(buf,0,n); receivedBody[0]=new String(body.toByteArray(),StandardCharsets.UTF_8);
            byte[] response="{}".getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().add("Content-Type","application/json"); exchange.sendResponseHeaders(200,response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        account.start();
        try {
            EventController gateway=new EventController(new RestTemplate(),"http://127.0.0.1:"+account.getAddress().getPort());
            EventController.Event e=new EventController.Event(); e.eventId="evt-integration"; e.accountId="a1"; e.type="CREDIT"; e.amount=new BigDecimal("8.25"); e.currency="USD"; e.eventTimestamp="2026-05-15T14:02:11Z";
            assertEquals(HttpStatus.CREATED,gateway.submit(e,"trace-integration-1").getStatusCode());
            assertEquals(1,calls.get()); assertEquals("trace-integration-1",receivedTrace[0]); assertTrue(receivedBody[0].contains("evt-integration"));
        } finally { account.stop(0); }
    }
}
