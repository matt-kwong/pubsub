/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.pubsub.flink.util;

import com.google.common.base.Optional;
import javax.annotation.Nullable;

/**
 * Utility class used to help connect {@link PubSubSink} and {@link PubSubSource} to a Google Cloud
 * Pub/Sub emulator.
 */
public class EmulatorEndpoint {
  public static final String EMULATOR_ENDPOINT_PREFIX = "emulator:///";

  public static String toEmulatorEndpoint(String endpoint) {
    return EMULATOR_ENDPOINT_PREFIX + endpoint;
  }

  @Nullable
  public static String getEmulatorEndpoint(Optional<String> endpoint) {
    String emulatorEndpoint = null;
    // Prioritize using an emulator endpoint set in env var PUBSUB_EMULATOR_HOST.
    if ((emulatorEndpoint = System.getenv("PUBSUB_EMULATOR_HOST")) != null) {
      return emulatorEndpoint;
    }
    if (endpoint.isPresent() && endpoint.get().startsWith(EMULATOR_ENDPOINT_PREFIX)) {
      return endpoint.get().replaceFirst(EMULATOR_ENDPOINT_PREFIX, "");
    }
    return emulatorEndpoint;
  }
}
