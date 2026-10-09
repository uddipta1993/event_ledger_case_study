package com.example.gateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

@RestController
public class EventController {
    private final Map<String, Event> events = new ConcurrentHashMap<String, Event>();
    private final Map<String,AtomicLong> counts = new ConcurrentHashMap<String,AtomicLong>();
    private final RestTemplate client;
    private final String accountUrl;
    private int consecutiveFailures = 0;
    private long circuitOpenUntil = 0;

    public EventController(RestTemplate client, @Value("${account.service.url:http://localhost:8081}") String accountUrl) {
        this.client=client; this.accountUrl=accountUrl;
    }

    @PostMapping("/events")
    public synchronized ResponseEntity<?> submit(@Valid @RequestBody Event incoming, @RequestHeader(value="X-Trace-Id",required=false) String trace) {
        count("POST /events");
        if (!("CREDIT".equals(incoming.type) || "DEBIT".equals(incoming.type))) return error(HttpStatus.BAD_REQUEST,"type must be CREDIT or DEBIT");
        try { OffsetDateTime.parse(incoming.eventTimestamp); } catch(Exception ex) { return error(HttpStatus.BAD_REQUEST,"eventTimestamp must be ISO 8601"); }
        Event event=events.get(incoming.eventId);
        boolean duplicate=event!=null;
        if (!duplicate) { event=incoming.copy(); event.applied=false; events.put(event.eventId,event); }
        if (!event.applied) {
            try {
                callAccount(event,trace); event.applied=true; consecutiveFailures=0;
            } catch(CircuitOpenException ex) { return error(HttpStatus.SERVICE_UNAVAILABLE,"Account Service circuit is open"); }
              catch(RestClientException ex) { failed(); return error(HttpStatus.SERVICE_UNAVAILABLE,"Account Service is unavailable; retry by submitting this event again"); }
        }
        return ResponseEntity.status(duplicate ? HttpStatus.OK : HttpStatus.CREATED).body(event);
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<?> get(@PathVariable String id) { count("GET /events/{id}"); Event e=events.get(id); return e==null?error(HttpStatus.NOT_FOUND,"event not found"):ResponseEntity.ok(e); }

    @GetMapping("/events")
    public List<Event> list(@RequestParam("account") String account) {
        count("GET /events"); List<Event> found=new ArrayList<Event>(); for(Event e:events.values()) if(account.equals(e.accountId)) found.add(e);
        found.sort(Comparator.comparing(e -> OffsetDateTime.parse(e.eventTimestamp).toInstant())); return found;
    }

    @GetMapping("/accounts/{id}/balance")
    public ResponseEntity<?> balance(@PathVariable String id, @RequestHeader(value="X-Trace-Id",required=false) String trace) { count("GET /accounts/{id}/balance"); return forward("/accounts/"+id+"/balance",trace); }
    @GetMapping("/accounts/{id}")
    public ResponseEntity<?> account(@PathVariable String id, @RequestHeader(value="X-Trace-Id",required=false) String trace) { count("GET /accounts/{id}"); return forward("/accounts/"+id,trace); }

    @GetMapping("/health") public Map<String,Object> health() { Map<String,Object> m=new LinkedHashMap<String,Object>(); m.put("status","UP"); m.put("service","gateway"); m.put("store","UP"); return m; }
    @GetMapping("/metrics") public Map<String,Object> metrics() { Map<String,Object> m=new LinkedHashMap<String,Object>(); Map<String,Long> c=new LinkedHashMap<String,Long>(); for(String k:counts.keySet()) c.put(k,counts.get(k).get()); m.put("request_count_by_endpoint",c); return m; }

    private ResponseEntity<?> forward(String path,String trace) {
        if(System.currentTimeMillis()<circuitOpenUntil) return error(HttpStatus.SERVICE_UNAVAILABLE,"Account Service circuit is open");
        try { HttpHeaders h=headers(trace); return ResponseEntity.ok(client.exchange(accountUrl+path,HttpMethod.GET,new HttpEntity<Object>(h),Object.class).getBody()); }
        catch(Exception ex) { failed(); return error(HttpStatus.SERVICE_UNAVAILABLE,"Account Service is unavailable"); }
    }
    private void callAccount(Event event,String trace) {
        if(System.currentTimeMillis()<circuitOpenUntil) throw new CircuitOpenException();
        RuntimeException last=null;
        for(int attempt=0;attempt<2;attempt++) {
            try { client.exchange(accountUrl+"/accounts/"+event.accountId+"/transactions",HttpMethod.POST,new HttpEntity<Event>(event,headers(trace)),Object.class); return; }
            catch(RestClientException ex) { last=ex; if(attempt==0) try { Thread.sleep(100); } catch(InterruptedException ie) { Thread.currentThread().interrupt(); throw ex; } }
        }
        throw last;
    }
    private HttpHeaders headers(String trace) { HttpHeaders h=new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); String id=trace==null||trace.trim().isEmpty()?TraceFilter.current():trace; h.set("X-Trace-Id",id==null?UUID.randomUUID().toString():id); return h; }
    private void failed() { consecutiveFailures++; if(consecutiveFailures>=3) { circuitOpenUntil=System.currentTimeMillis()+5000; consecutiveFailures=0; } }
    private void count(String endpoint) { AtomicLong a=counts.get(endpoint); if(a==null) { AtomicLong n=new AtomicLong(); a=counts.putIfAbsent(endpoint,n); if(a==null)a=n; } a.incrementAndGet(); }
    private ResponseEntity<Map<String,String>> error(HttpStatus status,String text) { Map<String,String> m=new LinkedHashMap<String,String>(); m.put("error",text); return ResponseEntity.status(status).body(m); }
    private static class CircuitOpenException extends RuntimeException { private static final long serialVersionUID=1L; }

    public static class Event {
        @NotBlank public String eventId; @NotBlank public String accountId; @NotBlank public String type;
        @NotNull @DecimalMin(value="0.0",inclusive=false) public BigDecimal amount;
        @NotBlank public String currency; @NotBlank public String eventTimestamp;
        public Map<String,Object> metadata; public boolean applied;
        public Event copy() { Event e=new Event(); e.eventId=eventId; e.accountId=accountId; e.type=type; e.amount=amount; e.currency=currency; e.eventTimestamp=eventTimestamp; e.metadata=metadata; e.applied=applied; return e; }
    }
    @org.springframework.stereotype.Component
    public static class TraceFilter extends OncePerRequestFilter {
        private static final ThreadLocal<String> CURRENT=new ThreadLocal<String>();
        static String current() { return CURRENT.get(); }
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,java.io.IOException {
            String trace=req.getHeader("X-Trace-Id"); if(trace==null||trace.trim().isEmpty()) trace=UUID.randomUUID().toString();
            CURRENT.set(trace); res.setHeader("X-Trace-Id",trace); System.out.println("{\"timestamp\":\""+Instant.now()+"\",\"level\":\"INFO\",\"service\":\"gateway\",\"traceId\":\""+trace+"\",\"path\":\""+req.getRequestURI()+"\"}");
            try { chain.doFilter(req,res); } finally { CURRENT.remove(); }
        }
    }
    @RestControllerAdvice public static class ValidationErrors {
        @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
        public ResponseEntity<Map<String,String>> invalid(org.springframework.web.bind.MethodArgumentNotValidException e) { Map<String,String> m=new LinkedHashMap<String,String>(); m.put("error","missing or invalid required fields"); return ResponseEntity.badRequest().body(m); }
    }
}
