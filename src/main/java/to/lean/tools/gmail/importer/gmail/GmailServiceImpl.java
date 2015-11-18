/*
 * Copyright 2015 The Mail Importer Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package to.lean.tools.gmail.importer.gmail;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.common.collect.ImmutableList;
import com.google.inject.assistedinject.Assisted;

import javax.annotation.concurrent.NotThreadSafe;
import javax.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toMap;

/**
 * Caching implementation of {@code GmailService}. This implementation assumes
 * that it is the only client affecting the user's mailbox state.
 *
 * <p>This class is <b>not</b> thread-safe, which effectively limits processing
 * a user to a single thread.
 */
@NotThreadSafe
public class GmailServiceImpl implements GmailService {

  private final User user;
  private final Gmail gmail;
  final Supplier<BatchRequest> batchSupplier;

  @Inject
  GmailServiceImpl(
      @Assisted User user,
      Gmail gmail) {
    this(user, gmail, gmail::batch);
  }

  GmailServiceImpl(
      User user,
      Gmail gmail,
      Supplier<BatchRequest> batchSupplier) {
    this.user = user;
    this.gmail = gmail;
    this.batchSupplier = batchSupplier;
  }

  @Override
  public Batch newBatch() {
    BatchRequest batch = gmail.batch();
    return new BatchImpl(user, gmail, () -> batch);
  }

  @Override
  public CompletableFuture<ListMessagesResponse> query(Query query) {
    GmailApiFuture<ListMessagesResponse> future = new GmailApiFuture<>();
    ForkJoinPool.commonPool().execute(() -> {
      // We use a batch request even in immediate mode to keep the callback
      // interface the same.
      BatchRequest batch = batchSupplier.get();

      try {
        gmail.users().messages().list(user.getEmailAddress())
            .setQ(query.getQuery())
            .setFields(query.getField().getRepr())
            .queue(batch, future.getBatchCallback());
        batch.execute();
      } catch (IOException e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<Message> message(String messageId, String fields) {
    GmailApiFuture<Message> future = new GmailApiFuture<>();
    ForkJoinPool.commonPool().execute(() -> {
      // We use a batch request even in immediate mode to keep the callback
      // interface the same.
      BatchRequest batch = batchSupplier.get();

      try {
        gmail.users().messages().get(user.getEmailAddress(), messageId)
            .setFields(fields)
            .queue(batch, future.getBatchCallback());
        batch.execute();
      } catch (IOException e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<Message> modify(
      String id, List<String> labelIdsToAdd, List<String> labelIdsToRemove) {
    return null;
  }

  @Override
  public CompletableFuture<Message> importMessage(
      AbstractInputStreamContent streamContent) {
    GmailApiFuture<Message> future = new GmailApiFuture<>();
    ForkJoinPool.commonPool().execute(() -> {
      // We use a batch request even in immediate mode to keep the callback
      // interface the same.
      BatchRequest batch = batchSupplier.get();

      try {
        Gmail.Users.Messages.GmailImport r = gmail.users()
            .messages()
            .gmailImport(user.getEmailAddress(),
                new Message(),
                streamContent);
        r.queue(batch, future.getBatchCallback());
        batch.execute();
      } catch (IOException e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  @Override
  public CompletableFuture<ListLabelsResponse> labels() {
    GmailApiFuture<ListLabelsResponse> future = new GmailApiFuture<>();
    ForkJoinPool.commonPool().execute(() -> {
      // We use a batch request even in immediate mode to keep the callback
      // interface the same.
      BatchRequest batch = batchSupplier.get();

      try {
        gmail.users().labels().list(user.getEmailAddress())
            .queue(batch, future.getBatchCallback());
        batch.execute();
      } catch (IOException e) {
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  /**
   * Wraps common logic for dealing with the Gmail API.
   *
   * @param <T> the return type of the future
   */
  private static class GmailApiFuture<T> extends CompletableFuture<T> {
    JsonBatchCallback<T> getBatchCallback() {
      return new JsonBatchCallback<T>() {
        @Override
        public void onFailure(
            GoogleJsonError e, HttpHeaders responseHeaders)
            throws IOException {
          GmailApiFuture.this.completeExceptionally(
              new GmailServiceRequestException(e, responseHeaders));
        }

        @Override
        public void onSuccess(
            T response,
            HttpHeaders responseHeaders)
            throws IOException {
          GmailApiFuture.this.complete(response);
        }
      };
    }
  }

  /**
   * Batch wrapper. This allows batches to be re-used, which is against the
   * contract.
   */
  // TODO(flan): Respect the contract
  private static class BatchImpl extends GmailServiceImpl implements Batch {
    public BatchImpl(
        User user,
        Gmail gmail,
        Supplier<BatchRequest> batchSupplier) {
      super(user, gmail, batchSupplier);
    }

    @Override
    public CompletableFuture<Void> execute() {
      if (batchSupplier.get().size() == 0) {
        return CompletableFuture.completedFuture(null);
      }
      return CompletableFuture.runAsync(() -> {
        try {
          batchSupplier.get().execute();
        } catch (IOException e) {
          throw GmailServiceException.forException(e);
        }
      });
    }
  }
}
