package org.hyperledger.besu.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class EventBus {
  private static final List<Consumer<Object>> listeners = new ArrayList<>();

  public static void register(final Consumer<Object> listener) {
    listeners.add(listener);
  }

  public static void post(final Object event) {
    for (Consumer<Object> listener : listeners) {
        listener.accept(event);
    }
  }
}
