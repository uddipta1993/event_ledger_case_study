package com.example.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@RestController
public class AccountController {
    private final Map<String, Tx> transactions = new ConcurrentHashMap<String, Tx>();
    private final AtomicLong requests = new AtomicLong();

    @PostMapping("/accounts/{accountId}/transactions")
    public synchronized ResponseEntity<?> apply(@PathVariable String accountId, @RequestBody Tx tx,
                                                 @RequestHeader(value="X-Trace-Id", required=false) String trace) {
        requests.incrementAndGet();
        if (tx.eventId == null || tx.accountId == null || tx.type == null || tx.amount == null || tx.amount.signum() <= 0)
            return ResponseEntity.badRequest().body(error("eventId, accountId, type and positive amount are required"));
        if (!accountId.equals(tx.accountId) || !("CREDIT".equals(tx.type) || "DEBIT".equals(tx.type)))
            return ResponseEntity.badRequest().body(error("accountId mismatch or unsupported transaction type"));
        Tx existing = transactions.get(tx.eventId);
        if (existing == null) { transactions.put(tx.eventId, tx); existing = tx; }
        return ResponseEntity.ok(account(accountId));
    }

    @GetMapping("/accounts/{accountId}/balance")
    public Map<String,Object> balance(@PathVariable String accountId) {
        requests.incrementAndGet(); Map<String,Object> result = new java.util.LinkedHashMap<String,Object>();
        result.put("accountId", accountId); result.put("currency", currency(accountId)); result.put("balance", total(accountId)); return result;
    }

    @GetMapping("/accounts/{accountId}")
    public Map<String,Object> details(@PathVariable String accountId) { requests.incrementAndGet(); return account(accountId); }

    @GetMapping("/health")
    public Map<String,Object> health() { Map<String,Object> m = new java.util.LinkedHashMap<String,Object>(); m.put("status","UP"); m.put("service","account"); m.put("store","UP"); return m; }

    @GetMapping("/metrics")
    public Map<String,Object> metrics() { Map<String,Object> m = new java.util.LinkedHashMap<String,Object>(); m.put("requests_total",requests.get()); return m; }

    private Map<String,Object> account(String id) {
        Map<String,Object> m = new java.util.LinkedHashMap<String,Object>(); m.put("accountId",id); m.put("currency",currency(id)); m.put("balance",total(id));
        List<Tx> recent = new ArrayList<Tx>(); for (Tx t: transactions.values()) if (id.equals(t.accountId)) recent.add(t);
        recent.sort(Comparator.comparing(t -> t.eventTimestamp == null ? java.time.Instant.MIN : java.time.OffsetDateTime.parse(t.eventTimestamp).toInstant()));
        m.put("transactions",recent); return m;
    }
    private BigDecimal total(String id) {
        BigDecimal sum = BigDecimal.ZERO; for (Tx t: transactions.values()) if (id.equals(t.accountId))
            sum = sum.add("CREDIT".equals(t.type) ? t.amount : t.amount.negate());
        return sum;
    }
    private String currency(String id) { for (Tx t: transactions.values()) if (id.equals(t.accountId)) return t.currency; return "USD"; }
    private Map<String,String> error(String message) { Map<String,String> e = new java.util.LinkedHashMap<String,String>(); e.put("error",message); return e; }

    public static class Tx {
        public String eventId; public String accountId; public String type; public BigDecimal amount; public String currency; public String eventTimestamp; public Map<String,Object> metadata;
    }
    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> bad(Exception e) {
            Map<String,String> m = new java.util.LinkedHashMap<String,String>(); m.put("error","Invalid request"); return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(m);
        }
    }
    @org.springframework.stereotype.Component
    public static class TraceFilter extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, java.io.IOException {
            String trace=req.getHeader("X-Trace-Id"); if(trace==null || trace.trim().isEmpty()) trace=java.util.UUID.randomUUID().toString();
            res.setHeader("X-Trace-Id",trace); System.out.println("{\"timestamp\":\""+Instant.now()+"\",\"level\":\"INFO\",\"service\":\"account\",\"traceId\":\""+trace+"\",\"path\":\""+req.getRequestURI()+"\"}");
            chain.doFilter(req,res);
        }
    }
}
