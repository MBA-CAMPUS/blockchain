/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.eth.transactions.sorter;

import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ADDED;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.ALREADY_KNOWN;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER;
import static org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult.REJECTED_UNDERPRICED_REPLACEMENT;
import static org.hyperledger.besu.ethereum.eth.transactions.sorter.SequencedRemovalReason.INVALID;
import static org.hyperledger.besu.ethereum.eth.transactions.sorter.SequencedRemovalReason.REPLACED;
import static org.hyperledger.besu.ethereum.eth.transactions.sorter.SequencedRemovalReason.TIMED_EVICTION;

import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.eth.transactions.BlobCache;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransaction;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionAddedListener;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactionDroppedListener;
import org.hyperledger.besu.ethereum.eth.transactions.PendingTransactions;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionAddedResult;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolConfiguration;
import org.hyperledger.besu.ethereum.eth.transactions.TransactionPoolReplacementHandler;
import org.hyperledger.besu.ethereum.mainnet.feemarket.FeeMarket;
import org.hyperledger.besu.ethereum.rlp.BytesValueRLPOutput;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.AccountState;
import org.hyperledger.besu.metrics.BesuMetricCategory;
import org.hyperledger.besu.plugin.data.TransactionSelectionResult;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.LabelledMetric;
import org.hyperledger.besu.util.EventBus;
import org.hyperledger.besu.util.PoolFullEvent;
import org.hyperledger.besu.util.SpaceUsedReset;
import org.hyperledger.besu.util.Subscribers;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Holds the current set of pending transactions with the ability to iterate them based on priority
 * for mining or look-up by hash.
 *
 * <p>This class is safe for use across multiple threads.
 */
public abstract class AbstractPendingTransactionsSorter implements PendingTransactions {
  private static final Logger LOG =
      LoggerFactory.getLogger(AbstractPendingTransactionsSorter.class);
  private static final Marker INVALID_TX_REMOVED = MarkerFactory.getMarker("INVALID_TX_REMOVED");

  protected final Clock clock;
  protected final TransactionPoolConfiguration poolConfig;

  protected final Object lock = new Object();
  protected final Map<Hash, PendingTransaction> pendingTransactions;

  protected final Map<Address, PendingTransactionsForSender> transactionsBySender =
      new ConcurrentHashMap<>();

  protected final Subscribers<PendingTransactionAddedListener> pendingTransactionSubscribers =
      Subscribers.create();

  protected final Subscribers<PendingTransactionDroppedListener> transactionDroppedListeners =
      Subscribers.create();

  protected final LabelledMetric<Counter> transactionRemovedCounter;
  protected final Counter localTransactionAddedCounter;
  protected final Counter remoteTransactionAddedCounter;

  protected final TransactionPoolReplacementHandler transactionReplacementHandler;
  protected final Supplier<BlockHeader> chainHeadHeaderSupplier;

  private final BlobCache blobCache;

  public static long spaceUsed = 0;
  protected Boolean PoolEventTrigger = true;

  public AbstractPendingTransactionsSorter(
      final TransactionPoolConfiguration poolConfig,
      final Clock clock,
      final MetricsSystem metricsSystem,
      final Supplier<BlockHeader> chainHeadHeaderSupplier) {

        EventBus.register(event -> {
          if (event instanceof SpaceUsedReset) {
            SpaceUsedDataReset();
          }
      });

    this.poolConfig = poolConfig;
    this.pendingTransactions = new ConcurrentHashMap<>(poolConfig.getTxPoolMaxSize());
    this.clock = clock;
    this.chainHeadHeaderSupplier = chainHeadHeaderSupplier;
    this.transactionReplacementHandler =
        new TransactionPoolReplacementHandler(
            poolConfig.getPriceBump(), poolConfig.getBlobPriceBump());
    final LabelledMetric<Counter> transactionAddedCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "transactions_added_total",
            "Count of transactions added to the transaction pool",
            "source");
    localTransactionAddedCounter = transactionAddedCounter.labels("local");
    remoteTransactionAddedCounter = transactionAddedCounter.labels("remote");

    transactionRemovedCounter =
        metricsSystem.createLabelledCounter(
            BesuMetricCategory.TRANSACTION_POOL,
            "transactions_removed_total",
            "Count of transactions removed from the transaction pool",
            "source",
            "operation");

    metricsSystem.createIntegerGauge(
        BesuMetricCategory.TRANSACTION_POOL,
        "transactions",
        "Current size of the transaction pool",
        pendingTransactions::size);

    this.blobCache = new BlobCache();
  }

  @Override
  public void reset() {
    pendingTransactions.clear();
    transactionsBySender.clear();
  }

  @Override
  public void evictOldTransactions() {
    final long removeTransactionsBefore =
        clock
            .instant()
            .minus(poolConfig.getPendingTxRetentionPeriod(), ChronoUnit.HOURS)
            .toEpochMilli();

    pendingTransactions.values().stream()
        .filter(transaction -> transaction.getAddedAt() < removeTransactionsBefore)
        .forEach(
            transactionInfo -> {
              LOG.atTrace()
                  .setMessage("Evicted {} due to age")
                  .addArgument(transactionInfo::toTraceLog)
                  .log();
              removeTransaction(transactionInfo.getTransaction(), TIMED_EVICTION);
            });
  }

  @Override
  public List<Transaction> getLocalTransactions() {
    return pendingTransactions.values().stream()
        .filter(PendingTransaction::isReceivedFromLocalSource)
        .map(PendingTransaction::getTransaction)
        .collect(Collectors.toList());
  }

  @Override
  public List<Transaction> getPriorityTransactions() {
    return pendingTransactions.values().stream()
        .filter(PendingTransaction::hasPriority)
        .map(PendingTransaction::getTransaction)
        .collect(Collectors.toList());
  }

  @Override
  public TransactionAddedResult addTransaction(
      final PendingTransaction transaction, final Optional<Account> maybeSenderAccount) {

    // internalAddTransaction이라는 내부 메서드를 호출해 트랜잭션을 풀에 추가하고, 그 결과를 transactionAddedStatus 변수에 저장
    final TransactionAddedResult transactionAddedStatus = internalAddTransaction(transaction, maybeSenderAccount);
    // ADDED가 반환된 경우 트랜잭션이 성공적으로 추가
    if (transactionAddedStatus.equals(ADDED)) {
      // 트랜잭션이 로컬에서 생성되지 않은 경우
      if (!transaction.isReceivedFromLocalSource()) {
        // 원격 트랜잭션 카운터 증가
        remoteTransactionAddedCounter.inc();
        // 트랜잭션이 로컬에서 생성된 경우
      } else {
        // 로컬 트랜잭션 카운터 증가
        localTransactionAddedCounter.inc();
      }
    }
    return transactionAddedStatus;
  }

  void removeTransaction(final Transaction transaction, final SequencedRemovalReason reason) {
    removeTransaction(transaction, false);
    notifyTransactionDropped(transaction, reason);
    System.out.println("삭제 이유 : " + reason);
  }

  @Override
  public void manageBlockAdded(
      final BlockHeader blockHeader,
      final List<Transaction> confirmedTransactions,
      final List<Transaction> reorgTransactions,
      final FeeMarket feeMarket) {
    synchronized (lock) {
      confirmedTransactions.forEach(this::transactionAddedToBlock);
      manageBlockAdded(blockHeader);
    }
  }

  protected abstract void manageBlockAdded(final BlockHeader blockHeader);

  public void transactionAddedToBlock(final Transaction transaction) {
    removeTransaction(transaction, true);
  }

  private void incrementTransactionRemovedCounter(
      final boolean receivedFromLocalSource, final boolean addedToBlock) {
    final String location = receivedFromLocalSource ? "local" : "remote";
    final String operation = addedToBlock ? "addedToBlock" : "dropped";
    transactionRemovedCounter.labels(location, operation).inc();
  }

  // There's a small edge case here we could encounter.
  // When we pass an upgrade block that has a new transaction type, we start allowing transactions
  // of that new type into our pool.
  // If we then reorg to a block lower than the upgrade block height _and_ we create a block, that
  // block could end up with transactions of the new type.
  // This seems like it would be very rare but worth it to document that we don't handle that case
  // right now.
  @Override
  public void selectTransactions(final TransactionSelector selector) {
    synchronized (lock) {
      final Set<Transaction> transactionsToRemove = new HashSet<>();
      final Map<Address, AccountTransactionOrder> accountTransactions = new HashMap<>();
      final Iterator<PendingTransaction> prioritizedTransactions = prioritizedTransactions();
      while (prioritizedTransactions.hasNext()) {
        final PendingTransaction highestPriorityPendingTransaction = prioritizedTransactions.next();
        final AccountTransactionOrder accountTransactionOrder =
            accountTransactions.computeIfAbsent(
                highestPriorityPendingTransaction.getSender(), this::createSenderTransactionOrder);

        for (final PendingTransaction transactionToProcess :
            accountTransactionOrder.transactionsToProcess(highestPriorityPendingTransaction)) {
          final TransactionSelectionResult result =
              selector.evaluateTransaction(transactionToProcess);

          if (result.discard()) {
            transactionsToRemove.add(transactionToProcess.getTransaction());
            logDiscardedTransaction(transactionToProcess, result);
          }

          if (result.stop()) {
            transactionsToRemove.forEach(tx -> removeTransaction(tx, INVALID));
            return;
          }
        }
      }
      transactionsToRemove.forEach(tx -> removeTransaction(tx, INVALID));
    }
  }

  private void logDiscardedTransaction(
      final PendingTransaction pendingTransaction, final TransactionSelectionResult result) {
    LOG.atInfo()
        .addMarker(INVALID_TX_REMOVED)
        .addKeyValue("txhash", pendingTransaction::getHash)
        .addKeyValue("txlog", pendingTransaction::toTraceLog)
        .addKeyValue("reason", result)
        .addKeyValue(
            "txrlp",
            () -> {
              final BytesValueRLPOutput rlp = new BytesValueRLPOutput();
              pendingTransaction.getTransaction().writeTo(rlp);
              return rlp.encoded().toHexString();
            })
        .log();
  }

  private AccountTransactionOrder createSenderTransactionOrder(final Address address) {
    return new AccountTransactionOrder(
        transactionsBySender.get(address).streamPendingTransactions());
  }

  private TransactionAddedResult addTransactionForSenderAndNonce(final PendingTransaction pendingTransaction, final Optional<Account> maybeSenderAccount) {
    // 송신자의 주소에 연결된 PendingTransactionsForSender 객체를 가져오거나 새로 생성
    PendingTransactionsForSender pendingTxsForSender =
        transactionsBySender.computeIfAbsent(
            pendingTransaction.getSender(),
            address -> new PendingTransactionsForSender(maybeSenderAccount));

    // 해당 송신자와 동일한 논스를 가진 대기 중인 트랜잭션이 있는 지 확인
    PendingTransaction existingPendingTx =
        pendingTxsForSender.getPendingTransactionForNonce(pendingTransaction.getNonce());

    // 교체될 가능성이 있는 트랜잭션을 담을 변수 선언
    final Optional<Transaction> maybeReplacedTransaction;
    // 같은 논스를 가진 트랜잭션이 존재하는 경우
    if (existingPendingTx != null) {
      // 가스 가격을 비교하여 새로운 트랜잭션이 기존 트랜잭션을 대체할 수 있는 지 판단
      // 교체하지 않는다면 로그를 남기고 결과 반환
      if (!transactionReplacementHandler.shouldReplace(
          existingPendingTx, pendingTransaction, chainHeadHeaderSupplier.get())) {
        LOG.atTrace()
            .setMessage("Reject underpriced transaction replacement {}")
            .addArgument(pendingTransaction::toTraceLog)
            .log();
        return REJECTED_UNDERPRICED_REPLACEMENT;
      }
      // 교체한다면 새 트랜잭션으로 대체할 것을 로그로 남김
      LOG.atTrace()
          .setMessage("Replace existing transaction {}, with new transaction {}")
          .addArgument(existingPendingTx::toTraceLog)
          .addArgument(pendingTransaction::toTraceLog)
          .log();
      // 교체될 가능성이 있는 변수에 기존 트랜잭션으로 설정
      maybeReplacedTransaction = Optional.of(existingPendingTx.getTransaction());
      // 같은 논스를 가진 트랜잭션이 존재하지 않는 경우
    } else {
      // 교체될 가능성이 있는 변수를 비워둠
      maybeReplacedTransaction = Optional.empty();
    }

    // 송신자 계정 정보 업데이트
    pendingTxsForSender.updateSenderAccount(maybeSenderAccount);
    // pendingTransaction을 trackPendingTransaction에 추가
    pendingTxsForSender.trackPendingTransaction(pendingTransaction);
    // 트랜잭션 추가 성공 로그
    LOG.atTrace()
        .setMessage("Tracked transaction by sender {}")
        .addArgument(pendingTxsForSender::toTraceLog)
        .log();
    maybeReplacedTransaction.ifPresent(tx -> removeTransaction(tx, REPLACED));
    return ADDED;
  }

  private void removePendingTransactionBySenderAndNonce(
      final PendingTransaction pendingTransaction) {
    final Transaction transaction = pendingTransaction.getTransaction();
    Optional.ofNullable(transactionsBySender.get(transaction.getSender()))
        .ifPresent(
            pendingTxsForSender -> {
              pendingTxsForSender.removeTrackedPendingTransaction(pendingTransaction);
              if (pendingTxsForSender.transactionCount() == 0) {
                LOG.trace(
                    "Removing sender {} from transactionBySender since no more tracked transactions",
                    transaction.getSender());
                transactionsBySender.remove(transaction.getSender());
              } else {
                LOG.atTrace()
                    .setMessage("Tracked transaction by sender {} after the removal of {}")
                    .addArgument(pendingTxsForSender::toTraceLog)
                    .addArgument(transaction::toTraceLog)
                    .log();
              }
            });
  }

  private void notifyTransactionAdded(final Transaction transaction) {
    pendingTransactionSubscribers.forEach(listener -> listener.onTransactionAdded(transaction));
  }

  private void notifyTransactionDropped(
      final Transaction transaction, final SequencedRemovalReason reason) {
    transactionDroppedListeners.forEach(
        listener -> listener.onTransactionDropped(transaction, reason));
  }

  @Override
  public long maxSize() {
    return poolConfig.getTxPoolMaxSize();
  }

  @Override
  public int size() {
    return pendingTransactions.size();
  }

  @Override
  public boolean containsTransaction(final Transaction transaction) {
    return pendingTransactions.containsKey(transaction.getHash());
  }

  @Override
  public Optional<Transaction> getTransactionByHash(final Hash transactionHash) {
    return Optional.ofNullable(pendingTransactions.get(transactionHash))
        .map(PendingTransaction::getTransaction);
  }

  @Override
  public List<PendingTransaction> getPendingTransactions() {
    return new ArrayList<>(pendingTransactions.values());
  }

  @Override
  public long subscribePendingTransactions(final PendingTransactionAddedListener listener) {
    return pendingTransactionSubscribers.subscribe(listener);
  }

  @Override
  public void unsubscribePendingTransactions(final long id) {
    pendingTransactionSubscribers.unsubscribe(id);
  }

  @Override
  public long subscribeDroppedTransactions(final PendingTransactionDroppedListener listener) {
    return transactionDroppedListeners.subscribe(listener);
  }

  @Override
  public void unsubscribeDroppedTransactions(final long id) {
    transactionDroppedListeners.unsubscribe(id);
  }

  @Override
  public OptionalLong getNextNonceForSender(final Address sender) {
    final PendingTransactionsForSender pendingTransactionsForSender =
        transactionsBySender.get(sender);
    return pendingTransactionsForSender == null
        ? OptionalLong.empty()
        : pendingTransactionsForSender.maybeNextNonce();
  }

  private void removeTransaction(final Transaction transaction, final boolean addedToBlock) {
    synchronized (lock) {
      final PendingTransaction removedPendingTx = pendingTransactions.remove(transaction.getHash());
      if (removedPendingTx != null) {
        removePrioritizedTransaction(removedPendingTx);
        removePendingTransactionBySenderAndNonce(removedPendingTx);
        incrementTransactionRemovedCounter(
            removedPendingTx.isReceivedFromLocalSource(), addedToBlock);
        if (removedPendingTx.getTransaction().getBlobsWithCommitments().isPresent()
            && addedToBlock) {
          this.blobCache.cacheBlobs(removedPendingTx.getTransaction());
        }
      }
    }
  }

  protected abstract void removePrioritizedTransaction(PendingTransaction removedPendingTx);

  protected abstract Iterator<PendingTransaction> prioritizedTransactions();

  protected abstract void prioritizeTransaction(final PendingTransaction pendingTransaction);

  private TransactionAddedResult internalAddTransaction(
      final PendingTransaction pendingTransaction, final Optional<Account> maybeSenderAccount) {
    // 보류 트랜잭션(pendingTransaction) 객체에서 트랜잭션(Transaction) 객체를 주출
    final Transaction transaction = pendingTransaction.getTransaction();
    // 하나의 스레드만 이 객체에 접근하도록 잠금
    synchronized (lock) {
      // 보류 트랜잭션 리스트에 처리할 보류 트랜잭션이 포함되어 있는 지 확인
      // 동일한 트랜잭션이 있다면 로그를 남기고 종료
      if (pendingTransactions.containsKey(pendingTransaction.getHash())) {
        LOG.atTrace()
            .setMessage("Already known transaction {}")
            .addArgument(pendingTransaction::toTraceLog)
            .log();
        return ALREADY_KNOWN;
      }
      // 트랜잭션의 논스가 허용하는 범위 안에 있는지 확인
      // 트랜잭션 논스가 너무 높다면 로그를 남기고 종료
      if (transaction.getNonce() - maybeSenderAccount.map(AccountState::getNonce).orElse(0L)
          >= poolConfig.getTxPoolMaxFutureTransactionByAccount()) {
        LOG.atTrace()
            .setMessage(
                "Transaction {} not added because nonce too far in the future for sender {}")
            .addArgument(transaction::toTraceLog)
            .addArgument(
                () ->
                    maybeSenderAccount
                        .map(Account::getAddress)
                        .map(Address::toString)
                        .orElse("unknown"))
            .log();
        return NONCE_TOO_FAR_IN_FUTURE_FOR_SENDER;
      }

      // pendingTransaction과 트랜잭션 보내는 사람의 정보를 기반으로 트랜잭션을 추가, 결과를 transactionAddedStatus에 저장
      final TransactionAddedResult transactionAddedStatus = addTransactionForSenderAndNonce(pendingTransaction, maybeSenderAccount);

      // 결과가 ADDED(추가됨)이 아닌 경우 결과를 반환
      if (!transactionAddedStatus.equals(ADDED)) {
        return transactionAddedStatus;
      }

      // pendingTransaction 리스트에 pendingTransaction을 추가하고 트랜잭션의 해시를 키값으로 사용 <- 트랜잭션 풀에 추가
      pendingTransactions.put(pendingTransaction.getHash(), pendingTransaction);
      // 트랜잭션 우선순위 설정 - 가스 기반, 기본 수수료 기반
      prioritizeTransaction(pendingTransaction);
      increaseCounters(pendingTransaction);

      if (maybeFull()) {
        PoolEventTrigger = false;
        EventBus.post(new PoolFullEvent());
      } else {
        PoolEventTrigger = true;
      }
    }
    // 트랜잭션이 추가된 것을 리스너에게 알림
    notifyTransactionAdded(pendingTransaction.getTransaction());
    return ADDED;
  }

  protected void increaseCounters(final PendingTransaction pendingTransaction) {
    // 새로운 트랜잭션의 크기만큼 사용중인 메모리에 추가
    spaceUsed += pendingTransaction.memorySize();
  }

  private boolean maybeFull() {
    // EventQueue에 한번 용량 초과로 들어갔다면 기준 이하의 용량이 될 때까지 이 클래스에서는 큐 삽입 이벤트 제한
    // 큐 삽입 이벤트 제한이 될 경우 용량 검증 로직은 반드시 false를 반환함
    // true : 큐 삽입 이벤트 가능
    // false : 큐 삽입 이벤트 불가능
    if (PoolEventTrigger) {
      // 현재 사용 가능한 메모리 여유 공간 조회 (byte 단위)
      final long cacheFreeSpace = cacheFreeSpace();
      // 메모리 여유 공간이 0보다 작다면
      if (cacheFreeSpace < 12490000) {
        // 필요한 공간 또는 제거가 필요한 거래 수에 대한 로그 출력
        // ex) Layer full: need to free 50 space
        LOG.atDebug()
            .setMessage("Layer full: {}")
            .addArgument(
                () ->
                    "need to free " + (-cacheFreeSpace) + " space" )
            .log();
        // 메모리 여유 공간이 없다면 true 반환
        return true;
      } else {
        // 메모리 여유 공간이 있다면 false 반환
        return false;
      }
    } else {
      // 메모리 여유 공간이 있다면 false 반환
      return false;
    }
  }

  protected long cacheFreeSpace() {
    // poolConfig : 트랜잭션 풀 설정 데이터
	  // getPendingTransactionsLayerMaxCapacityBytes() : 사용 가능한 최대 메모리 용량
	  // getSpaceUsed() : 현재 사용 중인 메모리 용량
	  // 최대 메모리 용량 - 사용 중인 메모리 용량 = 남은 메모리 용량
	  // 남은 메모리 용량을 계산하여 반환
    System.out.println("블록 제출 기준 용량 : 11000" + ", 사용중인 메모리 용량 : " + getSpaceUsed());
    return poolConfig.getPendingTransactionsLayerMaxCapacityBytes() - getSpaceUsed();
  }

  protected long getSpaceUsed() {
    return spaceUsed;
  }

  protected abstract PendingTransaction getLeastPriorityTransaction();

  @Override
  public String logStats() {
    return "Pending " + pendingTransactions.size();
  }

  @Override
  public String toTraceLog() {
    synchronized (lock) {
      StringBuilder sb =
          new StringBuilder(
              "Prioritized transactions { "
                  + StreamSupport.stream(
                          Spliterators.spliteratorUnknownSize(
                              prioritizedTransactions(), Spliterator.ORDERED),
                          false)
                      .map(PendingTransaction::toTraceLog)
                      .collect(Collectors.joining("; "))
                  + " }");

      return sb.toString();
    }
  }

  /**
   * @param transaction to restore blobs onto
   * @return an optional copy of the supplied transaction, but with the BlobsWithCommitments
   *     restored. If none could be restored, empty.
   */
  @Override
  public Optional<Transaction> restoreBlob(final Transaction transaction) {
    return blobCache.restoreBlob(transaction);
  }

  public void SpaceUsedDataReset() {
    spaceUsed = 0;
  }
}
