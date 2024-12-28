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

import org.hyperledger.besu.ethereum.eth.transactions.RemovalReason;

import java.util.Locale;

/** The reason why a pending tx has been removed */
enum SequencedRemovalReason implements RemovalReason {
  // 트랜잭션 풀이 최대 크기를 초과하여 새로운 트랜잭션을 추가할 공간을 만들기 위해 낮은 우선순위의 트랜잭션이 강제로 제거되는 경우
  EVICTED(true),
  // 트랜잭션이 트랜잭션 풀에 너무 오래 대기하여 일정 시간이 지나면 자동으로 제거되는 경우
  TIMED_EVICTION(true),
  // 같은 송신자와 논스를 가진 새로운 트랜잭션이 들어와 기존 트랜잭션이 대체되는 경우
  REPLACED(false),
  // 트랜잭션 자체가 무효하거나 잘못되어 제거되는 경우
  INVALID(false);

  private final String label;
  private final boolean stopTracking;

  SequencedRemovalReason(final boolean stopTracking) {
    this.label = name().toLowerCase(Locale.ROOT);
    this.stopTracking = stopTracking;
  }

  @Override
  public String label() {
    return label;
  }

  @Override
  public boolean stopTracking() {
    return stopTracking;
  }
}
