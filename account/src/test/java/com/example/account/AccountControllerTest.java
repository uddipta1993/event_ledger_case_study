package com.example.account;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AccountControllerTest {
    private AccountController controller() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:test"+java.util.UUID.randomUUID().toString().replace("-","")+";DB_CLOSE_DELAY=-1","sa","");
        ds.setDriverClassName("org.h2.Driver"); return new AccountController(new JdbcTemplate(ds));
    }
    private AccountController.Tx tx(String id,String type,String amount,String timestamp) {
        AccountController.Tx t=new AccountController.Tx(); t.eventId=id; t.accountId="a1"; t.type=type; t.amount=new BigDecimal(amount); t.currency="USD"; t.eventTimestamp=timestamp; return t;
    }
    @Test void deduplicatesAndComputesBalanceIndependentOfArrivalOrder() {
        AccountController c=controller();
        c.apply("a1",tx("later","DEBIT","20","2026-01-02T00:00:00Z"),"trace");
        c.apply("a1",tx("earlier","CREDIT","50","2026-01-01T00:00:00Z"),"trace");
        c.apply("a1",tx("later","DEBIT","20","2026-01-02T00:00:00Z"),"trace");
        assertEquals(0,new BigDecimal("30").compareTo((BigDecimal)c.balance("a1").get("balance")));
        assertEquals(2,((java.util.List<?>)c.details("a1").get("transactions")).size());
    }
    @Test void rejectsInvalidTransactions() {
        AccountController c=controller();
        assertEquals(HttpStatus.BAD_REQUEST,c.apply("a1",tx("bad","CREDIT","0","now"),null).getStatusCode());
    }
}
