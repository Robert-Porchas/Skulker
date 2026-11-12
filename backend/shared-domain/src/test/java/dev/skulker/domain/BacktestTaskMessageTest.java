package dev.skulker.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BacktestTaskMessageTest {
  @Test
  void factoryCreatesVersionOneReference() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    assertEquals(new BacktestTaskMessage(id, 1), BacktestTaskMessage.of(id));
  }

  @Test
  void rejectsMissingJobIdentity() {
    assertThrows(NullPointerException.class, () -> BacktestTaskMessage.of(null));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 2, Integer.MAX_VALUE})
  void rejectsUnknownSchemaInsteadOfInterpretingItAsVersionOne(int version) {
    assertThrows(
        IllegalArgumentException.class, () -> new BacktestTaskMessage(UUID.randomUUID(), version));
  }
}
