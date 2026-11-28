package com.banking.txn.api;

import com.banking.txn.domain.ErrorCategory;
import com.banking.txn.domain.TransactionDetails;
import com.banking.txn.domain.TransactionRequest;
import com.banking.txn.domain.TransactionResult;
import com.banking.txn.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * Processes a deposit, withdrawal or transfer.
     * 200 SUCCESS · 409 duplicate id · 422 rejected by a business rule · 503 technical failure.
     * Malformed requests (missing fields, bad JSON) get 400 from {@link GlobalExceptionHandler}.
     */
    @PostMapping
    public ResponseEntity<TransactionResult> submit(@Valid @RequestBody TransactionRequest request) {
        TransactionResult result = transactionService.process(request);
        return ResponseEntity.status(httpStatusFor(result)).body(result);
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionDetails> get(@PathVariable String transactionId) {
        return transactionService.findTransaction(transactionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    private static HttpStatus httpStatusFor(TransactionResult result) {
        return switch (result.status()) {
            case SUCCESS -> HttpStatus.OK;
            case REJECTED -> result.errorCategory() == ErrorCategory.DUPLICATE_TRANSACTION
                    ? HttpStatus.CONFLICT
                    : HttpStatus.UNPROCESSABLE_ENTITY;
            case FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
