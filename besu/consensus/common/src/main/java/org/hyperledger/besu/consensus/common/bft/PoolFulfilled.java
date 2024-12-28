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


import org.hyperledger.besu.consensus.common.bft.events.PoolCapacityFulfilled;

import java.util.Optional;
import java.util.concurrent.ScheduledFuture;


/** Class for starting and keeping organised block timers */
public class PoolFulfilled {

  // private static final Logger LOG = LoggerFactory.getLogger(PoolFulfilled.class);

  // private final BftExecutors bftExecutors;
  private Optional<ScheduledFuture<?>> currentTimerTask;
  private final BftEventQueue queue;;

  /**
   * Construct a BlockTimer with primed executor service ready to start timers
   *
   * @param queue The queue in which to put block expiry events
   */
  public PoolFulfilled(final BftEventQueue queue) {
    this.queue = queue;
    this.currentTimerTask = Optional.empty();
  }

  /** Cancels the current running round timer if there is one */
  public synchronized void cancelTimer() {
    currentTimerTask.ifPresent(t -> t.cancel(false));
    currentTimerTask = Optional.empty();
  }

  public void addQueueEvent(final ConsensusRoundIdentifier roundIdentifier) {
    queue.add(new PoolCapacityFulfilled(roundIdentifier));
  }
}
