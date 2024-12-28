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
package org.hyperledger.besu.ethereum.eth.transactions.layered;

import org.hyperledger.besu.ethereum.eth.transactions.RemovalReason;

import java.util.Locale;

/** The reason why a pending tx has been removed */
/** 대기 중인 트랜잭션이 제거된 이유 */
interface LayeredRemovalReason extends RemovalReason {
  /**
   * From where the tx has been removed
   *
   * @return removed from item
   */
  /**
   * 트랜잭션이 제거된 위치
   *
   * @return 제거된 위치
   */
  RemovedFrom removedFrom();

  /** There are 2 kinds of removals, from a layer and from the pool. */
  enum RemovedFrom {
    /**
     * Removing from a layer, can be also seen as a <i>move</i> between layers, since it is removed
     * from the current layer and added to another layer, for example in the case the layer is full
     * and some txs need to be moved to the next layer, or in the opposite case when some txs are
     * promoted to the upper layer.
     */
    /**
     * 레이어에서 제거하는 경우로, 이는 현재 레이어에서 제거되어 다른 레이어로 이동되는 <i>이동</i>으로 볼 수 있습니다. 예를 들어 레이어가 꽉 차서 일부 트랜잭션을
     * 다음 레이어로 이동해야 하는 경우나, 반대로 일부 트랜잭션이 상위 레이어로 승격되는 경우입니다.
     */
    LAYER,
    /**
     * Removing from the pool, instead means that the tx is directly removed from the pool, and it
     * will not be present in any layer, for example, when it is added to an imported block, or it
     * is replaced by another tx.
     */
    /**
     * 풀에서 제거되는 경우로, 이는 트랜잭션이 풀에서 직접 제거되며 이후 어떤 레이어에서도 존재하지 않게 되는 것을 의미합니다. 예를 들어, 트랜잭션이 블록에 포함되어
     * 확인된 경우나 다른 트랜잭션으로 대체된 경우가 해당됩니다.
     */
    POOL
  }

  /** The reason why the tx has been removed from the pool */
  enum PoolRemovalReason implements LayeredRemovalReason {
    /**
     * Tx removed since it is confirmed on chain, as part of an imported block. Keep tracking since
     * makes no sense to reprocess a confirmed.
     */
    /** 트랜잭션이 블록에 포함되어 체인에서 확인되었기 때문에 제거됨. 확인된 트랜잭션을 다시 처리할 필요가 없으므로 계속 추적합니다. */
    CONFIRMED(false),
    /**
     * Tx removed since it has been replaced by another one added in the same layer. Keep tracking
     * since makes no sense to reprocess a replaced tx.
     */
    /** 동일 레이어에서 추가된 다른 트랜잭션에 의해 대체되어 제거됨. 대체된 트랜잭션을 다시 처리할 필요가 없으므로 계속 추적합니다. */
    REPLACED(false),
    /**
     * Tx removed since it has been replaced by another one added in another layer. Keep tracking
     * since makes no sense to reprocess a replaced tx.
     */
    /** 다른 레이어에서 추가된 다른 트랜잭션에 의해 대체되어 제거됨. 대체된 트랜잭션을 다시 처리할 필요가 없으므로 계속 추적합니다. */
    CROSS_LAYER_REPLACED(false),
    /**
     * Tx removed when the pool is full, to make space for new incoming txs. Stop tracking it so we
     * could re-accept it in the future.
     */
    /** 새로운 트랜잭션을 위해 공간을 마련하기 위해 풀이 가득 찼을 때 트랜잭션이 제거됨. 미래에 다시 수락할 수 있도록 추적을 중단합니다. */
    DROPPED(true),
    /**
     * Tx removed since found invalid after it was added to the pool, for example during txs
     * selection for a new block proposal. Keep tracking since we do not want to reprocess an
     * invalid tx.
     */
    /**
     * 풀에 추가된 이후 유효하지 않은 것으로 확인되어 제거됨. 예를 들어, 새로운 블록 제안 시 트랜잭션 선택 과정에서 발견된 경우입니다. 유효하지 않은 트랜잭션을 다시
     * 처리하지 않기 위해 계속 추적합니다.
     */
    INVALIDATED(false),
    /**
     * Special case, when for a sender, discrepancies are found between the world state view and the
     * pool view, then all the txs for this sender are removed and added again. Discrepancies, are
     * rare, and can happen during a short windows when a new block is being imported and the world
     * state being updated. Keep tracking since it is removed and re-added.
     */
    /**
     * 특별한 경우로, 송신자와 관련된 풀 및 세계 상태 간의 불일치가 발견될 때 해당 송신자의 모든 트랜잭션이 제거되고 다시 추가됩니다. 불일치는 드물며 새로운 블록이
     * 수입되고 세계 상태가 업데이트될 때 짧은 시간 동안 발생할 수 있습니다. 제거 후 다시 추가되므로 계속 추적합니다.
     */
    RECONCILED(false),
    /**
     * When a pending tx is penalized its score is decreased, if at some point its score is lower
     * than the configured minimum then the pending tx is removed from the pool. Stop tracking it so
     * we could re-accept it in the future.
     */
    /** 대기 중인 트랜잭션의 점수가 감소되어 설정된 최소 점수 이하가 될 때 제거됨. 미래에 다시 수락할 수 있도록 추적을 중단합니다. */
    BELOW_MIN_SCORE(true);

    private final String label;
    private final boolean stopTracking;

    PoolRemovalReason(final boolean stopTracking) {
      this.label = name().toLowerCase(Locale.ROOT);
      this.stopTracking = stopTracking;
    }

    @Override
    public RemovedFrom removedFrom() {
      return RemovedFrom.POOL;
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

  /** The reason why the tx has been moved across layers */
  enum LayerMoveReason implements LayeredRemovalReason {
    /**
     * When the current layer is full, and this tx needs to be moved to the lower layer, in order to
     * free space.
     */
    EVICTED,
    /**
     * Specific to sequential layers, when a tx is removed because found invalid, then if the sender
     * has other txs with higher nonce, then a gap is created, and since sequential layers do not
     * permit gaps, txs following the invalid one need to be moved to lower layers.
     */
    FOLLOW_INVALIDATED,
    /**
     * When a tx is moved to the upper layer, since it satisfies all the requirement to be promoted.
     */
    PROMOTED,
    /**
     * When a tx is moved to the lower layer, since it, or a preceding one from the same sender,
     * does not respect anymore the requisites to stay in this layer.
     */
    DEMOTED;

    private final String label;

    LayerMoveReason() {
      this.label = name().toLowerCase(Locale.ROOT);
    }

    @Override
    public RemovedFrom removedFrom() {
      return RemovedFrom.LAYER;
    }

    @Override
    public String label() {
      return label;
    }

    /**
     * We need to continue to track a tx when is moved between layers
     *
     * @return always false
     */
    @Override
    public boolean stopTracking() {
      return false;
    }
  }
}
