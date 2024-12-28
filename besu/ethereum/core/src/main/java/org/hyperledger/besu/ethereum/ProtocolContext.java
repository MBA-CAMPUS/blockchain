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
package org.hyperledger.besu.ethereum;

import org.hyperledger.besu.ethereum.chain.BadBlockManager;
import org.hyperledger.besu.ethereum.chain.MutableBlockchain;
import org.hyperledger.besu.ethereum.mainnet.ProtocolSchedule;
import org.hyperledger.besu.ethereum.worldstate.WorldStateArchive;

import java.util.Optional;

/**
 * Holds the mutable state used to track the current context of the protocol. This is primarily the
 * blockchain and world state archive, but can also hold arbitrary context required by a particular
 * consensus algorithm.
 */
/**  
 * 프로토콜의 현재 컨텍스트를 추적하는 데 사용되는 변경 가능한 상태를 포함합니다.  
 * 주로 블록체인과 월드 상태 아카이브를 포함하며, 특정 합의 알고리즘에  
 * 필요한 임의의 컨텍스트도 포함될 수 있습니다.  
 */

public class ProtocolContext {
  private final MutableBlockchain blockchain;
  private final WorldStateArchive worldStateArchive;
  private final BadBlockManager badBlockManager;
  private final ConsensusContext consensusContext;

  /**
   * Constructs a new ProtocolContext with the given blockchain, world state archive, consensus
   * context, and bad block manager.
   *
   * @param blockchain the blockchain of the protocol context
   * @param worldStateArchive the world state archive of the protocol context
   * @param consensusContext the consensus context of the protocol context
   * @param badBlockManager the bad block manager of the protocol context
   */
  /**  
   * 주어진 블록체인, 월드 상태 아카이브, 합의 컨텍스트,  
   * 그리고 잘못된 블록 관리자를 사용하여 새 ProtocolContext를 생성합니다.  
   *  
   * @param blockchain 프로토콜 컨텍스트의 블록체인  
   * @param worldStateArchive 프로토콜 컨텍스트의 월드 상태 아카이브  
   * @param consensusContext 프로토콜 컨텍스트의 합의 컨텍스트  
   * @param badBlockManager 프로토콜 컨텍스트의 잘못된 블록 관리자  
   */
  public ProtocolContext(
      final MutableBlockchain blockchain,
      final WorldStateArchive worldStateArchive,
      final ConsensusContext consensusContext,
      final BadBlockManager badBlockManager) {
    this.blockchain = blockchain;
    this.worldStateArchive = worldStateArchive;
    this.consensusContext = consensusContext;
    this.badBlockManager = badBlockManager;
  }

  /**
   * Initializes a new ProtocolContext with the given blockchain, world state archive, protocol
   * schedule, consensus context factory, and bad block manager.
   *
   * @param blockchain the blockchain of the protocol context
   * @param worldStateArchive the world state archive of the protocol context
   * @param protocolSchedule the protocol schedule of the protocol context
   * @param consensusContextFactory the consensus context factory of the protocol context
   * @param badBlockManager the bad block manager of the protocol context
   * @return the initialized ProtocolContext
   */
  /**  
  * 주어진 블록체인, 월드 상태 아카이브, 프로토콜 스케줄,  
  * 합의 컨텍스트 팩토리, 그리고 잘못된 블록 관리자를 사용하여  
  * 새 ProtocolContext를 초기화합니다.  
  *  
  * @param blockchain 프로토콜 컨텍스트의 블록체인  
  * @param worldStateArchive 프로토콜 컨텍스트의 월드 상태 아카이브  
  * @param protocolSchedule 프로토콜 컨텍스트의 프로토콜 스케줄  
  * @param consensusContextFactory 프로토콜 컨텍스트의 합의 컨텍스트 팩토리  
  * @param badBlockManager 프로토콜 컨텍스트의 잘못된 블록 관리자  
  * @return 초기화된 ProtocolContext  
  */
  public static ProtocolContext init(
      final MutableBlockchain blockchain,
      final WorldStateArchive worldStateArchive,
      final ProtocolSchedule protocolSchedule,
      final ConsensusContextFactory consensusContextFactory,
      final BadBlockManager badBlockManager) {
    return new ProtocolContext(
        blockchain,
        worldStateArchive,
        consensusContextFactory.create(blockchain, worldStateArchive, protocolSchedule),
        badBlockManager);
  }

  /**
   * Gets the blockchain of the protocol context.
   *
   * @return the blockchain of the protocol context
   */
    /**  
  * 프로토콜 컨텍스트의 블록체인을 가져옵니다.  
  *  
  * @return 프로토콜 컨텍스트의 블록체인  
  */
  public MutableBlockchain getBlockchain() {
    return blockchain;
  }

  /**
   * Gets the world state archive of the protocol context.
   *
   * @return the world state archive of the protocol context
   */
  /**  
   * 프로토콜 컨텍스트의 월드 상태 아카이브를 가져옵니다.  
   *  
   * @return 프로토콜 컨텍스트의 월드 상태 아카이브  
   */
  public WorldStateArchive getWorldStateArchive() {
    return worldStateArchive;
  }

  /**
   * Gets the bad block manager of the protocol context.
   *
   * @return the bad block manager of the protocol context
   */
  /**  
   * 프로토콜 컨텍스트의 잘못된 블록 관리자를 가져옵니다.  
   *  
   * @return 프로토콜 컨텍스트의 잘못된 블록 관리자  
   */
  public BadBlockManager getBadBlockManager() {
    return badBlockManager;
  }

  /**
   * Gets the consensus context of the protocol context.
   *
   * @param <C> the type of the consensus context
   * @param klass the klass
   * @return the consensus context of the protocol context
   */
  /**  
   * 프로토콜 컨텍스트의 합의 컨텍스트를 가져옵니다.  
   *  
   * @param <C> 합의 컨텍스트의 유형  
   * @param klass 클래스  
   * @return 프로토콜 컨텍스트의 합의 컨텍스트  
   */
  public <C extends ConsensusContext> C getConsensusContext(final Class<C> klass) {
    return consensusContext.as(klass);
  }

  /**
   * Gets the safe consensus context of the protocol context.
   *
   * @param <C> the type of the consensus context
   * @param klass the klass
   * @return the consensus context of the protocol context
   */
  /**  
   * 프로토콜 컨텍스트의 안전한 합의 컨텍스트를 가져옵니다.  
   *  
   * @param <C> 합의 컨텍스트의 유형  
   * @param klass 클래스  
   * @return 프로토콜 컨텍스트의 합의 컨텍스트  
   */
  public <C extends ConsensusContext> Optional<C> safeConsensusContext(final Class<C> klass) {
    return Optional.ofNullable(consensusContext)
        .filter(c -> klass.isAssignableFrom(c.getClass()))
        .map(klass::cast);
  }
}
