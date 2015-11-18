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

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.auto.value.AutoValue;
import com.google.common.base.Throwables;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A user-oriented facade over {@link com.google.api.services.gmail.Gmail} that
 * uses {@link CompletableFuture}s instead of traditional callbacks. This allows
 * the same interface to apply to both one-off requests and batch requests.
 *
 * <p>For example, a single query can be written as:
 * <pre>{@code
 *   gmailService
 *       .query(
 *           new QueryBuilder()
 *               .rfc822MsgId("XXX@yyy")
 *               .withFields(MESSAGE_ID)
 *               .build())
 *       .then[...]
 * }</pre>
 * <p>A batch query can be written much the same way:
 * <pre>{@code
 *   Batch batch = gmailService.newBatch();
 *   batch.query(
 *           new QueryBuilder()
 *               .rfc822MsgId("XXX@yyy")
 *               .withFields(MESSAGE_ID)
 *               .build())
 *       .then[...]
 *   batch.query(
 *           new QueryBuilder()
 *               .rfc822MsgId("AAA@bbb")
 *               .withFields(MESSAGE_ID)
 *               .build())
 *       .then[...]
 *   batch.execute().get();
 * }</pre>
 * In fact, the return value of {@code newBatch} is another implementation of
 * the {@code GmailService} that defers its results to the end.
 *
 * <p>Note that {@code batch.execute()} returns a
 * {@code CompletableFuture<Void>} that guarantees that when it completes, all
 * non-asynchronous dependencies of the batch will also be complete.
 */
interface GmailService {

  enum Field {
    /** Whatever fields are returned by the server by default. */
    DEFAULT(""),
    /** Request that just the message IDs are returned. (Very efficient.) */
    MESSAGE_ID("message(id)");

    private final String repr;

    Field(String repr) {
      this.repr = repr;
    }

    public String getRepr() {
      return repr;
    }
  }

  @AutoValue
  abstract class Query {

    public static Builder builder() {
      return new AutoValue_GmailService_Query.Builder();
    }

    abstract String getQuery();
    abstract Field getField();

    @AutoValue.Builder
    abstract static class Builder {
      abstract Query build();
      abstract Builder setQuery(String query);
      abstract Builder setField(Field field);

      Builder rfc822MsgId(String rfc822MsgId) {
        setQuery("rfc822MsgId:" + rfc822MsgId);
        return this;
      }
    }
  }

  /**
   * Returns a new batch interface.
   */
  GmailService.Batch newBatch();

  /**
   * Sends a list messages query to the server for the current user. The query
   * is executed asynchronously to the calling thread.
   *
   * @param query the query parameters to use
   * @return a {@code CompletableFuture} for the {@link ListMessagesResponse}.
   *     If there is an exception during the request, it will be coerced to a
   *     {@code GmailServiceException}.
   *
   * @see com.google.api.services.gmail.Gmail.Users.Messages.List
   */
  CompletableFuture<ListMessagesResponse> query(Query query);

  /**
   * Sends a list messages query to the server for the current user. The query
   * is executed asynchronously to the calling thread.
   *
   * @param messageId the Gmail internal message ID to fetch
   * @param fields a string specifying the desired response fields
   * @return a {@code CompletableFuture} for the {@link Message}. If there is an
   *     exception during the request, it will be coerced to a
   *     {@code GmailServiceException}.
   *
   * @see com.google.api.services.gmail.Gmail.Users.Messages.List
   */
  CompletableFuture<Message> message(String messageId, String fields);

  /**
   * Modifies the labels on the messages with {@code id} by adding the labels
   * in {@code labelIdsToAdd} and removing the labels in
   * {@code labelIdsToRemove}.
   *
   * @param id the Gmail API's id of the message
   * @param labelIdsToAdd the label ids of the labels to add
   * @param labelIdsToRemove the label ids of the labels to remove
   * @return a {@code CompletableFuture} for the {@link Message}. If there is an
   *     exception during the request, it will be coerced to a
   *     {@code GmailServiceException}.
   */
  CompletableFuture<Message> modify(
      String id,
      List<String> labelIdsToAdd,
      List<String> labelIdsToRemove);

  /**
   * Uploads a raw message to Gmail. The {@code Message} returned by the future
   * represents the message after it is uploaded. The upload is executed
   * asynchronously to the calling thread.
   *
   * @param streamContent the raw message, in streaming form. This object must
   *     be usable from the execution thread in a thread-safe manner.
   * @return a {@code CompletableFuture} for the {@link Message} after it is
   *     uploaded.
   */
  CompletableFuture<Message> importMessage(
      AbstractInputStreamContent streamContent);

  /**
   * Sends a list labels query to the server for the current user. The query is
   * executed asynchronously to the calling thread.
   *
   * @return a {@code CompletableFuture} for the {@link ListLabelsResponse}.
   *     If there is an exception during the request, it will be coerced to a
   *     {@code GmailServiceException}.
   */
  CompletableFuture<ListLabelsResponse> labels();

  /**
   * Batch interface for {@code GmailService}.
   *
   * <p>Methods on this interface return {@code CompletableFutures} just like
   * the regular interface so that they can be used immediately as promises.
   * However, the requests will not actually be executed until the
   * {@link #execute()} method is called.
   */
  interface Batch extends GmailService {
    /**
     * Executes the requests gathered in the batch. The batch instance is no
     * longer usable after calling this method and future calls will result in
     * an {@code IllegalStateException}.
     *
     * @return a new future that can be used to wait for all of the results
     */
    CompletableFuture<Void> execute();
  }

  class GmailServiceException extends RuntimeException {
    public GmailServiceException(Throwable e) {
      super(e);
    }

    public GmailServiceException(String message) {
      super(message);
    }

    public static GmailServiceException forException(Exception e) {
      return new GmailServiceException(Throwables.getRootCause(e));
    }
  }

  class GmailServiceRequestException extends GmailServiceException {
    private final GoogleJsonError googleJsonError;
    private final HttpHeaders responseHeaders;

    public GmailServiceRequestException(
        GoogleJsonError googleJsonError, HttpHeaders responseHeaders) {
      super(googleJsonError.getMessage());
      this.googleJsonError = googleJsonError;
      this.responseHeaders = responseHeaders;
    }

    public GoogleJsonError getGoogleJsonError() {
      return googleJsonError;
    }

    public HttpHeaders getResponseHeaders() {
      return responseHeaders;
    }
  }
}
