package com.example.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;

@RestController
public class AccountController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong requests = new AtomicLong();

    public AccountController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS account_transactions (event_id VARCHAR(200) PRIMARY KEY, account_id VARCHAR(200) NOT NULL, transaction_type VARCHAR(10) NOT NULL, amount DECIMAL(19,4) NOT NULL, currency VARCHAR(10) NOT NULL, event_timestamp VARCHAR(80) NOT NULL, metadata CLOB)");
    }

    @PostMapping("/accounts/{accountId}/transactions")
    public synchronized ResponseEntity<?> apply(@PathVariable String accountId, @RequestBody Tx tx,
                                                 @RequestHeader(value="X-Trace-Id", required=false) String trace) {
        requests.incrementAndGet();
        if (tx.eventId == null || tx.eventId.trim().isEmpty() || tx.accountId == null || tx.type == null || tx.amount == null || tx.amount.signum() <= 0 || tx.currency == null || tx.eventTimestamp == null)
            return ResponseEntity.badRequest().body(error("eventId, accountId, type, currency, timestamp and positive amount are required"));
        if (!accountId.equals(tx.accountId) || !("CREDIT".equals(tx.type) || "DEBIT".equals(tx.type)))
            return ResponseEntity.badRequest().body(error("accountId mismatch or unsupported transaction type"));
        if (jdbc.queryForObject("SELECT COUNT(*) FROM account_transactions WHERE event_id=?", Integer.class, tx.eventId) == 0) {
            try {
                jdbc.update("INSERT INTO account_transactions(event_id,account_id,transaction_type,amount,currency,event_timestamp,metadata) VALUES(?,?,?,?,?,?,?)",
                        tx.eventId, tx.accountId, tx.type, tx.amount, tx.currency, tx.eventTimestamp, metadataJson(tx.metadata));
            } catch (DuplicateKeyException ignored) { /* A concurrent retry already recorded this event. */ }
        }
        return ResponseEntity.ok(account(accountId));
    }

    @GetMapping("/accounts/{accountId}/balance")
    public Map<String,Object> balance(@PathVariable String accountId) {
        requests.incrementAndGet(); Map<String,Object> result = new LinkedHashMap<String,Object>();
        result.put("accountId", accountId); result.put("currency", currency(accountId)); result.put("balance", total(accountId)); return result;
    }

    @GetMapping("/accounts/{accountId}")
    public Map<String,Object> details(@PathVariable String accountId) { requests.incrementAndGet(); return account(accountId); }

    @GetMapping("/health")
    public Map<String,Object> health() {
        Map<String,Object> m = new LinkedHashMap<String,Object>();
        try { jdbc.queryForObject("SELECT 1", Integer.class); m.put("status","UP"); m.put("database","UP"); }
        catch (Exception ex) { m.put("status","DOWN"); m.put("database","DOWN"); }
        m.put("service","account"); return m;
    }

    @GetMapping("/metrics")
    public Map<String,Object> metrics() { Map<String,Object> m = new LinkedHashMap<String,Object>(); m.put("requests_total",requests.get()); return m; }

    private Map<String,Object> account(String id) {
        Map<String,Object> m = new LinkedHashMap<String,Object>(); m.put("accountId",id); m.put("currency",currency(id)); m.put("balance",total(id));
        List<Tx> recent = jdbc.query("SELECT event_id,account_id,transaction_type,amount,currency,event_timestamp,metadata FROM account_transactions WHERE account_id=?", (rs,n) -> {
            Tx t=new Tx(); t.eventId=rs.getString("event_id"); t.accountId=rs.getString("account_id"); t.type=rs.getString("transaction_type"); t.amount=rs.getBigDecimal("amount"); t.currency=rs.getString("currency"); t.eventTimestamp=rs.getString("event_timestamp");
            String json=rs.getString("metadata"); if(json!=null) try { t.metadata=mapper.readValue(json,Map.class); } catch(Exception ignored) { t.metadata=new LinkedHashMap<String,Object>(); }
            return t;
        }, id);
        recent.sort(Comparator.comparing(t -> t.eventTimestamp == null ? Instant.MIN : OffsetDateTime.parse(t.eventTimestamp).toInstant()));
        m.put("transactions",recent); return m;
    }
    private BigDecimal total(String id) {
        BigDecimal sum=jdbc.queryForObject("SELECT COALESCE(SUM(CASE WHEN transaction_type='CREDIT' THEN amount ELSE -amount END),0) FROM account_transactions WHERE account_id=?",BigDecimal.class,id);
        return sum==null?BigDecimal.ZERO:sum;
    }
    private String currency(String id) {
        List<String> values=jdbc.query("SELECT currency FROM account_transactions WHERE account_id=? ORDER BY event_timestamp LIMIT 1",(rs,n)->rs.getString(1),id);
        return values.isEmpty()?"USD":values.get(0);
    }
    private String metadataJson(Map<String,Object> metadata) {
        if(metadata==null) return null;
        try { return mapper.writeValueAsString(metadata); } catch(JsonProcessingException ex) { throw new IllegalArgumentException("metadata must be valid JSON",ex); }
    }
    private Map<String,String> error(String message) { Map<String,String> e = new LinkedHashMap<String,String>(); e.put("error",message); return e; }

    public static class Tx {
        public String eventId; public String accountId; public String type; public BigDecimal amount; public String currency; public String eventTimestamp; public Map<String,Object> metadata;
    }
    @RestControllerAdvice
    public static class Errors {
        @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> bad(Exception e) {
            Map<String,String> m = new LinkedHashMap<String,String>(); m.put("error","Invalid request"); return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(m);
        }
    }
    @Component
    public static class TraceFilter extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, java.io.IOException {
            String trace=req.getHeader("X-Trace-Id"); if(trace==null || trace.trim().isEmpty()) trace=java.util.UUID.randomUUID().toString();
            res.setHeader("X-Trace-Id",trace); System.out.println("{\"timestamp\":\""+Instant.now()+"\",\"level\":\"INFO\",\"service\":\"account\",\"traceId\":\""+trace+"\",\"path\":\""+req.getRequestURI()+"\"}");
            chain.doFilter(req,res);
        }
    }
}
