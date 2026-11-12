package dev.skulker.store;

import java.util.UUID;

/** Scalar projection avoids loading potentially large stored result documents for batch polling. */
interface BatchStatusProjection {
  UUID getId();

  String getStatus();
}
