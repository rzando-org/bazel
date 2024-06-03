// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe.serialization;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.MissingFingerprintValueException;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;

/**
 * A bidirectional, in-memory, weak cache storing fingerprint ↔ value associations for the {@link
 * FingerprintValueStore}.
 *
 * <p>The cache supports the possibility of semantically different values having the same serialized
 * representation. For this reason, a distinguisher object can be included in the key for the
 * fingerprint ⇒ value mapping. This object should encapsulate all additional context necessary to
 * deserialize a value. The value ⇒ fingerprint mapping, on the other hand, is expected to be
 * deterministic. See {@link FingerprintWithDistinguisher}.
 */
public final class FingerprintValueCache {
  /**
   * Fingerprint to value cache.
   *
   * <p>Used to deduplicate fetches, or in some cases, where the object to be fetched was already
   * serialized, retrieves the already existing object.
   *
   * <p>The keys can either be a {@link ByteString} or a {@link FingerprintWithDistinguisher}.
   *
   * <p>The values in this cache are always {@code Object} or {@code ListenableFuture<Object>}. We
   * avoid a common wrapper object both for memory efficiency and because our cache eviction policy
   * is based on value GC, and wrapper objects would defeat that.
   *
   * <p>While a fetch for the contents is outstanding, the value in the cache will be a {@link
   * ListenableFuture}. When it is resolved, it is replaced with the unwrapped {@code Object}.
   */
  private final Cache<Object, Object> deserializationCache =
      Caffeine.newBuilder()
          .initialCapacity(SerializationConstants.DESERIALIZATION_POOL_SIZE)
          .weakValues()
          .build();

  /**
   * {@link Object} contents to store result mapping, eventually a fingerprint, but a future while
   * in-flight or in case of errors.
   *
   * <p>This cache deduplicates serializing the same contents to the {@link FingerprintValueStore}.
   * Its entries are as follows.
   *
   * <ul>
   *   <li>key: the content value object, using reference equality
   *   <li>value: either a {@code ListenableFuture<PutOperation>} when the operation is in flight or
   *       a {@link ByteString} fingerprint when it is complete
   * </ul>
   *
   * <p>{@code ListenableFuture<PutOperation>} contains two distinct asynchronous operations.
   *
   * <ul>
   *   <li><em>Outer {@code ListenableFuture}</em>: represents the asynchronous completion of
   *       serialization, fingerprinting and the initialization of the {@link
   *       FingerprintValueStore#put} operation.
   *   <li><em>{@link PutOperation#writeStatus}</em>: represents the completion of the {@link
   *       FingerprintValueStore#put} operation.
   * </ul>
   */
  private final Cache<Object, Object> serializationCache =
      Caffeine.newBuilder()
          .initialCapacity(SerializationConstants.DESERIALIZATION_POOL_SIZE)
          .weakKeys()
          .build();

  /**
   * Disables population of {@link #deserializationCache} during serialization.
   *
   * <p>When serialization completes, the usual behavior is to populate the deserialization cache so
   * that any subsequent deserialization of the value will hit the cache. This blocks the round-trip
   * serialization testing of deserialization code paths as a side effect. Setting this true
   * disables population of the deserialization cache on serialization.
   */
  private final boolean exerciseDeserializationForTesting;

  public FingerprintValueCache() {
    this(/* exerciseDeserializationForTesting= */ false);
  }

  @VisibleForTesting
  public FingerprintValueCache(boolean exerciseDeserializationForTesting) {
    this.exerciseDeserializationForTesting = exerciseDeserializationForTesting;
  }

  /**
   * Gets the result of a previous {@code putOperation} or registers a new one for {@code obj}, the
   * {@link #serializationCache} key.
   *
   * <p>If the {@code obj} has already been serialized or if its serialization is in-flight, returns
   * a non-null object that may be either:
   *
   * <ul>
   *   <li>a {@code ListenableFuture<PutOperation>} if it is still in flight; or
   *   <li>a {@link ByteString} fingerprint if writing to remote storage is successful.
   * </ul>
   *
   * <p>If a {@code ListenableFuture<PutOperation>} is returned, its expected {@link
   * ExecutionException} causes are {@link SerializationException} and {@link IOException}. The
   * caller must ensure that these are the only possible causes.
   *
   * <p>If a previous operation is returned, {@code putOperation} is ignored. Otherwise, if a null
   * value is returned, the caller owns the {@code putOperation} and must ensure it completes and
   * handle its errors.
   *
   * @param distinguisher an optional key distinguisher, see {@link FingerprintWithDistinguisher}
   */
  @Nullable
  Object getOrClaimPutOperation(
      Object obj, @Nullable Object distinguisher, ListenableFuture<PutOperation> putOperation) {
    // Any contention here is caused by two threads racing to serialize the same object. Since
    // the serialization is pure CPU work, it's tempting to simplify this code by using
    // `computeIfAbsent` instead. That unfortunately leads to recursive `ConcurrentMap` updates,
    // which isn't supported.
    Object previous = serializationCache.asMap().putIfAbsent(obj, putOperation);
    if (previous != null) {
      return previous;
    }
    unwrapFingerprintWhenDone(obj, distinguisher, putOperation);
    return null;
  }

  /**
   * Gets the result of a previous {@code getOperation} or registers a new one for {@code
   * fingerprint}, the {@link #deserializationCache} key.
   *
   * <p>This is used to avoid deduplicate fetches or fetching an object that had already been
   * serialized from this cache. If the key is for an already stored or retrieved object, or one
   * where retrievial is in-flight, returns a non-null value that can be one of the following.
   *
   * <ul>
   *   <li>a {@code ListenableFuture<Object>} if retrieval is in-flight; or
   *   <li>an {@link Object} if it is already known for the key.
   * </ul>
   *
   * <p>If a {@code ListenableFuture<Object>} is returned, its possible {@link ExecutionException}
   * causes are {@link SerializationException}, {@link IOException} and {@link
   * MissingFingerprintValueException}. The caller must ensure these are the only possible causes.
   *
   * <p>If a non-null value is returned, {@code getOperation} is ignored. Otherwise, when this
   * returns null, the caller must ensure that {@code getOperation} is eventually completed with the
   * or an error. The caller is responsible for handling errors.
   *
   * @param distinguisher an optional distinguisher, see {@link FingerprintWithDistinguisher}
   */
  @Nullable
  Object getOrClaimGetOperation(
      ByteString fingerprint,
      @Nullable Object distinguisher,
      ListenableFuture<Object> getOperation) {
    Object key = createKey(fingerprint, distinguisher);
    Object previous = deserializationCache.asMap().putIfAbsent(key, getOperation);
    if (previous != null) {
      return previous;
    }
    unwrapValueWhenDone(fingerprint, key, getOperation);
    return null;
  }

  /** Populates the reverse mapping and unwraps futures when they are no longer needed. */
  private void unwrapFingerprintWhenDone(
      Object obj, @Nullable Object distinguisher, ListenableFuture<PutOperation> putOperation) {
    Futures.addCallback(
        putOperation,
        new FutureCallback<PutOperation>() {
          @Override
          public void onSuccess(PutOperation operation) {
            // Serialization and fingerprinting has succeeded and storing the bytes in the
            // FingerprintValueStore has started.

            // Stores the reverse mapping in `deserializationCache`.
            if (!exerciseDeserializationForTesting) {
              deserializationCache.put(createKey(operation.fingerprint(), distinguisher), obj);
            }

            // It's possible to discard the outermost future at this point and only keep the
            // PutOperation instead, but for simplicity, discards both once both succeed.

            Futures.addCallback(
                operation.writeStatus(),
                new FutureCallback<Void>() {
                  @Override
                  public void onSuccess(Void unused) {
                    // The object has been successfully written to remote storage. Discards all the
                    // wrappers.
                    serializationCache.put(obj, operation.fingerprint());
                  }

                  @Override
                  public void onFailure(Throwable t) {
                    // Failure will be reported by the owner of `putOperation`.
                  }
                },
                directExecutor());
          }

          @Override
          public void onFailure(Throwable t) {
            // Failure will be reported by the owner of `putOperation`.
          }
        },
        directExecutor());
  }

  /** Unwraps the future and populates the reverse mapping when done. */
  private void unwrapValueWhenDone(
      ByteString fingerprint, Object key, ListenableFuture<Object> getOperation) {
    Futures.addCallback(
        getOperation,
        new FutureCallback<Object>() {
          @Override
          public void onSuccess(Object value) {
            deserializationCache.put(key, value);
            serializationCache.put(value, fingerprint);
          }

          @Override
          public void onFailure(Throwable t) {
            // Failure will be reported by the owner of the `getOperation`.
          }
        },
        directExecutor());
  }

  private static Object createKey(ByteString fingerprint, @Nullable Object distinguisher) {
    if (distinguisher == null) {
      return fingerprint;
    }
    return FingerprintWithDistinguisher.of(fingerprint, distinguisher);
  }

  /**
   * An extended {@link #deserializationCache} key, needed when the fingerprint alone is not enough.
   *
   * <p>The mapping stores a bidirectional fingerprint to value associations. However, there can be
   * multiple values for the same fingerprint. For example, consider the parent and child objects
   * (A, B). Suppose that both A and B share a common value S. When serializing B, S may be omitted
   * because it is already known to A and can be reinjected during deserialization.
   *
   * <p>The problem is that the fingerprint of B does not include anything about the shared value S.
   * So it could collide on fingerprint with some other (C, D) with a different shared value T.
   *
   * <p>Including a <em>distinguisher</em> in the key to account for the contextual value can be
   * used to avoid conflicts in cases like this.
   */
  @AutoValue
  abstract static class FingerprintWithDistinguisher {
    /** The primary key for a {@link #deserializationCache} entry. */
    abstract ByteString fingerprint();

    /** A secondary key, sometimes needed to resolve ambiguity. */
    abstract Object distinguisher();

    static FingerprintWithDistinguisher of(ByteString fingerprint, Object distinguisher) {
      return new AutoValue_FingerprintValueCache_FingerprintWithDistinguisher(
          fingerprint, distinguisher);
    }
  }
}
