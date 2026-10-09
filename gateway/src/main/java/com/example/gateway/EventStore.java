package com.example.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public EventStore(JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS gateway_events (event_id VARCHAR(200) PRIMARY KEY, account_id VARCHAR(200) NOT NULL, event_type VARCHAR(10) NOT NULL, amount DECIMAL(19,4) NOT NULL, currency VARCHAR(10) NOT NULL, event_timestamp VARCHAR(80) NOT NULL, metadata CLOB, applied BOOLEAN NOT NULL)");
    }

    public EventController.Event find(String id) {
        List<EventController.Event> matches=jdbc.query("SELECT * FROM gateway_events WHERE event_id=?",(rs,n)-> {
            EventController.Event e=new EventController.Event(); e.eventId=rs.getString("event_id"); e.accountId=rs.getString("account_id"); e.type=rs.getString("event_type"); e.amount=rs.getBigDecimal("amount"); e.currency=rs.getString("currency"); e.eventTimestamp=rs.getString("event_timestamp"); e.applied=rs.getBoolean("applied");
            String json=rs.getString("metadata"); if(json!=null) try { e.metadata=mapper.readValue(json,java.util.Map.class); } catch(Exception ignored) { e.metadata=new java.util.LinkedHashMap<String,Object>(); }
            return e;
        },id);
        return matches.isEmpty()?null:matches.get(0);
    }

    public void insert(EventController.Event e) {
        String metadata=null;
        try { if(e.metadata!=null) metadata=mapper.writeValueAsString(e.metadata); }
        catch(Exception ex) { throw new IllegalArgumentException("metadata must be valid JSON",ex); }
        jdbc.update("INSERT INTO gateway_events(event_id,account_id,event_type,amount,currency,event_timestamp,metadata,applied) VALUES(?,?,?,?,?,?,?,?)",e.eventId,e.accountId,e.type,e.amount,e.currency,e.eventTimestamp,metadata,false);
    }

    public void markApplied(String id) { jdbc.update("UPDATE gateway_events SET applied=TRUE WHERE event_id=?",id); }

    public boolean healthy() {
        try { jdbc.queryForObject("SELECT 1",Integer.class); return true; }
        catch(Exception ex) { return false; }
    }

    public List<EventController.Event> byAccount(String account) {
        List<EventController.Event> found=jdbc.query("SELECT * FROM gateway_events WHERE account_id=?",(rs,n)-> {
            EventController.Event e=new EventController.Event(); e.eventId=rs.getString("event_id"); e.accountId=rs.getString("account_id"); e.type=rs.getString("event_type"); e.amount=rs.getBigDecimal("amount"); e.currency=rs.getString("currency"); e.eventTimestamp=rs.getString("event_timestamp"); e.applied=rs.getBoolean("applied");
            String json=rs.getString("metadata"); if(json!=null) try { e.metadata=mapper.readValue(json,java.util.Map.class); } catch(Exception ignored) { e.metadata=new java.util.LinkedHashMap<String,Object>(); }
            return e;
        },account);
        found.sort(Comparator.comparing(e -> OffsetDateTime.parse(e.eventTimestamp).toInstant())); return found;
    }
}
