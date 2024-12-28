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
package org.hyperledger.besu.consensus.common.bft;

import org.hyperledger.besu.config.BftConfigOptions;
import org.hyperledger.besu.consensus.common.ForksSchedule;
import org.hyperledger.besu.consensus.common.bft.events.BlockTimerExpiry;
import org.hyperledger.besu.ethereum.core.BlockHeader;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Class for starting and keeping organised block timers */
/** 블록 타이머를 시작하고 정리하는 클래스 */
public class BlockTimer {

  private static final Logger LOG = LoggerFactory.getLogger(BlockTimer.class);

  private final ForksSchedule<? extends BftConfigOptions> forksSchedule;
  private final BftExecutors bftExecutors;
  private Optional<ScheduledFuture<?>> currentTimerTask;
  private final BftEventQueue queue;
  private final Clock clock;
  private long blockPeriodSeconds;
  private long emptyBlockPeriodSeconds;

  /**
   * Construct a BlockTimer with primed executor service ready to start timers
   *
   * @param queue The queue in which to put block expiry events
   * @param forksSchedule Bft fork schedule that contains block period seconds
   * @param bftExecutors Executor services that timers can be scheduled with
   * @param clock System clock
   */
  /** 
   * 타이머를 시작할 준비가 된 실행기 서비스와 함께 BlockTimer를 생성합니다.
   *
   * @param queue 블록 만료 이벤트를 넣을 큐
   * @param forksSchedule 블록 기간 초가 포함된 Bft 포크 일정
   * @param bftExecutors 타이머를 예약할 수 있는 실행기 서비스
   * @param clock 시스템 시계
   */
  public BlockTimer(
      final BftEventQueue queue,
      final ForksSchedule<? extends BftConfigOptions> forksSchedule,
      final BftExecutors bftExecutors,
      final Clock clock) {
    this.queue = queue;
    this.forksSchedule = forksSchedule;
    this.bftExecutors = bftExecutors;
    this.currentTimerTask = Optional.empty();
    this.clock = clock;
    this.blockPeriodSeconds = 0;
    this.emptyBlockPeriodSeconds = 0;
  }

  /** Cancels the current running round timer if there is one */
  /** 현재 실행 중인 라운드 타이머가 있으면 취소합니다. */
  public synchronized void cancelTimer() {
    currentTimerTask.ifPresent(t -> t.cancel(false));
    currentTimerTask = Optional.empty();
  }

  /**
   * Whether there is a timer currently running or not
   *
   * @return boolean of whether a timer is ticking or not
   */
  /** 
   * 현재 타이머가 실행 중인지 여부를 나타냅니다.
   *
   * @return 타이머가 실행 중인지 여부를 나타내는 부울 값
   */
  public synchronized boolean isRunning() {
    return currentTimerTask.map(t -> !t.isDone()).orElse(false);
  }

  /**
   * Starts a timer for the supplied round cancelling any previously active block timer
   *
   * @param round The round identifier which this timer is tracking
   * @param chainHeadHeader The header of the chain head
   */
  /** 
   * 제공된 라운드에 대한 타이머를 시작하며, 이전에 활성화된 블록 타이머를 취소합니다.
   *
   * @param round 이 타이머가 추적하는 라운드 식별자
   * @param chainHeadHeader 체인의 헤더
   */
  public synchronized void startTimer(
      final ConsensusRoundIdentifier round, final BlockHeader chainHeadHeader) {
    cancelTimer();

    final long expiryTime;

    // Experimental option for test scenarios only. Not for production use.
    final long blockPeriodMilliseconds =
        forksSchedule.getFork(round.getSequenceNumber()).getValue().getBlockPeriodMilliseconds();
    if (blockPeriodMilliseconds > 0) {
      // Experimental mode for setting < 1 second block periods e.g. for CI/CD pipelines
      // running tests against Besu
      expiryTime = clock.millis() + blockPeriodMilliseconds;
      LOG.warn(
          "Test-mode only xblockperiodmilliseconds has been set to {} millisecond blocks. Do not use in a production system.",
          blockPeriodMilliseconds);
    } else {
      // absolute time when the timer is supposed to expire
      final int currentBlockPeriodSeconds =
          forksSchedule.getFork(round.getSequenceNumber()).getValue().getBlockPeriodSeconds();
      final long minimumTimeBetweenBlocksMillis = currentBlockPeriodSeconds * 1000L;
      expiryTime = chainHeadHeader.getTimestamp() * 1_000 + minimumTimeBetweenBlocksMillis;
    }

    setBlockTimes(round);

    startTimer(round, expiryTime);
  }

  /**
   * Checks if the empty block timer is expired
   *
   * @param chainHeadHeader The header of the chain head
   * @param currentTimeInMillis The current time
   * @return a boolean value
   */
  /** 
   * 빈 블록 타이머가 만료되었는지 확인합니다.
   *
   * @param chainHeadHeader 체인의 헤더
   * @param currentTimeInMillis 현재 시간
   * @return 부울 값
   */
  public synchronized boolean checkEmptyBlockExpired(
      final BlockHeader chainHeadHeader, final long currentTimeInMillis) {
    final long emptyBlockPeriodExpiryTime =
        (chainHeadHeader.getTimestamp() + emptyBlockPeriodSeconds) * 1000;

    if (currentTimeInMillis > emptyBlockPeriodExpiryTime) {
      LOG.debug("Empty Block expired");
      return true;
    }
    LOG.debug("Empty Block NOT expired");
    return false;
  }

  /**
   * Resets the empty block timer
   *
   * @param roundIdentifier The current round identifier
   * @param chainHeadHeader The header of the chain head
   * @param currentTimeInMillis The current time
   */
  /** 
   * 빈 블록 타이머를 재설정합니다.
   *
   * @param roundIdentifier 현재 라운드 식별자
   * @param chainHeadHeader 체인의 헤더
   * @param currentTimeInMillis 현재 시간
   */
  public void resetTimerForEmptyBlock(
      final ConsensusRoundIdentifier roundIdentifier,
      final BlockHeader chainHeadHeader,
      final long currentTimeInMillis) {
    final long emptyBlockPeriodExpiryTime =
        (chainHeadHeader.getTimestamp() + emptyBlockPeriodSeconds) * 1000;
    final long nextBlockPeriodExpiryTime = currentTimeInMillis + blockPeriodSeconds * 1000;

    startTimer(roundIdentifier, Math.min(emptyBlockPeriodExpiryTime, nextBlockPeriodExpiryTime));
  }

  private synchronized void startTimer(
      final ConsensusRoundIdentifier round, final long expiryTime) {
    cancelTimer();
    final long now = clock.millis();

    if (expiryTime > now) {
      final long delay = expiryTime - now;

      final Runnable newTimerRunnable = () -> queue.add(new BlockTimerExpiry(round));

      final ScheduledFuture<?> newTimerTask =
          bftExecutors.scheduleTask(newTimerRunnable, delay, TimeUnit.MILLISECONDS);
      currentTimerTask = Optional.of(newTimerTask);
    } else {
      queue.add(new BlockTimerExpiry(round));
    }
  }

  private synchronized void setBlockTimes(final ConsensusRoundIdentifier round) {
    final BftConfigOptions currentConfigOptions =
        forksSchedule.getFork(round.getSequenceNumber()).getValue();
    this.blockPeriodSeconds = currentConfigOptions.getBlockPeriodSeconds();
    this.emptyBlockPeriodSeconds = currentConfigOptions.getEmptyBlockPeriodSeconds();
  }

  /**
   * Retrieves the Block Period Seconds
   *
   * @return the Block Period Seconds
   */
  /** 
   * 블록 기간 초를 가져옵니다.
   *
   * @return 블록 기간 초
   */
  public synchronized long getBlockPeriodSeconds() {
    return blockPeriodSeconds;
  }

  /**
   * Retrieves the Empty Block Period Seconds
   *
   * @return the Empty Block Period Seconds
   */
  /** 
   * 빈 블록 기간 초를 가져옵니다.
   *
   * @return 빈 블록 기간 초
   */
  public synchronized long getEmptyBlockPeriodSeconds() {
    return emptyBlockPeriodSeconds;
  }
}
