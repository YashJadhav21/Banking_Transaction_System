package com.banking.txn.service;

import com.banking.txn.IntegrationTest;
import com.banking.txn.domain.ErrorCategory;
import com.banking.txn.domain.TransactionRequest;
import com.banking.txn.domain.TransactionResult;
import com.banking.txn.domain.TransactionStatus;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the invariants hold when many threads hit the same accounts at once.
 * A CountDownLatch releases all threads at the same moment to maximise contention.
 */
class ConcurrencyTest extends IntegrationTest {

    @Autowired
    private TransactionService service;

    /**
     * The mandatory scenario from the assignment: balance ₹10,000, concurrent withdrawals
     * of ₹7,000 and ₹5,000. Exactly one may succeed. Repeated to show it is not luck.
     */
    @RepeatedTest(50)
    void onlyOneOfTwoConcurrentWithdrawalsSucceeds() throws Exception {
        createAccount("ACC1000001", "10000.00");

        List<TransactionResult> results = runConcurrently(2, List.of(
                TransactionRequest.withdrawal("TXN-A", "ACC1000001", new BigDecimal("7000")),
                TransactionRequest.withdrawal("TXN-B", "ACC1000001", new BigDecimal("5000"))));

        assertThat(countByStatus(results, TransactionStatus.SUCCESS)).isEqualTo(1);
        TransactionResult loser = results.stream()
                .filter(r -> r.status() == TransactionStatus.REJECTED).findFirst().orElseThrow();
        assertThat(loser.errorCategory()).isEqualTo(ErrorCategory.INSUFFICIENT_BALANCE);

        BigDecimal finalBalance = balanceOf("ACC1000001");
        assertThat(finalBalance).isIn(new BigDecimal("3000.00"), new BigDecimal("5000.00"));
        assertThat(finalBalance.signum()).isGreaterThanOrEqualTo(0);
    }

    /** 50 withdrawals of ₹300 from ₹10,000 on 10 threads: exactly 33 fit, ₹100 must remain. No lost updates. */
    @Test
    void manyConcurrentWithdrawalsNeverOverdraw() throws Exception {
        createAccount("ACC1000001", "10000.00");
        List<TransactionRequest> requests = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            requests.add(TransactionRequest.withdrawal("TXN-W" + i, "ACC1000001", new BigDecimal("300")));
        }

        List<TransactionResult> results = runConcurrently(10, requests);

        assertThat(countByStatus(results, TransactionStatus.SUCCESS)).isEqualTo(33);
        assertThat(countByStatus(results, TransactionStatus.REJECTED)).isEqualTo(17);
        assertThat(balanceOf("ACC1000001")).isEqualByComparingTo("100.00");
    }

    /** The same transaction id sent by 10 threads at once changes the balance exactly once. */
    @Test
    void concurrentDuplicatesMoveMoneyOnlyOnce() throws Exception {
        createAccount("ACC1000001", "1000.00");
        List<TransactionRequest> requests = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            requests.add(TransactionRequest.deposit("TXN-SAME", "ACC1000001", new BigDecimal("100")));
        }

        List<TransactionResult> results = runConcurrently(10, requests);

        assertThat(countByStatus(results, TransactionStatus.SUCCESS)).isEqualTo(1);
        assertThat(results).filteredOn(r -> r.status() == TransactionStatus.REJECTED)
                .allMatch(r -> r.errorCategory() == ErrorCategory.DUPLICATE_TRANSACTION)
                .hasSize(9);
        assertThat(balanceOf("ACC1000001")).isEqualByComparingTo("1100.00");
        assertThat(count("SELECT count(*) FROM transaction WHERE transaction_id = 'TXN-SAME'")).isEqualTo(1);
    }

    /**
     * Opposite transfers (A->B and B->A) at the same time are the classic deadlock.
     * With the deterministic lock order there is none: every transfer succeeds on its
     * first attempt, and the total amount of money is unchanged.
     */
    @Test
    void oppositeTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        createAccount("ACC1000001", "100000.00");
        createAccount("ACC1000002", "100000.00");
        List<TransactionRequest> requests = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean forward = i % 2 == 0;
            requests.add(TransactionRequest.transfer("TXN-X" + i,
                    forward ? "ACC1000001" : "ACC1000002",
                    forward ? "ACC1000002" : "ACC1000001",
                    new BigDecimal("10")));
        }

        List<TransactionResult> results = runConcurrently(20, requests);

        assertThat(countByStatus(results, TransactionStatus.SUCCESS)).isEqualTo(200);
        assertThat(balanceOf("ACC1000001").add(balanceOf("ACC1000002"))).isEqualByComparingTo("200000.00");
        assertThat(count("SELECT count(*) FROM transaction_audit WHERE attempt > 1")).isZero();
    }

    /** Submits every request to a fixed pool, releases them together, waits for all results. */
    private List<TransactionResult> runConcurrently(int threads, List<TransactionRequest> requests) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startSignal = new CountDownLatch(1);
        try {
            List<Future<TransactionResult>> futures = new ArrayList<>();
            for (TransactionRequest request : requests) {
                futures.add(pool.submit(() -> {
                    startSignal.await();
                    return service.process(request);
                }));
            }
            startSignal.countDown();

            List<TransactionResult> results = new ArrayList<>();
            for (Future<TransactionResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long countByStatus(List<TransactionResult> results, TransactionStatus status) {
        return results.stream().filter(r -> r.status() == status).count();
    }
}
