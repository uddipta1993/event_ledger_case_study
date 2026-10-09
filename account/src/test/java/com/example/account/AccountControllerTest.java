package com.example.account;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AccountControllerTest {
    private AccountController.Tx tx(String id,String type,String amount,String timestamp) {
        AccountController.Tx t=new AccountController.Tx(); t.eventId=id; t.accountId="a1"; t.type=type; t.amount=new BigDecimal(amount); t.currency="USD"; t.eventTimestamp=timestamp; return t;
    }
    @Test void deduplicatesAndComputesBalanceIndependentOfArrivalOrder() {
        AccountController c=new AccountController();
        c.apply("a1",tx("later","DEBIT","20","2026-01-02T00:00:00Z"),"trace");
        c.apply("a1",tx("earlier","CREDIT","50","2026-01-01T00:00:00Z"),"trace");
        c.apply("a1",tx("later","DEBIT","20","2026-01-02T00:00:00Z"),"trace");
        assertEquals(new BigDecimal("30"),c.balance("a1").get("balance"));
        assertEquals(2,((java.util.List<?>)c.details("a1").get("transactions")).size());
    }
    @Test void rejectsInvalidTransactions() {
        AccountController c=new AccountController();
        assertEquals(HttpStatus.BAD_REQUEST,c.apply("a1",tx("bad","CREDIT","0","now"),null).getStatusCode());
    }
}
