package com.banking.txn.api;

import com.banking.txn.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The REST API: request/response format and HTTP status codes. */
class TransactionApiTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void createAccounts() {
        createAccount("ACC1001", "20000.00");
        createAccount("ACC2004", "100.00");
    }

    @Test
    void sampleTransferFromTheAssignmentSucceeds() throws Exception {
        submit("""
                { "transactionId": "TXN100234", "sourceAccount": "ACC1001",
                  "destinationAccount": "ACC2004", "amount": 12500.00, "type": "TRANSFER" }
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.transactionId").value("TXN100234"));

        mvc.perform(get("/api/accounts/ACC2004"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(12600.00));
    }

    @Test
    void businessRejectionReturns422() throws Exception {
        submit("""
                { "transactionId": "TXN-1", "sourceAccount": "ACC2004", "amount": 500.00, "type": "WITHDRAWAL" }
                """)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCategory").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void duplicateReturns409() throws Exception {
        String body = """
                { "transactionId": "TXN-2", "destinationAccount": "ACC2004", "amount": 1.00, "type": "DEPOSIT" }
                """;
        submit(body).andExpect(status().isOk());
        submit(body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCategory").value("DUPLICATE_TRANSACTION"));
    }

    @Test
    void missingFieldsReturn400WithFieldErrors() throws Exception {
        submit("""
                { "destinationAccount": "ACC2004", "type": "DEPOSIT" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unknownTypeReturns400() throws Exception {
        submit("""
                { "transactionId": "TXN-3", "destinationAccount": "ACC2004", "amount": 1.00, "type": "LOAN" }
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    void storedTransactionShowsFailureReason() throws Exception {
        submit("""
                { "transactionId": "TXN-4", "sourceAccount": "ACC2004", "amount": 999.00, "type": "WITHDRAWAL" }
                """);

        mvc.perform(get("/api/transactions/TXN-4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.errorCategory").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.failureReason").exists());
    }

    @Test
    void unknownTransactionAndAccountReturn404() throws Exception {
        mvc.perform(get("/api/transactions/NOPE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/ACC9999")).andExpect(status().isNotFound());
    }

    private ResultActions submit(String json) throws Exception {
        return mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(json));
    }
}
