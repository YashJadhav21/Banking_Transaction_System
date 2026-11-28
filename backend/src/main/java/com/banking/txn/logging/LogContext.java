package com.banking.txn.logging;

import com.banking.txn.domain.TransactionRequest;
import org.slf4j.MDC;

/**
 * Puts the transaction's identifiers into the SLF4J MDC (Mapped Diagnostic Context).
 * Every log line written on this thread while processing the transaction then carries
 * these fields automatically - in the JSON logs they appear as top-level fields.
 * Timestamp and thread name are added by the log encoder itself.
 *
 * Account numbers are masked: only the last 4 characters are logged.
 */
public final class LogContext {

    private LogContext() {
    }

    public static void put(TransactionRequest request) {
        putIfPresent("transactionId", request.transactionId());
        putIfPresent("operation", request.type() == null ? null : request.type().name());
        putIfPresent("sourceAccount", mask(request.sourceAccount()));
        putIfPresent("destinationAccount", mask(request.destinationAccount()));
    }

    /** Must be called when processing ends: pool threads are reused for the next transaction. */
    public static void clear() {
        MDC.clear();
    }

    /** "ACC000123" becomes "*****0123". */
    public static String mask(String accountId) {
        if (accountId == null) {
            return null;
        }
        if (accountId.length() <= 4) {
            return "****";
        }
        int hidden = accountId.length() - 4;
        return "*".repeat(hidden) + accountId.substring(hidden);
    }

    private static void putIfPresent(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }
}
