/*
 * Copyright ConsenSys AG.
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
package org.hyperledger.besu.consensus.qbft.statemachine;

import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier;
import org.hyperledger.besu.consensus.common.bft.events.RoundExpiry;
import org.hyperledger.besu.consensus.common.bft.messagewrappers.BftMessage;
import org.hyperledger.besu.consensus.common.bft.payload.Payload;
import org.hyperledger.besu.consensus.common.bft.statemachine.BftFinalState;
import org.hyperledger.besu.consensus.qbft.messagewrappers.Commit;
import org.hyperledger.besu.consensus.qbft.messagewrappers.Prepare;
import org.hyperledger.besu.consensus.qbft.messagewrappers.Proposal;
import org.hyperledger.besu.consensus.qbft.messagewrappers.RoundChange;
import org.hyperledger.besu.consensus.qbft.network.QbftMessageTransmitter;
import org.hyperledger.besu.consensus.qbft.payload.MessageFactory;
import org.hyperledger.besu.consensus.qbft.validation.FutureRoundProposalMessageValidator;
import org.hyperledger.besu.consensus.qbft.validation.MessageValidatorFactory;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.plugin.services.securitymodule.SecurityModuleException;
import org.hyperledger.besu.util.EventBus;
import org.hyperledger.besu.util.SpaceUsedReset;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsible for starting/clearing Consensus rounds at a given block height. One of these is
 * created when a new block is imported to the chain. It immediately then creates a Round-0 object,
 * and sends a Proposal message. If the round times out prior to importing a block, this class is
 * responsible for creating a RoundChange message and transmitting it.
 */
public class QbftBlockHeightManager implements BaseQbftBlockHeightManager {

  private static final Logger LOG = LoggerFactory.getLogger(QbftBlockHeightManager.class);

  private final QbftRoundFactory roundFactory;
  private final RoundChangeManager roundChangeManager;
  private final BlockHeader parentHeader;
  private final QbftMessageTransmitter transmitter;
  private final MessageFactory messageFactory;
  private final Map<Integer, RoundState> futureRoundStateBuffer = Maps.newHashMap();
  private final FutureRoundProposalMessageValidator futureRoundProposalMessageValidator;
  private final Clock clock;
  private final Function<ConsensusRoundIdentifier, RoundState> roundStateCreator;
  private final BftFinalState finalState;

  private Optional<PreparedCertificate> latestPreparedCertificate = Optional.empty();
  private Optional<QbftRound> currentRound = Optional.empty();

  /**
   * Instantiates a new Qbft block height manager.
   *
   * @param parentHeader the parent header
   * @param finalState the final state
   * @param roundChangeManager the round change manager
   * @param qbftRoundFactory the qbft round factory
   * @param clock the clock
   * @param messageValidatorFactory the message validator factory
   * @param messageFactory the message factory
   */
  public QbftBlockHeightManager(
      final BlockHeader parentHeader,
      final BftFinalState finalState,
      final RoundChangeManager roundChangeManager,
      final QbftRoundFactory qbftRoundFactory,
      final Clock clock,
      final MessageValidatorFactory messageValidatorFactory,
      final MessageFactory messageFactory) {
    this.parentHeader = parentHeader;
    this.roundFactory = qbftRoundFactory;
    this.transmitter = new QbftMessageTransmitter(messageFactory, finalState.getValidatorMulticaster());
    this.messageFactory = messageFactory;
    this.clock = clock;
    this.roundChangeManager = roundChangeManager;
    this.finalState = finalState;

    futureRoundProposalMessageValidator =
        messageValidatorFactory.createFutureRoundProposalMessageValidator(
            getChainHeight(), parentHeader);

    roundStateCreator =
        (roundIdentifier) ->
            new RoundState(
                roundIdentifier,
                finalState.getQuorum(),
                messageValidatorFactory.createMessageValidator(roundIdentifier, parentHeader));

    final long nextBlockHeight = parentHeader.getNumber() + 1;
    final ConsensusRoundIdentifier roundIdentifier =
        new ConsensusRoundIdentifier(nextBlockHeight, 0);

    finalState.getBlockTimer().startTimer(roundIdentifier, parentHeader);
  }

  @Override
  public void handleBlockTimerExpiry(final ConsensusRoundIdentifier roundIdentifier) {
    if (currentRound.isPresent()) {
      // It is possible for the block timer to take longer than it should due to the precision of
      // the timer in Java and the OS. This means occasionally the proposal can arrive before the
      // block timer expiry and hence the round has already been set. There is no negative impact
      // on the protocol in this case.
      // Java 및 운영 체제(OS)의 타이머 정밀도로 인해 블록 타이머가 예상보다 오래 걸릴 수 있습니다.
      // 이는 가끔 제안 메시지가 블록 타이머 만료 전에 도착하여 이미 라운드가 설정된 경우를 의미합니다.
      // 이 경우 프로토콜에 부정적인 영향은 없습니다.
      return;
    }

    startNewRound(0);

    final QbftRound qbftRound = currentRound.get();

    logValidatorChanges(qbftRound);

    if (roundIdentifier.equals(qbftRound.getRoundIdentifier())) {
      buildBlockAndMaybePropose(roundIdentifier, qbftRound);
    } else {
      LOG.trace(
          "Block timer expired for a round ({}) other than current ({})",
          roundIdentifier,
          qbftRound.getRoundIdentifier());
    }
  }

  @Override
  public void poolCapacityFulfilled(final ConsensusRoundIdentifier roundIdentifier) {
    if (currentRound.isPresent()) {
      // Java 및 운영 체제(OS)의 타이머 정밀도로 인해 블록 타이머가 예상보다 오래 걸릴 수 있습니다.
      // 이는 가끔 제안 메시지가 블록 타이머 만료 전에 도착하여 이미 라운드가 설정된 경우를 의미합니다.
      // 이 경우 프로토콜에 부정적인 영향은 없습니다.
      return;
    }

    EventBus.post(new SpaceUsedReset());
    startNewRound(0);

    final QbftRound qbftRound = currentRound.get();

    logValidatorChanges(qbftRound);

    if (roundIdentifier.equals(qbftRound.getRoundIdentifier())) {
      buildBlockAndMaybePropose_pool(roundIdentifier, qbftRound);
    } else {
      LOG.trace(
          "Transaction Pool Fulfilled for a round ({}) other than current ({})",
          roundIdentifier,
          qbftRound.getRoundIdentifier());
    }
  }

  private void buildBlockAndMaybePropose(
      final ConsensusRoundIdentifier roundIdentifier, final QbftRound qbftRound) {

    // mining will be checked against round 0 as the current round is initialised to 0 above
    // 채굴은 현재 라운드가 위에서 0으로 초기화되었기 때문에 라운드 0에 대해 확인됩니다.
    // 현재 노드가 지정된 라운드에서 블록을 제안할 권한이 있는지 확인합니다.
    final boolean isProposer = finalState.isLocalNodeProposerForRound(qbftRound.getRoundIdentifier());

    // 제안자가 아니라면 아무 작업도 수행하지 않고 로그를 남기고 종료합니다.
    if (!isProposer) {
      // nothing to do here...
      LOG.trace("This node is not a proposer so it will not send a proposal: " + roundIdentifier);
      return;
    }

    // 현재 시간을 초 단위로 변환하여 블록 헤더에 사용할 타임스탬프를 계산합니다.
    final long headerTimeStampSeconds = Math.round(clock.millis() / 1000D);
    // 지정된 타임스탬프를 사용하여 새 블록을 생성합니다.
    final Block block = qbftRound.createBlock(headerTimeStampSeconds);
    // 생성된 블록에 트랜잭션이 포함되어 있는지 확인합니다.
    final boolean blockHasTransactions = !block.getBody().getTransactions().isEmpty();
    // 블록에 트랜잭션이 포함되어 있으면 상태를 업데이트하고 제안 메시지를 네트워크에 전송합니다.
    if (blockHasTransactions) {
      LOG.trace(
          "Block has transactions and this node is a proposer so it will send a proposal: "
              + roundIdentifier);
      qbftRound.updateStateWithProposalAndTransmit(block);

    } else {
      // handle the block times period
      // 빈 블록 처리 시간을 확인합니다.
      final long currentTimeInMillis = finalState.getClock().millis();
      // 빈 블록 생성 제한 시간이 만료되었는지 확인합니다.
      boolean emptyBlockExpired = finalState.getBlockTimer().checkEmptyBlockExpired(parentHeader, currentTimeInMillis);
      // 제한 시간이 만료된 경우 상태를 업데이트하고 빈 블록을 제안합니다.
      if (emptyBlockExpired) {
        LOG.trace(
            "Block has no transactions and this node is a proposer so it will send a proposal: "
                + roundIdentifier);
        qbftRound.updateStateWithProposalAndTransmit(block);
      // 빈 블록 제한 시간이 만료되지 않은 경우 타이머를 재설정합니다.
      } else {
        LOG.trace(
            "Block has no transactions but emptyBlockPeriodSeconds did not expired yet: "
                + roundIdentifier);
        finalState
            .getBlockTimer()
            .resetTimerForEmptyBlock(roundIdentifier, parentHeader, currentTimeInMillis);
        finalState.getRoundTimer().cancelTimer();
        currentRound = Optional.empty();
      }
    }
  }

  private void buildBlockAndMaybePropose_pool(final ConsensusRoundIdentifier roundIdentifier, final QbftRound qbftRound) {
  // mining will be checked against round 0 as the current round is initialised to 0 above
  // 채굴은 현재 라운드가 위에서 0으로 초기화되었기 때문에 라운드 0에 대해 확인됩니다.
  // 현재 노드가 지정된 라운드에서 블록을 제안할 권한이 있는지 확인합니다.
  final boolean isProposer = finalState.isLocalNodeProposerForRound(qbftRound.getRoundIdentifier());

  // 제안자가 아니라면 아무 작업도 수행하지 않고 로그를 남기고 종료합니다.
  if (!isProposer) {
    // nothing to do here...
    System.out.println("해당 노드는 현재 블록 제안자가 아닙니다." + roundIdentifier);
    LOG.trace("This node is not a proposer so it will not send a proposal: " + roundIdentifier);
    return;
  }

  // 라운드 타이머 종료
  finalState.getRoundTimer().cancelTimer();
  // 현재 시간을 초 단위로 변환하여 블록 헤더에 사용할 타임스탬프를 계산합니다.
  final long headerTimeStampSeconds = Math.round(clock.millis() / 1000D);
  // 지정된 타임스탬프를 사용하여 새 블록을 생성합니다.
  final Block block = qbftRound.createBlock(headerTimeStampSeconds);
  // 블록에 트랜잭션이 포함되어 있으면 상태를 업데이트하고 제안 메시지를 네트워크에 전송합니다.
  System.out.println("트랜잭션 풀 충족 - 블록 제안");
  qbftRound.updateStateWithProposalAndTransmit(block);
}

  /**
   * If the list of validators for the next block to be proposed/imported has changed from the
   * previous block, log the change. Only log for round 0 (i.e. once per block).
   *
   * @param qbftRound The current round
   */
  private void logValidatorChanges(final QbftRound qbftRound) {
    if (qbftRound.getRoundIdentifier().getRoundNumber() == 0) {
      final Collection<Address> previousValidators =
          MessageValidatorFactory.getValidatorsForBlock(qbftRound.protocolContext, parentHeader);
      final Collection<Address> validatorsForHeight =
          MessageValidatorFactory.getValidatorsAfterBlock(qbftRound.protocolContext, parentHeader);
      if (!(validatorsForHeight.containsAll(previousValidators))
          || !(previousValidators.containsAll(validatorsForHeight))) {
        LOG.info(
            "Validator list change. Previous chain height {}: {}. Current chain height {}: {}.",
            parentHeader.getNumber(),
            previousValidators,
            parentHeader.getNumber() + 1,
            validatorsForHeight);
      }
    }
  }

  @Override
  public void roundExpired(final RoundExpiry expire) {
    if (currentRound.isEmpty()) {
      LOG.error(
          "Received Round timer expiry before round is created timerRound={}", expire.getView());
      return;
    }

    QbftRound qbftRound = currentRound.get();
    if (!expire.getView().equals(qbftRound.getRoundIdentifier())) {
      LOG.trace(
          "Ignoring Round timer expired which does not match current round. round={}, timerRound={}",
          qbftRound.getRoundIdentifier(),
          expire.getView());
      return;
    }

    LOG.debug(
        "Round has expired, creating PreparedCertificate and notifying peers. round={}",
        qbftRound.getRoundIdentifier());
    final Optional<PreparedCertificate> preparedCertificate =
        qbftRound.constructPreparedCertificate();

    if (preparedCertificate.isPresent()) {
      latestPreparedCertificate = preparedCertificate;
    }

    startNewRound(qbftRound.getRoundIdentifier().getRoundNumber() + 1);
    qbftRound = currentRound.get();

    try {
      final RoundChange localRoundChange =
          messageFactory.createRoundChange(
              qbftRound.getRoundIdentifier(), latestPreparedCertificate);

      // Its possible the locally created RoundChange triggers the transmission of a NewRound
      // message - so it must be handled accordingly.
      handleRoundChangePayload(localRoundChange);
    } catch (final SecurityModuleException e) {
      LOG.warn("Failed to create signed RoundChange message.", e);
    }

    transmitter.multicastRoundChange(qbftRound.getRoundIdentifier(), latestPreparedCertificate);
  }

  @Override
  public void handleProposalPayload(final Proposal proposal) {
    LOG.trace("Received a Proposal Payload.");
    final MessageAge messageAge =
        determineAgeOfPayload(proposal.getRoundIdentifier().getRoundNumber());

    if (messageAge == MessageAge.PRIOR_ROUND) {
      LOG.trace("Received Proposal Payload for a prior round={}", proposal.getRoundIdentifier());
    } else {
      if (messageAge == MessageAge.FUTURE_ROUND) {
        if (!futureRoundProposalMessageValidator.validateProposalMessage(proposal)) {
          LOG.info("Received future Proposal which is illegal, no round change triggered.");
          return;
        }
        startNewRound(proposal.getRoundIdentifier().getRoundNumber());
      }
      currentRound.ifPresent(r -> r.handleProposalMessage(proposal));
    }
  }

  @Override
  public void handlePreparePayload(final Prepare prepare) {
    LOG.trace("Received a Prepare Payload.");
    actionOrBufferMessage(
        prepare,
        currentRound.isPresent() ? currentRound.get()::handlePrepareMessage : (ignore) -> {},
        RoundState::addPrepareMessage);
  }

  @Override
  public void handleCommitPayload(final Commit commit) {
    // Commit Payload 메세지 로그 기록
    LOG.trace("Received a Commit Payload.");
    // Commit 메세지를 처리
    actionOrBufferMessage(
        commit,
        currentRound.isPresent() ? currentRound.get()::handleCommitMessage : (ignore) -> {},
        RoundState::addCommitMessage);
  }

  private <P extends Payload, M extends BftMessage<P>> void actionOrBufferMessage(
      final M qbftMessage,
      final Consumer<M> inRoundHandler,
      final BiConsumer<RoundState, M> buffer) {
    // 메세지 라운드 번호 확인
    final MessageAge messageAge = determineAgeOfPayload(qbftMessage.getRoundIdentifier().getRoundNumber());
    // 현재 라운드 메세지인 경우
    if (messageAge == MessageAge.CURRENT_ROUND) {
      // 즉시 처리
      inRoundHandler.accept(qbftMessage);
    // 미래 라운드 메세지인 경우
    } else if (messageAge == MessageAge.FUTURE_ROUND) {
      // 해당 라운드 번호의 라운드 식별자 조회
      final ConsensusRoundIdentifier msgRoundId = qbftMessage.getRoundIdentifier();
      // 라운드 스테이트 생성
      final RoundState roundstate =
          futureRoundStateBuffer.computeIfAbsent(
              msgRoundId.getRoundNumber(), k -> roundStateCreator.apply(msgRoundId));
      // 버퍼에 메세지 저장
      buffer.accept(roundstate, qbftMessage);
    }
  }

  @Override
  public void handleRoundChangePayload(final RoundChange message) {
    // 수신된 RoundChange 메세지의 라운드 식별자 조회
    final ConsensusRoundIdentifier targetRound = message.getRoundIdentifier();
    // 라운드 변경 요청을 로그를 통해 기록
    LOG.debug(
        "Round change from {}: block {}, round {}",
        message.getAuthor(),
        message.getRoundIdentifier().getSequenceNumber(),
        message.getRoundIdentifier().getRoundNumber());

    // Diagnostic logging (only logs anything if the chain has stalled)
    // 라운드 변경 메시지를 저장하고, 네트워크 상태를 진단할 수 있도록 요약 정보를 로그로 기록
    roundChangeManager.storeAndLogRoundChangeSummary(message);

    // 메시지의 타겟 라운드가 현재 라운드보다 이전인 경우, 이를 무시하고 로그로 기록
    final MessageAge messageAge = determineAgeOfPayload(message.getRoundIdentifier().getRoundNumber());
    if (messageAge == MessageAge.PRIOR_ROUND) {
      LOG.debug("Received RoundChange Payload for a prior round. targetRound={}", targetRound);
      return;
    }

    // roundChangeManager를 통해 라운드 변경 메시지를 추가하고, 필요 시 충분한 라운드 변경 메시지가 수집되었는지 확인
    final Optional<Collection<RoundChange>> result = roundChangeManager.appendRoundChangeMessage(message);

    // 라운드 변경 메시지가 합의 임계값을 충족하면, 라운드 변경 프로세스를 시작할 준비
    if (result.isPresent()) {
      LOG.debug(
          "Received sufficient RoundChange messages to change round to targetRound={}",
          targetRound);
      // 메시지가 미래 라운드에 해당하면 미래 라운드를 미리 생성
      if (messageAge == MessageAge.FUTURE_ROUND) {
        startNewRound(targetRound.getRoundNumber());
      }

      // 수집된 라운드 변경 메시지를 기반으로 새로운 라운드에 필요한 메타데이터를 생성
      final RoundChangeArtifacts roundChangeMetadata = RoundChangeArtifacts.create(result.get());

      // 로컬 노드가 타겟 라운드의 제안자(Proposer)인 경우, 새로운 라운드를 초기화하고 수집된 라운드 변경 데이터를 사용해 라운드를 시작
      if (finalState.isLocalNodeProposerForRound(targetRound)) {
        if (currentRound.isEmpty()) {
          startNewRound(0);
        }
        currentRound.get().startRoundWith(roundChangeMetadata, TimeUnit.MILLISECONDS.toSeconds(clock.millis()));
      }
    }
  }

  private void startNewRound(final int roundNumber) {
    // 새로운 라운드 시작을 로그로 기록
    LOG.debug("Starting new round {}", roundNumber);
    // validate the current round
    // 현재 라운드가 존재하는지 확인
    if (futureRoundStateBuffer.containsKey(roundNumber)) {
      // 존재할 경우, 기존 RoundState를 기반으로 새로운 라운드 생성
      currentRound = Optional.of(roundFactory.createNewRoundWithState(parentHeader, futureRoundStateBuffer.get(roundNumber)));
      // 새로운 라운드 번호 이하의 미래 메세지는 모두 정리
      futureRoundStateBuffer.keySet().removeIf(k -> k <= roundNumber);
    } else {
      // 존재하지 않을 경우, 기본 초기 상태를 가진 라운드 생성
      currentRound = Optional.of(roundFactory.createNewRound(parentHeader, roundNumber));
    }
    // discard roundChange messages from the current and previous rounds
    // 현재 라운드 이전의 모든 라운드 변경 메시지를 삭제
    roundChangeManager.discardRoundsPriorTo(currentRound.get().getRoundIdentifier());
  }

  @Override
  public long getChainHeight() {
    return parentHeader.getNumber() + 1;
  }

  @Override
  public BlockHeader getParentBlockHeader() {
    return parentHeader;
  }

  private MessageAge determineAgeOfPayload(final int messageRoundNumber) {
    final int currentRoundNumber =
        currentRound.map(r -> r.getRoundIdentifier().getRoundNumber()).orElse(-1);
    if (messageRoundNumber > currentRoundNumber) {
      return MessageAge.FUTURE_ROUND;
    } else if (messageRoundNumber == currentRoundNumber) {
      return MessageAge.CURRENT_ROUND;
    }
    return MessageAge.PRIOR_ROUND;
  }

  /** The enum Message age. */
  public enum MessageAge {
    /** Prior round message age. */
    PRIOR_ROUND,
    /** Current round message age. */
    CURRENT_ROUND,
    /** Future round message age. */
    FUTURE_ROUND
  }
}
