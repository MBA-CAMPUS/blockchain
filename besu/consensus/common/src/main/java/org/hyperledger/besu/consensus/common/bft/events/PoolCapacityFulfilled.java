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
package org.hyperledger.besu.consensus.common.bft.events;

import java.util.Objects;

import org.hyperledger.besu.consensus.common.bft.ConsensusRoundIdentifier;


/** 트랜잭션 풀 용량 기준 충족 이벤트가 발생했을 때 처리 로직 */
public final class PoolCapacityFulfilled implements BftEvent {
  final ConsensusRoundIdentifier roundIdentifier;

  /**
   * 용량 충족 이벤트 생성자
   *
   * roundIdentifier The roundIdentifier that the expired timer belonged to
   */
  public PoolCapacityFulfilled(final ConsensusRoundIdentifier roundIdentifier) {
    this.roundIdentifier = roundIdentifier;
  }

  @Override
  public BftEvents.Type getType() {
    return BftEvents.Type.POOL_CAPACITY_FULFILLED;
  }

    /**
   * Gets round identifier.
   *
   * @return the round Identifier
   */
  public ConsensusRoundIdentifier getRoundIdentifier() {
    return roundIdentifier;
  }

  // @Override
  // public String toString() {
  //   return MoreObjects.toStringHelper(this).add("Round Identifier", roundIdentifier).toString();
  // }

  // @Override
  // public boolean equals(final Object o) {
  //   if (this == o) {
  //     return true;
  //   }
  //   if (o == null || getClass() != o.getClass()) {
  //     return false;
  //   }
  //   final BlockTimerExpiry that = (BlockTimerExpiry) o;
  //   return Objects.equals(roundIdentifier, that.roundIdentifier);
  // }

  @Override
  public int hashCode() {
    return Objects.hash();
  }
}