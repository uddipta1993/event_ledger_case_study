package com.example.gateway;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

class EventControllerTest {
    private EventController.Event event(String id,String time) {
        EventController.Event e=new EventController.Event(); e.eventId=id; e.accountId="a1"; e.type="CREDIT"; e.amount=new BigDecimal("10"); e.currency="USD"; e.eventTimestamp=time; return e;
    }
    @Test void persistsListsChronologicallyAndReturnsOriginalOnDuplicate() {
        RestTemplate rest=mock(RestTemplate.class); when(rest.exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(Object.class))).thenReturn(new ResponseEntity<Object>(Collections.emptyMap(),HttpStatus.OK));
        EventController c=new EventController(rest,"http://account");
        assertEquals(HttpStatus.CREATED,c.submit(event("second","2026-02-02T00:00:00Z"),"t").getStatusCode());
        assertEquals(HttpStatus.CREATED,c.submit(event("first","2026-02-01T00:00:00Z"),"t").getStatusCode());
        assertEquals(HttpStatus.OK,c.submit(event("first","2026-02-01T00:00:00Z"),"t").getStatusCode());
        assertEquals("first",c.list("a1").get(0).eventId);
        verify(rest,times(2)).exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(Object.class));
    }
    @Test void passesTraceAndReturns503WhenAccountServiceIsDown() {
        RestTemplate rest=mock(RestTemplate.class); when(rest.exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(Object.class))).thenThrow(new ResourceAccessException("down"));
        EventController c=new EventController(rest,"http://account");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,c.submit(event("evt","2026-02-01T00:00:00Z"),"trace-123").getStatusCode());
        org.mockito.ArgumentCaptor<HttpEntity> request=org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(rest,times(2)).exchange(anyString(),eq(HttpMethod.POST),request.capture(),eq(Object.class));
        assertEquals("trace-123",request.getValue().getHeaders().getFirst("X-Trace-Id"));
        assertEquals(HttpStatus.OK,c.get("evt").getStatusCode());
    }
    @Test void opensCircuitAfterRepeatedFailures() {
        RestTemplate rest=mock(RestTemplate.class); when(rest.exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(Object.class))).thenThrow(new ResourceAccessException("down"));
        EventController c=new EventController(rest,"http://account");
        c.submit(event("one","2026-02-01T00:00:00Z"),"t"); c.submit(event("two","2026-02-01T00:00:00Z"),"t"); c.submit(event("three","2026-02-01T00:00:00Z"),"t");
        org.springframework.http.ResponseEntity<?> blocked=c.submit(event("four","2026-02-01T00:00:00Z"),"t");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,blocked.getStatusCode());
        assertTrue(blocked.getBody().toString().contains("circuit is open"));
        verify(rest,times(6)).exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(Object.class));
    }
}
